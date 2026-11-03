package com.molina.core.common

import java.security.SecureRandom
import java.util.Locale
import kotlin.math.ln

/**
 * [ENC-POLICY] Kebijakan kata sandi arsip (ZIP AES-256 dan 7z AES-256). Murni (tanpa Android) supaya bisa diuji JVM.
 *
 * Mengapa perlu: fungsi turunan kunci ditentukan FORMAT dan tidak bisa diganti: 7z memakai SHA-256 x 2^19 putaran,
 * ZIP AES (WinZip AE-x) memakai PBKDF2-HMAC-SHA1 1000 putaran. Keduanya jauh lebih murah ditebak daripada Argon2/scrypt,
 * jadi kekuatan arsip BERGANTUNG pada kekuatan kata sandi. Kata sandi pendek/umum ditolak, yang lemah diberi peringatan,
 * dan generator acak (SecureRandom) disediakan. Perkiraan entropi hanyalah heuristik, bukan jaminan.
 */
object PasswordPolicy {
    const val MIN_LENGTH = 8
    const val RECOMMENDED_LENGTH = 12
    const val GENERATED_LENGTH = 20

    // Tanpa karakter yang mudah tertukar (0/O, 1/l/I) dan tanpa spasi/kutip supaya aman diketik di ponsel.
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789-_.!@#%+="

    enum class Strength(val label: String) { TOO_WEAK("Terlalu lemah"), WEAK("Lemah"), FAIR("Cukup"), STRONG("Kuat") }

    class Verdict(val strength: Strength, val acceptable: Boolean, val message: String)

    private val COMMON = setOf(
        "password", "password1", "password123", "passw0rd", "qwertyui", "qwertyuiop", "qwerty123", "12345678", "123456789",
        "1234567890", "11111111", "00000000", "iloveyou", "admin123", "letmein1", "welcome1", "abc12345",
        "katasandi", "katasandi123", "rahasia123", "indonesia", "indonesia123", "sayangku", "bismillah",
    )

    fun evaluate(pw: String): Verdict {
        if (pw.isEmpty()) return Verdict(Strength.TOO_WEAK, false, "Kata sandi belum diisi.")
        // Sandi dikirim ke 7z lewat stdin satu baris (lihat SevenZipCommand.stdinFor): baris baru tidak boleh ada.
        if (pw.any { it == '\n' || it == '\r' || it == '\u0000' }) return Verdict(Strength.TOO_WEAK, false, "Kata sandi tidak boleh memuat baris baru.")
        if (pw.codePointCount(0, pw.length) < MIN_LENGTH) return Verdict(Strength.TOO_WEAK, false, "Kata sandi minimal $MIN_LENGTH karakter.")
        val lower = pw.lowercase(Locale.ROOT)
        if (lower in COMMON || isSequential(lower)) return Verdict(Strength.TOO_WEAK, false, "Kata sandi terlalu umum atau berpola. Pilih yang lain.")
        val bits = entropyBits(pw)
        return when {
            bits < 36.0 -> Verdict(Strength.TOO_WEAK, false, "Kata sandi terlalu mudah ditebak. Perpanjang atau campur huruf, angka, simbol.")
            bits < 50.0 -> Verdict(Strength.WEAK, true, "Lemah: disarankan minimal $RECOMMENDED_LENGTH karakter dengan campuran huruf, angka, simbol, atau pakai Buat acak.")
            bits < 70.0 -> Verdict(Strength.FAIR, true, "Cukup.")
            else -> Verdict(Strength.STRONG, true, "Kuat.")
        }
    }

    /** Kata sandi acak [length] karakter (12..64) dari SecureRandom, selalu lolos [evaluate]. */
    fun generate(length: Int = GENERATED_LENGTH, rnd: SecureRandom = SecureRandom()): String {
        val n = length.coerceIn(RECOMMENDED_LENGTH, 64)
        while (true) {
            val sb = StringBuilder(n)
            repeat(n) { sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)]) }
            val s = sb.toString()
            if (evaluate(s).strength >= Strength.FAIR) return s
        }
    }

    private fun isSequential(s: String): Boolean =
        s.length >= 2 && ((1 until s.length).all { s[it] - s[it - 1] == 1 } || (1 until s.length).all { s[it] - s[it - 1] == -1 })

    private fun entropyBits(pw: String): Double {
        var pool = 0
        if (pw.any { it in 'a'..'z' }) pool += 26
        if (pw.any { it in 'A'..'Z' }) pool += 26
        if (pw.any { it in '0'..'9' }) pool += 10
        if (pw.any { it.code < 128 && !it.isLetterOrDigit() }) pool += 33
        if (pw.any { it.code >= 128 }) pool += 100
        var eff = 0.0
        var prev = 0
        for ((i, ch) in pw.withIndex()) {
            val step = if (i == 0) 99 else ch.code - prev
            eff += when (step) { 0 -> 0.0; 1, -1 -> 0.25; else -> 1.0 }
            prev = ch.code
        }
        return eff * ln(pool.toDouble()) / ln(2.0)
    }
}
