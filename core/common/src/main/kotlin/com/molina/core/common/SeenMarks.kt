package com.molina.core.common

/** Kunci stabil video untuk penanda "diunduh" dan "pernah diputar" (murni JVM, dapat diuji). */
object VideoKey {
    private val YT = Regex(
        "^https?://(?:(?:www\\.|m\\.|music\\.)?youtube\\.com/(?:watch\\?(?:[^#]*&)?v=|shorts/|live/|embed/)|youtu\\.be/)" +
            "([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])",
        RegexOption.IGNORE_CASE,
    )

    /** "yt:<id>" untuk URL video YouTube (watch?v=, youtu.be/, shorts/, live/, embed/); null untuk selain itu. */
    fun of(url: String): String? = YT.find(url.trim())?.let { "yt:" + it.groupValues[1] }
}

/** Daftar kunci terbaru-di-atas dengan batas jumlah; disimpan sebagai teks satu kunci per baris. */
object SeenMarks {
    const val MAX = 500

    /** Taruh [key] di paling atas (tanpa duplikat), potong sampai [max]. */
    fun push(list: List<String>, key: String, max: Int = MAX): List<String> =
        (listOf(key) + list.filter { it != key }).take(max)

    fun encode(list: List<String>): String = list.joinToString("\n")

    /** Tahan teks null/rusak: baris kosong dibuang, duplikat dibuang, urutan dipertahankan, maks [MAX]. */
    fun decode(text: String?): List<String> =
        text.orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX).toList()
}
