package com.molina.vault.crypto

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Format cadangan .mvault (tanpa lapisan Keystore, bisa dibuka di perangkat lain):
 *   header(29)  = "MVBK"(4) | versi(1) | iterasi(4) | salt(16) | jumlahRecord(4)
 *   wrapped(60) = iv(12) || AES-256-GCM[kunci dari passphrase]( vaultKey ), AAD = header
 *   record*     = id(36, ASCII) | panjang(4) | isi rec_<id>.mvr apa adanya
 *   trailer(32) = HMAC-SHA256 atas semua byte sebelumnya (kunci diturunkan dari vaultKey)
 * Murni JVM: tidak memakai Keystore sehingga bisa dites unit.
 */
object BackupCrypto {
    const val MIN_ITERATIONS = 600_000
    const val TARGET_ITERATIONS = 900_000
    const val MAX_ITERATIONS = 10_000_000
    const val MAX_RECORDS = 100_000
    const val MAX_RECORD_BYTES = 17 * 1024 * 1024
    const val ID_SIZE = 36
    const val MAC_SIZE = 32
    const val HEADER_SIZE = 4 + 1 + 4 + 16 + 4
    const val WRAPPED_SIZE = 12 + 32 + 16
    private const val VERSION: Byte = 1
    private val MAGIC = byteArrayOf(0x4D, 0x56, 0x42, 0x4B) // "MVBK"
    private val rng = SecureRandom()

    class Header(val bytes: ByteArray, val iterations: Int, val salt: ByteArray, val count: Int)

    fun newHeader(count: Int): Header {
        require(count in 0..MAX_RECORDS) { "Jumlah record tidak valid" }
        val salt = ByteArray(16).also(rng::nextBytes)
        val bytes = ByteBuffer.allocate(HEADER_SIZE)
            .put(MAGIC).put(VERSION).putInt(TARGET_ITERATIONS).put(salt).putInt(count).array()
        return Header(bytes, TARGET_ITERATIONS, salt, count)
    }

    /** Menolak iterasi rendah (anti-downgrade) dan absurd (anti-DoS), serta jumlah record tidak wajar. */
    fun parseHeader(bytes: ByteArray): Header {
        if (bytes.size != HEADER_SIZE) throw InvalidVaultException("Header cadangan tidak valid")
        val bb = ByteBuffer.wrap(bytes)
        val magic = ByteArray(4).also { bb.get(it) }
        if (!magic.contentEquals(MAGIC)) throw InvalidVaultException("Bukan berkas cadangan Molina Vault")
        if (bb.get() != VERSION) throw InvalidVaultException("Versi cadangan tidak didukung")
        val iter = bb.int
        if (iter < MIN_ITERATIONS || iter > MAX_ITERATIONS) throw InvalidVaultException("Parameter KDF cadangan tidak aman")
        val salt = ByteArray(16).also { bb.get(it) }
        val count = bb.int
        if (count < 0 || count > MAX_RECORDS) throw InvalidVaultException("Jumlah record tidak valid")
        return Header(bytes.copyOf(), iter, salt, count)
    }

    fun wrapKey(passphrase: CharArray, header: Header, vaultKey: ByteArray): ByteArray {
        require(vaultKey.size == 32) { "vaultKey harus 32 byte" }
        val k = derive(passphrase, header.salt, header.iterations)
        try {
            val iv = ByteArray(12).also(rng::nextBytes)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv))
            c.updateAAD(header.bytes)
            return iv + c.doFinal(vaultKey)
        } finally { k.fill(0) }
    }

    fun unwrapKey(passphrase: CharArray, header: Header, wrapped: ByteArray): ByteArray {
        if (wrapped.size != WRAPPED_SIZE) throw InvalidVaultException("Cadangan rusak")
        val k = derive(passphrase, header.salt, header.iterations)
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, wrapped.copyOfRange(0, 12)))
            c.updateAAD(header.bytes)
            return c.doFinal(wrapped, 12, wrapped.size - 12)
        } catch (e: GeneralSecurityException) {
            throw WrongPasswordOrTamperedException()
        } finally { k.fill(0) }
    }

    /** HMAC-SHA256 dengan kunci turunan domain "backup-mac", terpisah dari subkunci record. */
    fun newMac(vaultKey: ByteArray): Mac {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(vaultKey, "HmacSHA256"))
        val macKey = m.doFinal("molina-vault/backup-mac/v1".toByteArray(Charsets.UTF_8))
        try { m.init(SecretKeySpec(macKey, "HmacSHA256")) } finally { macKey.fill(0) }
        return m
    }

    private fun derive(pass: CharArray, salt: ByteArray, iter: Int): ByteArray {
        val spec = PBEKeySpec(pass, salt, iter, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally { spec.clearPassword() }
    }
}
