package com.molina.vault.crypto

import java.security.SecureRandom
import java.util.BitSet

/**
 * Passphrase cadangan acak: 24 simbol dari alfabet 32 karakter (5 bit per simbol) = 120 bit entropi,
 * ditulis dalam 6 kelompok berisi 4 simbol, dipisah "-". Huruf kecil dan tanpa i, l, o, u agar mudah
 * diketik dan ditulis tangan. 256 habis dibagi 32 sehingga `byte and 31` seragam (tanpa bias).
 */
object PassphraseGenerator {
    private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"
    private const val GROUPS = 6
    private const val GROUP_LEN = 4
    private val rng = SecureRandom()

    fun generate(): CharArray {
        val n = GROUPS * GROUP_LEN
        val raw = ByteArray(n).also(rng::nextBytes)
        val out = CharArray(n + GROUPS - 1)
        var o = 0
        for (i in 0 until n) {
            if (i > 0 && i % GROUP_LEN == 0) out[o++] = '-'
            out[o++] = ALPHABET[raw[i].toInt() and 31]
        }
        raw.fill(0)
        return out
    }
}

/**
 * Syarat passphrase cadangan buatan sendiri: >= 24 karakter, >= 10 karakter unik, hanya ASCII cetak.
 * ASCII dipaksa karena PBKDF2 di sebagian versi Android meng-encode karakter non-ASCII berbeda dari JVM,
 * sehingga cadangan bisa gagal dibuka di perangkat lain.
 */
object BackupPolicy {
    fun isAcceptable(p: CharArray): Boolean {
        if (p.size < 24) return false
        val seen = BitSet(128)
        for (ch in p) {
            if (ch.code < 0x20 || ch.code > 0x7E) return false
            seen.set(ch.code)
        }
        return seen.cardinality() >= 10
    }
}
