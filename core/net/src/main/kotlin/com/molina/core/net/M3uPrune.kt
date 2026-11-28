package com.molina.core.net

/**
 * [ERR-PRUNE] Membuang entri tertentu (berdasarkan URL) dari teks playlist M3U tanpa menyentuh bagian lain.
 * Satu entri = baris tag entri (#EXTINF, #EXTVLCOPT, #KODIPROP, #EXTHTTP, #EXTGRP) sejak URL sebelumnya,
 * ditambah baris URL-nya. #EXTM3U, komentar lain, baris kosong di luar entri, dan akhir baris (LF/CRLF) dibiarkan.
 * Semua kemunculan URL yang sama dibuang (pembaca hanya menampilkan yang pertama). Murni Kotlin, tanpa Android.
 */
object M3uPrune {
    class Result(val text: String, val removed: Int)

    private val ENTRY_TAGS = listOf("#EXTINF", "#EXTVLCOPT:", "#KODIPROP:", "#EXTHTTP:", "#EXTGRP:")

    private fun norm(raw: String): String =
        raw.trim().removePrefix("\uFEFF").removePrefix("\u00EF\u00BB\u00BF").trim()

    private fun isEntryTag(raw: String): Boolean {
        val t = norm(raw)
        return ENTRY_TAGS.any { t.startsWith(it, ignoreCase = true) }
    }

    fun removeUrls(text: String, urls: Set<String>): Result {
        if (urls.isEmpty()) return Result(text, 0)
        val out = ArrayList<String>()
        val pending = ArrayList<String>() // baris sejak URL terakhir: tag entri, komentar, baris kosong
        var removed = 0
        for (raw in text.split('\n')) {
            val t = norm(raw)
            if (t.isEmpty() || t.startsWith("#")) { pending += raw; continue }
            if (t in urls) {
                // Buang tag entri dan baris kosong milik entri ini; komentar biasa tetap dipertahankan.
                pending.filterTo(out) { !isEntryTag(it) && norm(it).isNotEmpty() }
                removed++
            } else {
                out += pending
                out += raw
            }
            pending.clear()
        }
        out += pending
        return Result(out.joinToString("\n"), removed)
    }
}
