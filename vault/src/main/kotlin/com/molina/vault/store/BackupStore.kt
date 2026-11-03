package com.molina.vault.store

import com.molina.vault.crypto.BackupCrypto
import com.molina.vault.crypto.InvalidVaultException
import com.molina.vault.crypto.PasswordPolicy
import com.molina.vault.crypto.SessionLock
import com.molina.vault.crypto.VaultCrypto
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac

/** Ekspor dan pemulihan cadangan .mvault. Format dan alasan desain: docs/BACKUP_FORMAT.md. */
object BackupStore {
    class ExportResult(val count: Int, val skipped: Int)

    /** Sesi harus terbuka. Record yang tidak bisa didekripsi dilewati dan dihitung di [ExportResult.skipped]. */
    fun export(store: VaultStore, out: OutputStream, passphrase: CharArray): ExportResult {
        val vaultKey = SessionLock.use { it.copyOf() }
        try {
            val all = store.dir.listFiles().orEmpty()
                .filter { it.isFile && it.name.startsWith("rec_") && it.name.endsWith(".mvr") }
                .sortedBy { it.name }
            val good = all.filter { f -> try { store.readMeta(vaultKey, f); true } catch (e: Exception) { false } }
            val header = BackupCrypto.newHeader(good.size)
            val wrapped = BackupCrypto.wrapKey(passphrase, header, vaultKey)
            val mac = BackupCrypto.newMac(vaultKey)
            val o = BufferedOutputStream(out, 64 * 1024)
            fun emit(b: ByteArray) { mac.update(b); o.write(b) }
            emit(header.bytes)
            emit(wrapped)
            for (f in good) {
                val id = f.name.removePrefix("rec_").removeSuffix(".mvr")
                val data = f.readBytes()
                try {
                    if (data.size > BackupCrypto.MAX_RECORD_BYTES) throw IOException("Record terlalu besar")
                    emit(id.toByteArray(Charsets.US_ASCII))
                    emit(ByteBuffer.allocate(4).putInt(data.size).array())
                    emit(data)
                } finally { data.fill(0) }
            }
            o.write(mac.doFinal())
            o.flush()
            return ExportResult(good.size, all.size - good.size)
        } finally { vaultKey.fill(0) }
    }

    /**
     * Hanya ke vault KOSONG (tanpa gabung). Urutan aman: staging, verifikasi HMAC, verifikasi tiap record,
     * pindah atomik, header vault.mvlt ditulis TERAKHIR sehingga vault baru "ada" hanya jika lengkap.
     * Master baru membungkus ulang vaultKey dan membuat lapisan Keystore perangkat ini.
     */
    fun restore(store: VaultStore, input: InputStream, passphrase: CharArray, newMaster: CharArray) {
        check(!store.exists()) { "Vault sudah ada; pemulihan hanya ke vault kosong" }
        require(PasswordPolicy.isAcceptable(newMaster)) { "Master password terlalu lemah" }
        val staging = File(store.dir, "restore.tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        var vaultKey: ByteArray? = null
        try {
            val din = DataInputStream(BufferedInputStream(input, 64 * 1024))
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
                val data = ByteArray(len).also { din.readFully(it) }
                mac.update(data)
                try {
                    FileOutputStream(File(staging, "rec_$id.mvr")).use { it.write(data); it.flush(); it.fd.sync() }
                } finally { data.fill(0) }
            }
            val tag = ByteArray(BackupCrypto.MAC_SIZE).also { din.readFully(it) }
            if (!MessageDigest.isEqual(mac.doFinal(), tag)) throw InvalidVaultException("Cadangan rusak atau dimanipulasi")
            if (din.read() != -1) throw InvalidVaultException("Ada data tambahan setelah cadangan")
            val staged = staging.listFiles().orEmpty()
            for (f in staged) store.readMeta(key, f)
            store.dir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("rec_") }.forEach { it.delete() }
            for (f in staged) {
                if (!f.renameTo(File(store.dir, f.name))) throw IOException("Gagal memindahkan record")
            }
            store.atomicWrite(store.headerFile, VaultCrypto.rewrap(newMaster, key))
        } catch (e: EOFException) {
            throw InvalidVaultException("Cadangan terpotong")
        } finally {
            vaultKey?.fill(0)
            staging.deleteRecursively()
        }
    }
}
