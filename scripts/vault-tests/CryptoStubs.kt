package com.molina.vault.crypto

// MOLINA-H1
// Stub exception: aslinya ada di VaultCrypto.kt (butuh Android Keystore), jadi tidak bisa dikompilasi di JVM murni.
class InvalidVaultException(msg: String) : Exception(msg)
class WrongPasswordOrTamperedException : Exception("Password salah atau vault rusak")
