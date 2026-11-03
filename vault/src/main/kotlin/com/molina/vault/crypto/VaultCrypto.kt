package com.molina.vault.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class InvalidVaultException(msg: String) : Exception(msg)
class WrongPasswordOrTamperedException : Exception("Password salah atau vault rusak")

/**
 * Format file: header(25) || AES-GCM[Keystore]( AES-GCM[master]( vaultKey ) )
 * header = "MVLT"(4) | version(1) | iterations(4) | salt(16), dipakai sebagai AAD di kedua lapisan.
 */
object VaultCrypto {
    const val MIN_ITERATIONS = 600_000
    const val TARGET_ITERATIONS = 900_000
    private const val MAX_ITERATIONS = 10_000_000
    private const val VERSION: Byte = 1
    private const val SALT_SIZE = 16
    private const val HEADER_SIZE = 4 + 1 + 4 + SALT_SIZE
    private const val IV_SIZE = 12
    private const val KS_ALIAS = "molina_vault_device_key"
    private val MAGIC = byteArrayOf(0x4D, 0x56, 0x4C, 0x54) // "MVLT"
    private val rng = SecureRandom()

    class Created(val fileBytes: ByteArray, val vaultKey: ByteArray)

    fun create(master: CharArray): Created {
        val vaultKey = ByteArray(32).also(rng::nextBytes)
        return Created(wrap(master, vaultKey), vaultKey)
    }

    /** Dipakai juga untuk ganti master password atau upgrade iterasi (salt baru). */
    fun rewrap(newMaster: CharArray, vaultKey: ByteArray): ByteArray = wrap(newMaster, vaultKey)

    fun needsUpgrade(file: ByteArray): Boolean = parseHeader(file).second < TARGET_ITERATIONS

    fun unlock(master: CharArray, file: ByteArray): ByteArray {
        val (salt, iter) = parseHeader(file)
        val header = file.copyOfRange(0, HEADER_SIZE)
        try {
            val inner = openGcm(deviceKey(), file.copyOfRange(HEADER_SIZE, file.size), header)
            val mk = derive(master, salt, iter)
            try {
                return openGcm(SecretKeySpec(mk, "AES"), inner, header)
            } finally { mk.fill(0) }
        } catch (e: GeneralSecurityException) {
            throw WrongPasswordOrTamperedException()
        }
    }

    // Enkripsi record individual (judul, URL, user, password) dengan vaultKey.
    fun encryptRecord(vaultKey: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray =
        sealSub(vaultKey, plain, aad)

    fun decryptRecord(vaultKey: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray =
        try { openSub(vaultKey, blob, aad) }
        catch (e: GeneralSecurityException) { throw WrongPasswordOrTamperedException() }

    /** HKDF-SHA256 (RFC 5869), salt nol, info = domain|aad -> subkunci 32 byte per (jenis, id). */
    private fun hkdf(ikm: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update("molina-vault/record/v2|".toByteArray(Charsets.UTF_8))
        mac.update(info)
        mac.update(0x01.toByte())
        val okm = mac.doFinal()
        prk.fill(0)
        return okm
    }

    private fun sealSub(vaultKey: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val sk = hkdf(vaultKey, aad)
        try { return sealGcm(SecretKeySpec(sk, "AES"), plain, aad, softwareKey = true) } finally { sk.fill(0) }
    }

    private fun openSub(vaultKey: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray {
        val sk = hkdf(vaultKey, aad)
        try { return openGcm(SecretKeySpec(sk, "AES"), blob, aad) } finally { sk.fill(0) }
    }

    private fun wrap(master: CharArray, vaultKey: ByteArray): ByteArray {
        val salt = ByteArray(SALT_SIZE).also(rng::nextBytes)
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .put(MAGIC).put(VERSION).putInt(TARGET_ITERATIONS).put(salt).array()
        val mk = derive(master, salt, TARGET_ITERATIONS)
        try {
            val inner = sealGcm(SecretKeySpec(mk, "AES"), vaultKey, header, softwareKey = true)
            return header + sealGcm(deviceKey(), inner, header, softwareKey = false)
        } finally { mk.fill(0) }
    }

    private fun parseHeader(file: ByteArray): Pair<ByteArray, Int> {
        if (file.size < HEADER_SIZE + IV_SIZE + 16) throw InvalidVaultException("File terlalu pendek")
        val bb = ByteBuffer.wrap(file, 0, HEADER_SIZE)
        val magic = ByteArray(4).also { bb.get(it) }
        if (!magic.contentEquals(MAGIC)) throw InvalidVaultException("Bukan file vault")
        if (bb.get() != VERSION) throw InvalidVaultException("Versi tidak didukung")
        val iter = bb.int
        // Anti-downgrade (iterasi rendah) dan anti-DoS (iterasi absurd)
        if (iter < MIN_ITERATIONS || iter > MAX_ITERATIONS) throw InvalidVaultException("Parameter KDF tidak aman")
        val salt = ByteArray(SALT_SIZE).also { bb.get(it) }
        return salt to iter
    }

    private fun derive(master: CharArray, salt: ByteArray, iter: Int): ByteArray {
        val spec = PBEKeySpec(master, salt, iter, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally { spec.clearPassword() }
    }

    /**
     * true = kunci Keystore perangkat berada di TEE/StrongBox; false = hanya perangkat lunak;
     * null = tidak bisa ditentukan (kunci belum ada atau API menolak).
     */
    @Suppress("DEPRECATION")
    fun deviceKeyInSecureHardware(): Boolean? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = ks.getKey(KS_ALIAS, null) as? SecretKey
        if (key == null) null
        else {
            val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
            info.isInsideSecureHardware
        }
    } catch (e: Exception) {
        null
    }

    private fun deviceKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KS_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun sealGcm(key: SecretKey, plain: ByteArray, aad: ByteArray, softwareKey: Boolean): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        if (softwareKey) {
            val iv = ByteArray(IV_SIZE).also(rng::nextBytes)
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        } else {
            c.init(Cipher.ENCRYPT_MODE, key) // Keystore membuat IV acak sendiri
        }
        c.updateAAD(aad)
        return c.iv + c.doFinal(plain)
    }

    private fun openGcm(key: SecretKey, blob: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, IV_SIZE)))
        c.updateAAD(aad)
        return c.doFinal(blob, IV_SIZE, blob.size - IV_SIZE)
    }
}
