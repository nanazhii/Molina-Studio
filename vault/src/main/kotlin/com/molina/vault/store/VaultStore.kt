package com.molina.vault.store

import android.content.Context
import com.molina.vault.crypto.InvalidVaultException
import com.molina.vault.crypto.PasswordPolicy
import com.molina.vault.crypto.SessionLock
import com.molina.vault.crypto.VaultCrypto
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.UUID

private val REC_MAGIC = byteArrayOf(0x4D, 0x56, 0x52, 0x43) // "MVRC"
private const val REC_VERSION: Byte = 1
private const val HEAD = 4 + 1 + 4
private const val MIN_SEALED = 29 // iv(12) + tag(16) + >=1 byte
private const val MAX_PAYLOAD = 16 * 1024 * 1024

/** Penyimpanan privat: noBackupFilesDir/vault. Satu file per entri, metadata dan isi dienkripsi terpisah. */
class VaultStore(context: Context) {
    private val dir = File(context.applicationContext.noBackupFilesDir, "vault").apply { mkdirs() }
    private val headerFile = File(dir, "vault.mvlt")

    fun exists(): Boolean = headerFile.exists()

    fun create(master: CharArray) {
        check(!exists()) { "Vault sudah ada" }
        require(PasswordPolicy.isAcceptable(master)) { "Master password terlalu lemah" }
        val created = VaultCrypto.create(master)
        atomicWrite(headerFile, created.fileBytes)
        SessionLock.begin(created.vaultKey)
    }

    fun unlock(master: CharArray) {
        val file = headerFile.readBytes()
        val key = VaultCrypto.unlock(master, file)
        if (VaultCrypto.needsUpgrade(file)) atomicWrite(headerFile, VaultCrypto.rewrap(master, key))
        SessionLock.begin(key)
    }

    fun changeMaster(newMaster: CharArray) {
        require(PasswordPolicy.isAcceptable(newMaster)) { "Master password terlalu lemah" }
        SessionLock.use { key -> atomicWrite(headerFile, VaultCrypto.rewrap(newMaster, key)) }
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

    fun put(type: RecordType, title: String, fields: List<ByteArray>, id: String = UUID.randomUUID().toString()): RecordMeta {
        require(validId(id)) { "ID tidak valid" }
        require(title.isNotBlank() && title.length <= 200) { "Judul tidak valid" }
        val payload = PayloadCodec.pack(fields)
        try {
            require(payload.size <= MAX_PAYLOAD) { "Data terlalu besar (maks 16 MB)" }
            return SessionLock.use { key ->
                val now = System.currentTimeMillis()
                val metaJson = JSONObject().put("id", id).put("type", type.name)
                    .put("title", title).put("ts", now).toString().toByteArray(Charsets.UTF_8)
                val sealedMeta = VaultCrypto.encryptRecord(key, metaJson, aad("meta", id))
                metaJson.fill(0)
                val sealedData = VaultCrypto.encryptRecord(key, payload, aad("data", id))
                val out = ByteBuffer.allocate(HEAD + sealedMeta.size + sealedData.size)
                    .put(REC_MAGIC).put(REC_VERSION).putInt(sealedMeta.size)
                    .put(sealedMeta).put(sealedData).array()
                atomicWrite(recFile(id), out)
                RecordMeta(id, type, title, now)
            }
        } finally {
            payload.fill(0)
        }
    }

    /** Pemanggil wajib me-wipe setiap ByteArray hasil setelah selesai. */
    fun read(id: String): List<ByteArray> = SessionLock.use { key ->
        require(validId(id)) { "ID tidak valid" }
        val raw = recFile(id).readBytes()
        val n = sealedMetaSize(raw)
        val plain = VaultCrypto.decryptRecord(key, raw.copyOfRange(HEAD + n, raw.size), aad("data", id))
        try { PayloadCodec.unpack(plain) } finally { plain.fill(0) }
    }

    fun delete(id: String) {
        require(validId(id)) { "ID tidak valid" }
        SessionLock.use { _ -> secureDelete(recFile(id)) }
    }

    // ---- internal ----

    private fun readMeta(key: ByteArray, f: File): RecordMeta {
        val id = f.name.removePrefix("rec_").removeSuffix(".mvr")
        require(validId(id)) { "Nama file tidak valid" }
        val sealed = DataInputStream(FileInputStream(f).buffered()).use { d ->
            val magic = ByteArray(4)
            d.readFully(magic)
            if (!magic.contentEquals(REC_MAGIC) || d.readByte() != REC_VERSION) throw InvalidVaultException("Record tidak valid")
            val n = d.readInt()
            if (n < MIN_SEALED || n > 65_536) throw InvalidVaultException("Record rusak")
            ByteArray(n).also { d.readFully(it) }
        }
        val plain = VaultCrypto.decryptRecord(key, sealed, aad("meta", id))
        try {
            val j = JSONObject(String(plain, Charsets.UTF_8))
            if (j.getString("id") != id) throw InvalidVaultException("ID tidak cocok")
            return RecordMeta(id, RecordType.valueOf(j.getString("type")), j.getString("title"), j.getLong("ts"))
        } finally {
            plain.fill(0)
        }
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

    private fun validId(id: String): Boolean =
        runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    private fun recFile(id: String) = File(dir, "rec_$id.mvr")

    private fun aad(kind: String, id: String): ByteArray = "$kind|$id".toByteArray(Charsets.UTF_8)

    private fun atomicWrite(target: File, data: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { it.write(data); it.flush(); it.fd.sync() }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Gagal menyimpan")
        }
    }

    /** Menimpa nol lalu hapus. Di flash tidak dijamin; perlindungan sebenarnya adalah enkripsi. */
    private fun secureDelete(f: File) {
        if (!f.exists()) return
        try {
            RandomAccessFile(f, "rws").use { raf -> raf.write(ByteArray(f.length().toInt())) }
        } catch (e: Exception) { /* tetap lanjut hapus */ }
        f.delete()
    }
}
