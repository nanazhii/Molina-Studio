package com.molina.cinema

/**
 * [LYRICS-AUTO] Perkiraan sinkronisasi otomatis untuk lirik polos (tanpa timestamp). Kotlin murni (tanpa Android).
 * Bukan forced-alignment: waktu tiap baris dibagi sebanding panjang teksnya di antara intro dan outro perkiraan.
 * Untuk presisi tinggi pakai "Sinkronkan lirik" (tap-sync) yang menyimpan .lrc asli.
 */
internal object LyricsAutoSync {
    private const val MIN_INTRO_MS = 4_000L
    private const val MAX_INTRO_MS = 20_000L
    private const val MAX_OUTRO_MS = 12_000L
    private const val LINE_BASE = 8 // bobot dasar per baris (jeda napas) di luar panjang teks

    fun estimate(plain: List<String>, durationMs: Long): List<LyricLine> {
        val texts = plain.map { it.trim() }.filter { it.isNotEmpty() }
        if (texts.isEmpty() || durationMs < 10_000L) return emptyList()
        val intro = (durationMs * 0.06).toLong().coerceIn(MIN_INTRO_MS, MAX_INTRO_MS)
        val outro = (durationMs * 0.04).toLong().coerceAtMost(MAX_OUTRO_MS)
        val span = durationMs - intro - outro
        if (span <= 0L) return emptyList()
        var total = 0.0
        for (t in texts) total += t.length + LINE_BASE
        var acc = 0.0
        return texts.map { t ->
            val start = intro + (span * acc / total).toLong()
            acc += t.length + LINE_BASE
            LyricLine(start, t)
        }
    }
}
