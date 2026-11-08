package com.molina.vault.store

import android.content.Context
import com.molina.vault.crypto.InvalidVaultException
import com.molina.vault.crypto.WrongPasswordOrTamperedException
import com.molina.vault.crypto.PasswordPolicy
import com.molina.vault.crypto.SessionLock
import com.molina.vault.crypto.StreamCrypto
import com.molina.vault.crypto.VaultCrypto
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID

private val REC_MAGIC = byteArrayOf(0x4D, 0x56, 0x52, 0x43) // "MVRC"
private const val REC_VERSION: Byte = 2
private const val REC_VERSION_BLOB: Byte = 3   // MOLINA-H11-GLUE (lihat BlobRecord)
/** Batas per berkas gudang (streaming v3). */
const val VAULT_MAX_FILE_BYTES = StreamCrypto.MAX_STREAM_BYTES
/** Rekomendasi: blob <= 1 MiB tetap v2 (put), di atasnya v3 (putFile). Belum dikonfirmasi pengguna. */
const val VAULT_LARGE_THRESHOLD = 1L * 1024 * 1024
private const val SPACE_MARGIN = 8L * 1024 * 1024
private const val HEAD = 4 + 1 + 4
private const val MIN_SEALED = 29 // iv(12) + tag(16) + >=1 byte
private const val MAX_PAYLOAD = 32 * 1024 * 1024
private const val ROTATE_DIR = "rotate.tmp"      // MOLINA-H8-ROTATE
private const val ROTATE_COMMIT = "COMMIT"

/** Rotasi sudah dikomit tetapi belum selesai dipindahkan; akan dituntaskan otomatis saat vault dibuka. */
class RotationPendingException : Exception("Rotasi kunci tertunda dan vault dikunci. Buka kembali vault untuk menuntaskannya.")

/** Record v3 (berkas besar) tidak boleh dimuat utuh lewat read(); pakai openLarge/exportBlob. */
class LargeRecordException : Exception("Record berkas besar: gunakan openLarge dan exportBlob")

/** Ruang kosong tidak cukup untuk impor/rotasi. */
class InsufficientSpaceException(val needed: Long, val free: Long) : Exception("Ruang penyimpanan tidak cukup")

/** Hasil penyimpanan berkas besar. */
class StoredFile(val meta: RecordMeta, val info: BlobInfo)

/** Penyimpanan privat: noBackupFilesDir/vault. Satu file per entri, metadata dan isi dienkripsi terpisah. */
class VaultStore(context: Context) {
    internal val dir = File(context.applicationContext.noBackupFilesDir, "vault").apply { mkdirs() }
    internal val headerFile = File(dir, "vault.mvlt")
    internal val prefs = VaultPrefs(context)   // MOLINA-H7B
    /** Diganti di uji. Android 8: File.getUsableSpace pada noBackupFilesDir. */
    internal var freeSpace: () -> Long = { dir.usableSpace }

    fun exists(): Boolean = headerFile.exists()

    fun create(master: CharArray) {
        check(!exists()) { "Vault sudah ada" }
        require(PasswordPolicy.isAcceptable(master)) { "Master password terlalu lemah" }
        val created = VaultCrypto.create(master)
        atomicWrite(headerFile, created.fileBytes)
        resetManifestState()   // MOLINA-H15-MANIFEST
        SessionLock.begin(created.vaultKey)
        runCatching { SessionLock.use { k -> mfWrite(k, buildFromDisk(k, 1L)) } }
    }

    fun unlock(master: CharArray) {
        recoverRotation()
        val file = headerFile.readBytes()
        val key = VaultCrypto.unlock(master, file)
        if (VaultCrypto.needsUpgrade(file)) atomicWrite(headerFile, VaultCrypto.rewrap(master, key))
        SessionLock.begin(key)
    }

    /**
     * MOLINA-H8-ROTATE: ganti master SEKALIGUS merotasi vaultKey. Semua record dienkripsi ulang ke staging dan
     * diverifikasi baca-ulang; baru setelah penanda COMMIT tertulis, berkas dipindahkan dan header diganti.
     * Bila terhenti di tengah jalan, [recoverRotation] menuntaskan (ada COMMIT) atau membuang staging (tanpa COMMIT).
     * Mengembalikan jumlah record yang sudah tidak terbaca dan dibiarkan apa adanya.
     */
    fun changeMaster(newMaster: CharArray): Int {
        require(PasswordPolicy.isAcceptable(newMaster)) { "Master password terlalu lemah" }
        recoverRotation()
        var skipped = 0
        var newCounter = 0L   // MOLINA-H15-MANIFEST
        try {
            SessionLock.rotate { old ->
                val fresh = VaultCrypto.newVaultKey()
                val staging = File(dir, ROTATE_DIR)
                var committed = false
                try {
                    staging.deleteRecursively()
                    check(staging.mkdirs()) { "Gagal menyiapkan staging" }
                    needSpace(rotationBytes() + SPACE_MARGIN)   // rotasi menggandakan ruang sementara
                    var rotated = 0
                    for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
                        if (!f.isFile || !f.name.startsWith("rec_") || !f.name.endsWith(".mvr")) continue
                        val id = f.name.removePrefix("rec_").removeSuffix(".mvr")
                        if (!validId(id)) { skipped++; continue }
                        try {
                            stageRotated(old, fresh, f, id, staging)
                            rotated++
                        } catch (e: WrongPasswordOrTamperedException) {
                            skipped++
                        } catch (e: InvalidVaultException) {
                            skipped++
                        }
                    }
                    check(skipped == 0 || rotated > 0) { "Rotasi dibatalkan: tidak ada record yang bisa dibaca" }
                    newCounter = stageManifest(old, fresh, staging)   // MOLINA-H15-MANIFEST
                    writeSynced(File(staging, headerFile.name), VaultCrypto.rewrap(newMaster, fresh))
                    writeSynced(File(staging, ROTATE_COMMIT), byteArrayOf(1))
                    committed = true
                    commitRotation(staging)
                } catch (e: Exception) {
                    fresh.fill(0)
                    if (!committed) staging.deleteRecursively()
                    throw if (committed) RotationPendingException() else e
                }
                fresh
            }
        } catch (e: RotationPendingException) {
            SessionLock.lock()
            throw e
        }
        if (newCounter > 0L) prefs.setManifestCounter(newCounter)   // MOLINA-H15-MANIFEST
        return skipped
    }

    /** true jika [pass] sama dengan master saat ini; dipakai agar passphrase cadangan berbeda dari master. */
    fun matchesMaster(pass: CharArray): Boolean {
        val k = try { VaultCrypto.unlock(pass, headerFile.readBytes()) } catch (e: Exception) { return false }
        k.fill(0)
        return true
    }

    fun list(): VaultListing = SessionLock.use { key ->
        var bad = 0
        val items = ArrayList<RecordMeta>()
        for (f in dir.listFiles().orEmpty()) {
            if (!f.name.startsWith("rec_") || !f.name.endsWith(".mvr")) continue
            try { items.add(readMeta(key, f)) } catch (e: Exception) { bad++ }
        }
        VaultListing(items.sortedBy { it.title.lowercase() }, bad)
    }

    fun put(
        type: RecordType,
        title: String,
        fields: List<ByteArray>,
        id: String = UUID.randomUUID().toString(),
        folder: String = "",
        tags: List<String> = emptyList(),
    ): RecordMeta {
        require(validId(id)) { "ID tidak valid" }
        require(title.isNotBlank() && title.length <= 200) { "Judul tidak valid" }
        // MOLINA-H6-FOLDER
        val cleanFolder = LabelPolicy.cleanFolder(folder)
        val cleanTags = tags.take(LabelPolicy.MAX_TAGS).map { it.take(LabelPolicy.MAX_TAG) }.filter { it.isNotEmpty() }
        val payload = PayloadCodec.pack(fields)
        try {
            require(payload.size <= MAX_PAYLOAD) { "Data terlalu besar (maks 32 MB)" }
            return SessionLock.use { key ->
                val now = System.currentTimeMillis()
                val metaObj = JSONObject().put("id", id).put("type", type.name)
                    .put("title", title).put("ts", now)
                if (cleanFolder.isNotEmpty()) metaObj.put("folder", cleanFolder)
                if (cleanTags.isNotEmpty()) metaObj.put("tags", JSONArray(cleanTags))
                val metaJson = metaObj.toString().toByteArray(Charsets.UTF_8)
                val sealedMeta = VaultCrypto.encryptRecord(key, metaJson, aad("meta", id))
                metaJson.fill(0)
                val sealedData = VaultCrypto.encryptRecord(key, payload, aad("data", id))
                val out = ByteBuffer.allocate(HEAD + sealedMeta.size + sealedData.size)
                    .put(REC_MAGIC).put(REC_VERSION).putInt(sealedMeta.size)
                    .put(sealedMeta).put(sealedData).array()
                tracked(key, id) { atomicWrite(recFile(id), out) }   // MOLINA-H15-MANIFEST
                prefs.noteChange()
                RecordMeta(id, type, title, now, cleanFolder, cleanTags)
            }
        } finally {
            payload.fill(0)
        }
    }

    /** Pemanggil wajib me-wipe setiap ByteArray hasil setelah selesai. */
    fun read(id: String): List<ByteArray> = SessionLock.use { key ->
        require(validId(id)) { "ID tidak valid" }
        if (BlobRecord.isBlobRecord(recFile(id))) throw LargeRecordException()
        val raw = recFile(id).readBytes()
        val n = sealedMetaSize(raw)
        val plain = VaultCrypto.decryptRecord(key, raw.copyOfRange(HEAD + n, raw.size), aad("data", id))
        try { PayloadCodec.unpack(plain) } finally { plain.fill(0) }
    }

    fun delete(id: String) {
        require(validId(id)) { "ID tidak valid" }
        SessionLock.use { key -> tracked(key, id, remove = true) { secureDelete(recFile(id)) }; prefs.noteChange() }   // MOLINA-H15-MANIFEST
    }

    /** MOLINA-H7B: ubah folder (dan tag bila diberikan) tanpa menyentuh isi; hanya metadata yang dienkripsi ulang. */
    fun relabel(id: String, folder: String, tags: List<String>? = null) {
        require(validId(id)) { "ID tidak valid" }
        val cleanFolder = LabelPolicy.cleanFolder(folder)
        SessionLock.use { key ->
            val rf = recFile(id)
            if (BlobRecord.isBlobRecord(rf)) {   // MOLINA-H11-GLUE: tanpa memuat blob
                needSpace(rf.length() + SPACE_MARGIN)
                val head = BlobRecord.readHead(rf)
                tracked(key, id) { BlobRecord.relabel(rf, relabeledMeta(key, head.sealedMeta, id, cleanFolder, tags)) }   // MOLINA-H15-MANIFEST
                prefs.noteChange()
                return@use
            }
            val raw = rf.readBytes()
            try {
                val n = sealedMetaSize(raw)
                val plain = VaultCrypto.decryptRecord(key, raw.copyOfRange(HEAD, HEAD + n), aad("meta", id))
                val j = try { JSONObject(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
                if (j.getString("id") != id) throw InvalidVaultException("ID tidak cocok")
                if (cleanFolder.isEmpty()) j.remove("folder") else j.put("folder", cleanFolder)
                if (tags != null) {
                    val clean = tags.take(LabelPolicy.MAX_TAGS).map { it.take(LabelPolicy.MAX_TAG) }.filter { it.isNotEmpty() }
                    if (clean.isEmpty()) j.remove("tags") else j.put("tags", JSONArray(clean))
                }
                val metaJson = j.toString().toByteArray(Charsets.UTF_8)
                val sealedMeta = VaultCrypto.encryptRecord(key, metaJson, aad("meta", id))
                metaJson.fill(0)
                val rest = raw.size - HEAD - n
                val out = ByteBuffer.allocate(HEAD + sealedMeta.size + rest)
                    .put(REC_MAGIC).put(REC_VERSION).putInt(sealedMeta.size)
                    .put(sealedMeta).put(raw, HEAD + n, rest).array()
                tracked(key, id) { atomicWrite(recFile(id), out) }   // MOLINA-H15-MANIFEST
                prefs.noteChange()
            } finally {
                raw.fill(0)
            }
        }
    }

    /** Memindahkan semua entri dari folder [from] ke [to] (kosong = tanpa folder). Nama yang sudah ada digabung. */
    fun renameFolder(from: String, to: String): Int {
        var moved = 0
        for (m in list().items) {
            if (m.folder == from) {
                relabel(m.id, to)
                moved++
            }
        }
        return moved
    }

    // ---- berkas besar, record v3 (MOLINA-H11-GLUE) ----

    /**
     * Menyimpan berkas besar secara streaming (memori O(64 KB)). [fields] berisi semua field; isi di [blobIndex]
     * diabaikan dan diganti aliran [blob]. [expectedBytes] (jika diketahui) dipakai untuk cek ruang sebelum menulis.
     * Gagal di tengah: .tmp dihapus, record lama (bila ada) utuh.
     */
    fun putFile(
        type: RecordType,
        title: String,
        fields: List<ByteArray>,
        blobIndex: Int,
        blob: InputStream,
        id: String = UUID.randomUUID().toString(),
        folder: String = "",
        tags: List<String> = emptyList(),
        expectedBytes: Long = -1L,
        maxBytes: Long = StreamCrypto.MAX_STREAM_BYTES,
    ): StoredFile {
        require(validId(id)) { "ID tidak valid" }
        require(title.isNotBlank() && title.length <= 200) { "Judul tidak valid" }
        require(expectedBytes <= maxBytes) { "Berkas terlalu besar" }
        val cleanFolder = LabelPolicy.cleanFolder(folder)
        val cleanTags = tags.take(LabelPolicy.MAX_TAGS).map { it.take(LabelPolicy.MAX_TAG) }.filter { it.isNotEmpty() }
        needSpace((if (expectedBytes >= 0) StreamCrypto.encryptedSize(expectedBytes) else maxBytes) + SPACE_MARGIN)
        return SessionLock.use { key ->
            val now = System.currentTimeMillis()
            val metaObj = JSONObject().put("id", id).put("type", type.name).put("title", title).put("ts", now)
            if (cleanFolder.isNotEmpty()) metaObj.put("folder", cleanFolder)
            if (cleanTags.isNotEmpty()) metaObj.put("tags", JSONArray(cleanTags))
            val metaJson = metaObj.toString().toByteArray(Charsets.UTF_8)
            try {
                val info = tracked(key, id) { BlobRecord.write(recFile(id), id, key, sealer(key), metaJson, fields, blobIndex, blob, maxBytes) }   // MOLINA-H15-MANIFEST
                prefs.noteChange()
                StoredFile(RecordMeta(id, type, title, now, cleanFolder, cleanTags), info)
            } finally {
                metaJson.fill(0)
            }
        }
    }

    fun isLarge(id: String): Boolean {
        require(validId(id)) { "ID tidak valid" }
        return BlobRecord.isBlobRecord(recFile(id))
    }

    /** Bagian kecil record v3 + deskriptor blob (ukuran, SHA-256) tanpa menyentuh blob. Pemanggil wajib me-wipe fields. */
    fun openLarge(id: String): BlobOpened = SessionLock.use { key ->
        require(validId(id)) { "ID tidak valid" }
        BlobRecord.openSmall(recFile(id), id, sealer(key))
    }

    /**
     * Mendekripsi blob ke [out] per chunk. Bila melempar exception, [out] bisa berisi bagian awal plaintext:
     * pemanggil wajib membuang tujuan ekspor (SAF: DocumentsContract.deleteDocument).
     */
    fun exportBlob(id: String, out: OutputStream): StreamCrypto.Result = SessionLock.use { key ->
        require(validId(id)) { "ID tidak valid" }
        BlobRecord.exportBlob(recFile(id), id, key, sealer(key), out)
    }

    // ---- gudang: sunting berkas besar, pemeriksaan integritas, pemakaian (MOLINA-H14-GUDANG) ----

    /**
     * Mengubah judul dan field non-blob record v3 (mis. nama file, catatan) tanpa membuka blob. [fields] harus sama
     * banyak dengan record; isi pada indeks blob diabaikan. Deskriptor blob (ukuran, SHA-256) dipertahankan dan dicek
     * ulang setelah ditulis. Folder dan tag tidak berubah. Atomik; gagal berarti record lama utuh.
     */
    fun editLarge(id: String, title: String, fields: List<ByteArray>): RecordMeta {
        require(validId(id)) { "ID tidak valid" }
        require(title.isNotBlank() && title.length <= 200) { "Judul tidak valid" }
        return SessionLock.use { key ->
            val rf = recFile(id)
            require(BlobRecord.isBlobRecord(rf)) { "Bukan berkas besar" }
            needSpace(rf.length() + SPACE_MARGIN)
            val sl = sealer(key)
            val head = BlobRecord.readHead(rf)
            val old = BlobRecord.openSmall(head, id, sl)
            try {
                require(fields.size == old.fields.size) { "Jumlah field tidak cocok" }
                val metaPlain = sl.open("meta", id, head.sealedMeta)
                val j = try { JSONObject(String(metaPlain, Charsets.UTF_8)) } finally { metaPlain.fill(0) }
                if (j.getString("id") != id) throw InvalidVaultException("ID tidak cocok")
                j.put("title", title).put("ts", System.currentTimeMillis())
                val metaJson = j.toString().toByteArray(Charsets.UTF_8)
                val newMeta = try { sl.seal("meta", id, metaJson) } finally { metaJson.fill(0) }
                val newSmall = BlobRecord.resealSmall(sl, id, old.info, fields)
                tracked(key, id) { BlobRecord.rewriteHead(rf, newMeta, newSmall) }   // MOLINA-H15-MANIFEST
                val check = BlobRecord.openSmall(rf, id, sl)
                try {
                    if (check.info.size != old.info.size || !java.security.MessageDigest.isEqual(check.info.sha256, old.info.sha256)) {
                        throw InvalidVaultException("Deskriptor blob berubah setelah menulis")
                    }
                } finally {
                    check.fields.forEach { it.fill(0) }
                }
                prefs.noteChange()
                readMeta(key, rf)
            } finally {
                old.fields.forEach { it.fill(0) }
            }
        }
    }

    /**
     * Mendekripsi dan memeriksa segel setiap record: meta, data (v2), atau seluruh chunk dan SHA-256 blob (v3).
     * Mendeteksi kerusakan dan perubahan pada berkas yang ADA; berkas yang hilang seluruhnya tidak terdeteksi.
     * Melempar IllegalStateException bila vault terkunci di tengah jalan. [isCancelled] dicek sebelum tiap record.
     */
    fun audit(isCancelled: () -> Boolean = { false }, onProgress: (Int, Int) -> Unit = { _, _ -> }): AuditResult {
        val files = dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("rec_") && it.name.endsWith(".mvr") }
            .sortedBy { it.name }
        val bad = ArrayList<AuditBad>()
        var ok = 0
        var bytes = 0L
        var checked = 0
        var cancelled = false
        for ((i, f) in files.withIndex()) {
            if (isCancelled()) { cancelled = true; break }
            if (!SessionLock.isUnlocked) throw IllegalStateException("Vault terkunci")
            onProgress(i, files.size)
            val id = f.name.removePrefix("rec_").removeSuffix(".mvr")
            var title: String? = null
            try {
                SessionLock.use { key ->
                    require(validId(id)) { "Nama berkas tidak valid" }
                    title = readMeta(key, f).title
                    if (BlobRecord.isBlobRecord(f)) BlobRecord.verify(f, id, key, sealer(key))
                    else read(id).forEach { it.fill(0) }
                }
                ok++
                bytes += f.length()
            } catch (e: Exception) {
                if (!SessionLock.isUnlocked) throw IllegalStateException("Vault terkunci")
                val why = when (e) {
                    is com.molina.vault.crypto.WrongPasswordOrTamperedException -> "Segel tidak valid: rusak atau dimanipulasi"
                    is InvalidVaultException -> e.message ?: "Struktur record rusak"
                    else -> "Tidak terbaca"
                }
                bad.add(AuditBad(id, title, why))
            }
            checked++
        }
        onProgress(files.size, files.size)
        val mf = if (cancelled) null else try { checkManifest() } catch (e: Exception) { null }   // MOLINA-H15-MANIFEST
        return AuditResult(checked, ok, bad, cancelled, bytes, mf)
    }

    /** Ringkasan pemakaian untuk layar Gudang: jumlah per jenis, ukuran di disk, berkas besar, dan lima record terbesar. */
    fun usage(): UsageReport = SessionLock.use { key ->
        val per = LinkedHashMap<RecordType, Int>()
        var corrupt = 0
        var disk = 0L
        var large = 0
        var largeDisk = 0L
        var n = 0
        val sized = ArrayList<Pair<RecordMeta, Long>>()
        for (f in dir.listFiles().orEmpty()) {
            if (!f.isFile || !f.name.startsWith("rec_") || !f.name.endsWith(".mvr")) continue
            disk += f.length()
            try {
                val m = readMeta(key, f)
                n++
                per[m.type] = (per[m.type] ?: 0) + 1
                sized.add(m to f.length())
                if (BlobRecord.isBlobRecord(f)) { large++; largeDisk += f.length() }
            } catch (e: Exception) {
                corrupt++
            }
        }
        UsageReport(per, n, corrupt, disk, large, largeDisk, sized.sortedByDescending { it.second }.take(5))
    }

    // ---- manifest tersegel (MOLINA-H15-MANIFEST) ----
    // Satu berkas terenkripsi (kunci turunan vaultKey) berisi, per record: id, tag SHA-256 (meta + IV/tag data + panjang), judul.
    // Mendeteksi record dihapus, ditukar dengan versi lama, disambung (meta lama + data baru), atau ditambahkan di luar Vault.
    // Jangkar penghitung disimpan di shared_prefs (di luar folder vault) untuk mendeteksi seluruh folder dikembalikan ke versi lama.

    private val manifestFile = File(dir, MANIFEST_NAME)

    private fun isRecFile(f: File) = f.isFile && f.name.startsWith("rec_") && f.name.endsWith(".mvr")

    private fun recMap(): Map<String, File> {
        val m = LinkedHashMap<String, File>()
        for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
            if (!isRecFile(f)) continue
            val id = f.name.removePrefix("rec_").removeSuffix(".mvr")
            if (validId(id)) m[id] = f
        }
        return m
    }

    private fun mfSeal(key: ByteArray, d: ManifestData): ByteArray {
        val plain = ManifestCodec.encode(d)
        try {
            val sealed = VaultCrypto.encryptRecord(key, plain, aad("manifest", "vault"))
            return ByteBuffer.allocate(5 + sealed.size).put(ManifestCodec.MAGIC).put(ManifestCodec.VERSION).put(sealed).array()
        } finally {
            plain.fill(0)
        }
    }

    /** null bila belum ada. Melempar WrongPasswordOrTamperedException atau InvalidVaultException bila rusak atau dimanipulasi. */
    private fun mfRead(key: ByteArray): ManifestData? {
        if (!manifestFile.exists()) return null
        val raw = manifestFile.readBytes()
        if (raw.size < 5 + MIN_SEALED || raw.size > 32 * 1024 * 1024) throw InvalidVaultException("Manifest rusak")
        for (i in 0 until 4) if (raw[i] != ManifestCodec.MAGIC[i]) throw InvalidVaultException("Manifest rusak")
        if (raw[4] != ManifestCodec.VERSION) throw InvalidVaultException("Versi manifest tidak dikenal")
        val plain = VaultCrypto.decryptRecord(key, raw.copyOfRange(5, raw.size), aad("manifest", "vault"))
        try { return ManifestCodec.decode(plain) } finally { plain.fill(0) }
    }

    private fun mfLoad(key: ByteArray): Pair<ManifestData?, String?> =
        try { mfRead(key) to null }
        catch (e: WrongPasswordOrTamperedException) { null to "Manifest rusak atau dimanipulasi" }
        catch (e: InvalidVaultException) { null to "Manifest rusak atau dimanipulasi" }

    private fun mfWrite(key: ByteArray, d: ManifestData) {
        atomicWrite(manifestFile, mfSeal(key, d))
        prefs.setManifestCounter(d.counter)
    }

    private fun entryOf(key: ByteArray, id: String, f: File): ManifestEntry {
        val title = try { readMeta(key, f).title } catch (e: Exception) { "(tidak terbaca)" }
        return ManifestEntry(id, ManifestCodec.fileTag(f), title)
    }

    private fun buildFromDisk(key: ByteArray, counter: Long): ManifestData {
        val d = ManifestData(counter, LinkedHashMap())
        for ((id, f) in recMap()) {
            try { d.entries[id] = entryOf(key, id, f) } catch (e: Exception) { /* tidak bisa dihitung: akan terlapor tak dikenal */ }
        }
        return d
    }

    private fun mfTouch(key: ByteArray, id: String) {
        val d = mfLoad(key).first ?: return   // hilang atau rusak: jangan dibenarkan diam-diam, peringatan tetap muncul saat pemeriksaan
        d.entries[id] = entryOf(key, id, recFile(id))
        d.counter++
        mfWrite(key, d)
    }

    private fun mfRemove(key: ByteArray, id: String) {
        val d = mfLoad(key).first ?: return
        if (d.entries.remove(id) == null) return
        d.counter++
        mfWrite(key, d)
    }

    /** Jurnal: id dicatat SEBELUM menulis dan dihapus setelah manifest diperbarui; bila proses mati di antaranya, pemeriksaan berikutnya memulihkan id itu saja. */
    private fun <T> tracked(key: ByteArray, id: String, remove: Boolean = false, block: () -> T): T {
        prefs.setPending(id)
        try {
            val r = block()
            try {
                if (remove) mfRemove(key, id) else mfTouch(key, id)
            } catch (e: Exception) {
                /* IO gagal: terlihat sebagai perubahan pada pemeriksaan berikutnya (alarm palsu langka, bukan celah) */
            }
            return r
        } finally {
            prefs.clearPending()
        }
    }

    internal fun resetManifestState() {
        manifestFile.delete()
        File(dir, MANIFEST_NAME + ".tmp").delete()
        prefs.resetManifest()
    }

    /**
     * Membandingkan isi folder dengan manifest. Vault lama tanpa manifest (dan tanpa jangkar) diadopsi sekali sebagai acuan awal.
     * Tidak pernah menulis ulang manifest yang rusak, hilang, atau tampak diputar balik: buktinya dipertahankan sampai [acceptManifest].
     */
    fun checkManifest(): ManifestReport = SessionLock.use { key ->
        val recs = recMap()
        val (data, bad) = mfLoad(key)
        if (bad != null) return@use ManifestReport(ManifestState.ALERT, emptyList(), bad, 0, 0L)
        if (data == null) {
            if (prefs.manifestCounter > 0L) {
                return@use ManifestReport(ManifestState.ALERT, emptyList(), "Manifest hilang atau diganti. Entri yang dihapus diam-diam tidak bisa dipastikan.", 0, 0L)
            }
            val fresh = buildFromDisk(key, 1L)
            mfWrite(key, fresh)
            prefs.clearPending()
            return@use ManifestReport(ManifestState.ADOPTED, emptyList(), null, fresh.entries.size, fresh.counter)
        }
        var problem: String? = null
        val anchor = prefs.manifestCounter
        if (data.counter < anchor) {
            problem = "Folder Vault tampak dikembalikan ke versi lama (penghitung manifest ${data.counter}, tercatat $anchor)"
        }
        val pend = prefs.pendingId
        if (pend != null) {
            if (validId(pend)) {
                val pf = recs[pend]
                if (pf != null) {
                    try { data.entries[pend] = entryOf(key, pend, pf) } catch (e: Exception) { data.entries.remove(pend) }
                } else {
                    data.entries.remove(pend)
                }
                data.counter++
                if (problem == null) mfWrite(key, data)
            }
            prefs.clearPending()
        }
        val issues = ArrayList<ManifestIssue>()
        for ((id, ent) in data.entries) {
            val f = recs[id]
            if (f == null) { issues.add(ManifestIssue(id, ent.title, IssueKind.MISSING)); continue }
            val tag = try { ManifestCodec.fileTag(f) } catch (e: Exception) { null }
            if (tag == null || !java.security.MessageDigest.isEqual(tag, ent.tag)) issues.add(ManifestIssue(id, ent.title, IssueKind.CHANGED))
        }
        for ((id, f) in recs) {
            if (!data.entries.containsKey(id)) {
                issues.add(ManifestIssue(id, try { readMeta(key, f).title } catch (e: Exception) { null }, IssueKind.UNKNOWN))
            }
        }
        val state = if (problem != null || issues.isNotEmpty()) ManifestState.ALERT else ManifestState.OK
        ManifestReport(state, issues.sortedWith(compareBy({ it.kind }, { it.title ?: "" })), problem, data.entries.size, data.counter)
    }

    /** Pengguna menerima keadaan folder sekarang sebagai acuan baru (entri yang hilang tidak kembali). */
    fun acceptManifest() {
        SessionLock.use { key ->
            val prev = mfLoad(key).first
            val counter = maxOf(prev?.counter ?: 0L, prefs.manifestCounter) + 1L
            mfWrite(key, buildFromDisk(key, counter))
            prefs.clearPending()
        }
    }

    /** Rotasi: bangun manifest dengan kunci BARU dari record yang sudah di-staging; record rusak yang dilewati ikut tercatat apa adanya. */
    private fun stageManifest(old: ByteArray, fresh: ByteArray, staging: File): Long {
        val prev = mfLoad(old).first
        val counter = maxOf(prev?.counter ?: 0L, prefs.manifestCounter) + 1L
        val d = ManifestData(counter, LinkedHashMap())
        for ((id, f) in recMap()) {
            val staged = File(staging, f.name)
            try {
                d.entries[id] = if (staged.exists()) entryOf(fresh, id, staged)
                else ManifestEntry(id, ManifestCodec.fileTag(f), "(tidak terbaca)")
            } catch (e: Exception) {
                /* tidak bisa dihitung: akan terlapor tak dikenal */
            }
        }
        writeSynced(File(staging, MANIFEST_NAME), mfSeal(fresh, d))
        return counter
    }

    // ---- rotasi (MOLINA-H8-ROTATE) ----

    private fun stageRotated(old: ByteArray, fresh: ByteArray, f: File, id: String, staging: File) {
        if (BlobRecord.isBlobRecord(f)) {   // MOLINA-H11-GLUE: streaming, memori O(chunk)
            BlobRecord.rotate(f, File(staging, "rec_$id.mvr"), id, old, sealer(old), fresh, sealer(fresh))
            return
        }
        val raw = f.readBytes()
        try {
            val n = sealedMetaSize(raw)
            val meta = VaultCrypto.decryptRecord(old, raw.copyOfRange(HEAD, HEAD + n), aad("meta", id))
            val data = try {
                VaultCrypto.decryptRecord(old, raw.copyOfRange(HEAD + n, raw.size), aad("data", id))
            } catch (e: Exception) {
                meta.fill(0)
                throw e
            }
            try {
                val sealedMeta = VaultCrypto.encryptRecord(fresh, meta, aad("meta", id))
                val sealedData = VaultCrypto.encryptRecord(fresh, data, aad("data", id))
                VaultCrypto.decryptRecord(fresh, sealedMeta, aad("meta", id)).fill(0)
                VaultCrypto.decryptRecord(fresh, sealedData, aad("data", id)).fill(0)
                val out = ByteBuffer.allocate(HEAD + sealedMeta.size + sealedData.size)
                    .put(REC_MAGIC).put(REC_VERSION).putInt(sealedMeta.size)
                    .put(sealedMeta).put(sealedData).array()
                writeSynced(File(staging, "rec_$id.mvr"), out)
            } finally {
                meta.fill(0)
                data.fill(0)
            }
        } finally {
            raw.fill(0)
        }
    }

    /** Idempoten: aman dijalankan ulang setelah proses mati di tengah pemindahan. */
    private fun commitRotation(staging: File) {
        for (f in staging.listFiles().orEmpty()) {
            if (!f.name.startsWith("rec_") || !f.name.endsWith(".mvr")) continue
            if (!f.renameTo(File(dir, f.name))) throw IOException("Gagal memindahkan record rotasi")
        }
        val mf = File(staging, MANIFEST_NAME)   // MOLINA-H15-MANIFEST: manifest tersegel kunci baru pindah bersama record
        if (mf.exists() && !mf.renameTo(manifestFile)) throw IOException("Gagal memindahkan manifest rotasi")
        val header = File(staging, headerFile.name)
        if (header.exists() && !header.renameTo(headerFile)) throw IOException("Gagal memindahkan header rotasi")
        staging.deleteRecursively()
    }

    internal fun recoverRotation() {
        val staging = File(dir, ROTATE_DIR)
        if (!staging.exists()) return
        if (File(staging, ROTATE_COMMIT).exists()) commitRotation(staging) else staging.deleteRecursively()
    }

    private fun writeSynced(target: File, data: ByteArray) {
        FileOutputStream(target).use { it.write(data); it.flush(); it.fd.sync() }
    }

    // ---- internal ----

    internal fun readMeta(key: ByteArray, f: File): RecordMeta {
        val id = f.name.removePrefix("rec_").removeSuffix(".mvr")
        require(validId(id)) { "Nama file tidak valid" }
        val sealed = DataInputStream(FileInputStream(f).buffered()).use { d ->
            val magic = ByteArray(4)
            d.readFully(magic)
            val ver = d.readByte()
            if (!magic.contentEquals(REC_MAGIC) || (ver != REC_VERSION && ver != REC_VERSION_BLOB)) throw InvalidVaultException("Record tidak valid")
            val n = d.readInt()
            if (n < MIN_SEALED || n > 65_536) throw InvalidVaultException("Record rusak")
            ByteArray(n).also { d.readFully(it) }
        }
        val plain = VaultCrypto.decryptRecord(key, sealed, aad("meta", id))
        try {
            val j = JSONObject(String(plain, Charsets.UTF_8))
            if (j.getString("id") != id) throw InvalidVaultException("ID tidak cocok")
            val arr = j.optJSONArray("tags")
            val tags = ArrayList<String>()
            if (arr != null) {
                for (i in 0 until minOf(arr.length(), LabelPolicy.MAX_TAGS)) {
                    val s = arr.optString(i, "")
                    if (s.isNotEmpty()) tags.add(s.take(LabelPolicy.MAX_TAG))
                }
            }
            val folder = j.optString("folder", "").take(LabelPolicy.MAX_FOLDER)
            return RecordMeta(id, RecordType.valueOf(j.getString("type")), j.getString("title"), j.getLong("ts"), folder, tags)
        } finally {
            plain.fill(0)
        }
    }

    internal fun sealer(key: ByteArray): RecordSealer = object : RecordSealer {
        override fun seal(kind: String, id: String, plain: ByteArray): ByteArray =
            VaultCrypto.encryptRecord(key, plain, aad(kind, id))
        override fun open(kind: String, id: String, sealed: ByteArray): ByteArray =
            VaultCrypto.decryptRecord(key, sealed, aad(kind, id))
    }

    /** Sisa ruang kosong di penyimpanan privat Vault (untuk ditampilkan di layar gudang). */
    fun freeBytes(): Long = freeSpace()

    internal fun needSpace(bytes: Long) {
        val free = freeSpace()
        if (free < bytes) throw InsufficientSpaceException(bytes, free)
    }

    private fun rotationBytes(): Long =
        dir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("rec_") && it.name.endsWith(".mvr") }.sumOf { it.length() }

    private fun relabeledMeta(key: ByteArray, sealedMeta: ByteArray, id: String, folder: String, tags: List<String>?): ByteArray {
        val plain = VaultCrypto.decryptRecord(key, sealedMeta, aad("meta", id))
        val j = try { JSONObject(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
        if (j.getString("id") != id) throw InvalidVaultException("ID tidak cocok")
        if (folder.isEmpty()) j.remove("folder") else j.put("folder", folder)
        if (tags != null) {
            val clean = tags.take(LabelPolicy.MAX_TAGS).map { it.take(LabelPolicy.MAX_TAG) }.filter { it.isNotEmpty() }
            if (clean.isEmpty()) j.remove("tags") else j.put("tags", JSONArray(clean))
        }
        val metaJson = j.toString().toByteArray(Charsets.UTF_8)
        try { return VaultCrypto.encryptRecord(key, metaJson, aad("meta", id)) } finally { metaJson.fill(0) }
    }

    private fun sealedMetaSize(raw: ByteArray): Int {
        if (raw.size < HEAD + MIN_SEALED * 2) throw InvalidVaultException("Record rusak")
        val bb = ByteBuffer.wrap(raw)
        val magic = ByteArray(4)
        bb.get(magic)
        if (!magic.contentEquals(REC_MAGIC) || bb.get() != REC_VERSION) throw InvalidVaultException("Record tidak valid")
        val n = bb.int
        if (n < MIN_SEALED || n > 65_536 || HEAD + n + MIN_SEALED > raw.size) throw InvalidVaultException("Record rusak")
        return n
    }

    internal fun validId(id: String): Boolean =
        runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    private fun recFile(id: String) = File(dir, "rec_$id.mvr")

    private fun aad(kind: String, id: String): ByteArray = "$kind|$id".toByteArray(Charsets.UTF_8)

    internal fun atomicWrite(target: File, data: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { it.write(data); it.flush(); it.fd.sync() }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Gagal menyimpan")
        }
    }

    /** MOLINA-H11-GLUE: per blok 64 KB (versi lama mengalokasikan ByteArray sebesar berkas). */
    private fun secureDelete(f: File) = SecureWipe.overwriteAndDelete(f)
}
