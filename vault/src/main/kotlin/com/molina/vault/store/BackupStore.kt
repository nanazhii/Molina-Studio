package com.molina.vault.store

// MOLINA-H12-BACKUP
import com.molina.vault.crypto.BackupCrypto
import com.molina.vault.crypto.InvalidVaultException
import com.molina.vault.crypto.PasswordPolicy
import com.molina.vault.crypto.SessionLock
import com.molina.vault.crypto.VaultCrypto
import com.molina.vault.crypto.WrongPasswordOrTamperedException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac

/**
 * Ekspor dan pemulihan cadangan .mvault. Format dan alasan desain: docs/BACKUP_FORMAT.md.
 * Semua jalur berjalan streaming (memori O(64 KB)) sehingga record v3 sampai 256 MB aman di Android 8.
 */
object BackupStore {
    private const val BLOCK = 64 * 1024
    private const val SPACE_MARGIN = 8L * 1024 * 1024

    /** [omitted] = record berkas besar yang sengaja tidak disertakan (includeLarge=false). */
    class ExportResult(val count: Int, val skipped: Int, val omitted: Int = 0)

    private fun isRec(f: File) = f.isFile && f.name.startsWith("rec_") && f.name.endsWith(".mvr")

    /** Perkiraan ukuran cadangan dalam byte (tepat bila semua record terbaca); untuk menampilkan ukuran dan cek ruang tujuan. */
    fun estimateBytes(store: VaultStore, includeLarge: Boolean = true): Long {
        var n = (BackupCrypto.HEADER_SIZE + BackupCrypto.WRAPPED_SIZE + BackupCrypto.MAC_SIZE).toLong()
        for (f in store.dir.listFiles().orEmpty()) {
            if (!isRec(f)) continue
            if (!includeLarge && BlobRecord.isBlobRecord(f)) continue
            n += BackupCrypto.ID_SIZE + 4 + f.length()
        }
        return n
    }

    /**
     * Sesi harus terbuka. Record yang tidak bisa dibaca dilewati ([ExportResult.skipped]). [includeLarge]=false membuat
     * cadangan "database saja" tanpa berkas besar. Bila melempar exception, [out] berisi cadangan setengah jadi:
     * pemanggil wajib membuang tujuan (SAF: DocumentsContract.deleteDocument).
     */
    fun export(store: VaultStore, out: OutputStream, passphrase: CharArray, includeLarge: Boolean = true): ExportResult {
        val vaultKey = SessionLock.use { it.copyOf() }
        try {
            val all = store.dir.listFiles().orEmpty().filter { isRec(it) }.sortedBy { it.name }
            var omitted = 0
            val good = ArrayList<File>()
            for (f in all) {
                if (!includeLarge && BlobRecord.isBlobRecord(f)) { omitted++; continue }
                try { store.readMeta(vaultKey, f); good.add(f) } catch (e: Exception) { /* dilewati */ }
            }
            val header = BackupCrypto.newHeader(good.size)
            val wrapped = BackupCrypto.wrapKey(passphrase, header, vaultKey)
            val mac = BackupCrypto.newMac(vaultKey)
            val o = BufferedOutputStream(out, BLOCK)
            fun emit(b: ByteArray) { mac.update(b); o.write(b) }
            emit(header.bytes)
            emit(wrapped)
            val buf = ByteArray(BLOCK)
            try {
                for (f in good) {
                    val id = f.name.removePrefix("rec_").removeSuffix(".mvr")
                    val len = f.length()
                    if (len > BackupCrypto.MAX_RECORD_BYTES) throw IOException("Record terlalu besar")
                    emit(id.toByteArray(Charsets.US_ASCII))
                    emit(ByteBuffer.allocate(4).putInt(len.toInt()).array())
                    FileInputStream(f).use { ins ->
                        var left = len
                        while (left > 0) {
                            val n = ins.read(buf, 0, minOf(left, BLOCK.toLong()).toInt())
                            if (n < 0) throw IOException("Record berubah saat dicadangkan")
                            mac.update(buf, 0, n)
                            o.write(buf, 0, n)
                            left -= n
                        }
                        if (ins.read() != -1) throw IOException("Record berubah saat dicadangkan")
                    }
                }
            } finally { buf.fill(0) }
            o.write(mac.doFinal())
            o.flush()
            return ExportResult(good.size, all.size - omitted - good.size, omitted)
        } finally { vaultKey.fill(0) }
    }

    /**
     * Hanya ke vault KOSONG (tanpa gabung). Urutan aman: staging (streaming, dengan cek ruang per record), verifikasi
     * HMAC, verifikasi tiap record (record v3 diperiksa penuh: setiap chunk), pindah atomik, header vault.mvlt ditulis
     * TERAKHIR sehingga vault baru "ada" hanya jika lengkap. Record v3 yang rusak di dalam cadangan dilewati dan
     * dihitung pada nilai kembalian; bila hanya itu yang ada, pemulihan dibatalkan.
     * Master baru membungkus ulang vaultKey dan membuat lapisan Keystore perangkat ini.
     * @return jumlah record v3 yang dilewati karena rusak (0 = semua pulih)
     */
    fun restore(store: VaultStore, input: InputStream, passphrase: CharArray, newMaster: CharArray): Int {
        check(!store.exists()) { "Vault sudah ada; pemulihan hanya ke vault kosong" }
        require(PasswordPolicy.isAcceptable(newMaster)) { "Master password terlalu lemah" }
        val staging = File(store.dir, "restore.tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        var vaultKey: ByteArray? = null
        val buf = ByteArray(BLOCK)
        try {
            val din = DataInputStream(BufferedInputStream(input, BLOCK))
            val headerBytes = ByteArray(BackupCrypto.HEADER_SIZE).also { din.readFully(it) }
            val header = BackupCrypto.parseHeader(headerBytes)
            val wrapped = ByteArray(BackupCrypto.WRAPPED_SIZE).also { din.readFully(it) }
            val key = BackupCrypto.unwrapKey(passphrase, header, wrapped)
            vaultKey = key
            val mac: Mac = BackupCrypto.newMac(key)
            mac.update(headerBytes)
            mac.update(wrapped)
            val seen = HashSet<String>()
            repeat(header.count) {
                val idBytes = ByteArray(BackupCrypto.ID_SIZE).also { din.readFully(it) }
                mac.update(idBytes)
                val id = String(idBytes, Charsets.US_ASCII)
                if (!store.validId(id) || !seen.add(id)) throw InvalidVaultException("ID record tidak valid atau ganda")
                val lenBytes = ByteArray(4).also { din.readFully(it) }
                mac.update(lenBytes)
                val len = ByteBuffer.wrap(lenBytes).int
                if (len < 0 || len > BackupCrypto.MAX_RECORD_BYTES) throw InvalidVaultException("Ukuran record tidak wajar")
                store.needSpace(len + SPACE_MARGIN)
                FileOutputStream(File(staging, "rec_$id.mvr")).use { fos ->
                    var left = len
                    while (left > 0) {
                        val n = din.read(buf, 0, minOf(left, BLOCK))
                        if (n < 0) throw EOFException()
                        mac.update(buf, 0, n)
                        fos.write(buf, 0, n)
                        left -= n
                    }
                    fos.flush(); fos.fd.sync()
                }
            }
            val tag = ByteArray(BackupCrypto.MAC_SIZE).also { din.readFully(it) }
            if (!MessageDigest.isEqual(mac.doFinal(), tag)) throw InvalidVaultException("Cadangan rusak atau dimanipulasi")
            if (din.read() != -1) throw InvalidVaultException("Ada data tambahan setelah cadangan")
            val usable = ArrayList<File>()
            var skipped = 0
            for (f in staging.listFiles().orEmpty()) {
                store.readMeta(key, f)
                if (BlobRecord.isBlobRecord(f)) {
                    val id = f.name.removePrefix("rec_").removeSuffix(".mvr")
                    try {
                        BlobRecord.verify(f, id, key, store.sealer(key))
                    } catch (e: WrongPasswordOrTamperedException) { skipped++; continue
                    } catch (e: InvalidVaultException) { skipped++; continue }
                }
                usable.add(f)
            }
            if (usable.isEmpty() && skipped > 0) throw InvalidVaultException("Tidak ada record yang bisa dipulihkan")
            store.dir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("rec_") }.forEach { it.delete() }
            for (f in usable) {
                if (!f.renameTo(File(store.dir, f.name))) throw IOException("Gagal memindahkan record")
            }
            store.atomicWrite(store.headerFile, VaultCrypto.rewrap(newMaster, key))
            return skipped
        } catch (e: EOFException) {
            throw InvalidVaultException("Cadangan terpotong")
        } finally {
            buf.fill(0)
            vaultKey?.fill(0)
            staging.deleteRecursively()
        }
    }
}
