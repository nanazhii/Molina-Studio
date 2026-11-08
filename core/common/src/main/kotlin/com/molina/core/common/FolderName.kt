package com.molina.core.common

import java.text.Normalizer

/**
 * Nama kanal -> nama folder. HARUS menghasilkan keluaran yang sama dengan `sanitize_folder_name` di
 * assets/molina/molina_dl.py (mode `--per-channel`), supaya "Rapikan ke folder kanal" memakai nama folder
 * yang sama dengan unduhan baru. Kesamaan dijaga tabel di ChannelTidyTests (dihasilkan dari Python).
 * Murni JVM; tidak melempar.
 */
object FolderName {
    const val FALLBACK = "Tanpa Kanal"
    const val MAX_BYTES = 100

    private val BAD = Regex("""[<>:"/\\|?*\u0000-\u001f\u007f]""")
    // Tanpa (?U): flag itu tidak didukung regex ICU Android (PatternSyntaxException). Kelas eksplisit = \s Unicode Python.
    private val SPACES = Regex("[\\s\\u001c-\\u001f\\u0085\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]+")
    private val RESERVED: Set<String> =
        setOf("con", "prn", "aux", "nul") + (1..9).map { "com$it" } + (1..9).map { "lpt$it" }

    fun sanitize(name: String?, fallback: String = FALLBACK, maxBytes: Int = MAX_BYTES): String {
        var t = Normalizer.normalize(name.orEmpty(), Normalizer.Form.NFC)
        t = buildString {
            t.codePoints().forEach { cp -> if (Character.getType(cp) != Character.FORMAT.toInt()) appendCodePoint(cp) }
        }
        t = BAD.replace(t, "_")
        t = SPACES.replace(t, " ").trim().trimStart('.')
        t = cutBytes(t, maxBytes).trimEnd('.', ' ')
        if (t.isEmpty() || t.all { it == '_' || it == ' ' }) return fallback
        if (t.substringBefore('.').trim().lowercase() in RESERVED) t = "_$t"
        return t
    }

    /** Potong ke paling banyak [max] byte UTF-8 tanpa memotong satu karakter pun. */
    private fun cutBytes(s: String, max: Int): String {
        var used = 0
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (used + n > max) break
            used += n
            sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
