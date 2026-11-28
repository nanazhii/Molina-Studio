package com.molina.vault.crypto

// MOLINA-H11-GLUE
// Pengganti VaultCrypto untuk uji JVM (aslinya memakai Android Keystore). Tanda tangan sama dengan yang dipakai VaultStore.
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object VaultCrypto {
    class Created(val fileBytes: ByteArray, val vaultKey: ByteArray)
    private val rng = SecureRandom()

    fun newVaultKey(): ByteArray = ByteArray(32).also(rng::nextBytes)

    private fun gcm(mode: Int, key: ByteArray, iv: ByteArray, aad: ByteArray): Cipher {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return c
    }

    private fun sub(key: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal("fake-record".toByteArray())
    }

    fun encryptRecord(vaultKey: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(12).also(rng::nextBytes)
        return iv + gcm(Cipher.ENCRYPT_MODE, sub(vaultKey), iv, aad).doFinal(plain)
    }

    fun decryptRecord(vaultKey: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size < 28) throw InvalidVaultException("terlalu pendek")
        try {
            return gcm(Cipher.DECRYPT_MODE, sub(vaultKey), blob.copyOfRange(0, 12), aad).doFinal(blob, 12, blob.size - 12)
        } catch (e: GeneralSecurityException) { throw WrongPasswordOrTamperedException() }
    }

    private fun mk(master: CharArray) = MessageDigest.getInstance("SHA-256").digest(String(master).toByteArray())

    fun rewrap(newMaster: CharArray, vaultKey: ByteArray): ByteArray = "FAKE".toByteArray() + encryptRecord(mk(newMaster), vaultKey, "hdr".toByteArray())
    fun create(master: CharArray): Created { val k = newVaultKey(); return Created(rewrap(master, k), k) }
    fun needsUpgrade(file: ByteArray) = false
    fun unlock(master: CharArray, file: ByteArray): ByteArray =
        try { decryptRecord(mk(master), file.copyOfRange(4, file.size), "hdr".toByteArray()) } catch (e: InvalidVaultException) { throw WrongPasswordOrTamperedException() }
}
