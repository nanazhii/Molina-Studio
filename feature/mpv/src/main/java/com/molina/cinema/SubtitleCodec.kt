package com.molina.cinema

import kotlin.math.roundToLong

/*
 * [SUBTITLE] Codec SRT murni (tanpa Android) untuk Konverter, ekspor hasil OCR, dan simpan hasil sinkronisasi.
 * Pemutaran tidak butuh ini: sinkronisasi langsung memakai properti mpv sub-delay / sub-speed.
 */
internal data class SubCue(val startMs: Long, val endMs: Long, val text: String)

internal object SrtCodec {
    private const val MAX_CUES = 50_000
    private val TIMING = Regex(
        """(\d{1,3}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,3}):(\d{2}):(\d{2})[,.](\d{1,3})"""
    )

    private fun frac(s: String): Long = when (s.length) { 1 -> s.toLong() * 100; 2 -> s.toLong() * 10; else -> s.toLong() }
    private fun ms(h: String, m: String, s: String, f: String) =
        h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1_000 + frac(f)

    fun parse(input: String): List<SubCue> {
        val out = ArrayList<SubCue>()
        val blocks = input.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n{2,}"))
        for (b in blocks) {
            if (out.size >= MAX_CUES) break
            val lines = b.trim().lines()
            val ti = lines.indexOfFirst { TIMING.containsMatchIn(it) }
            if (ti < 0) continue
            val g = TIMING.find(lines[ti])!!.groupValues
            val start = ms(g[1], g[2], g[3], g[4]); val end = ms(g[5], g[6], g[7], g[8])
            val text = lines.drop(ti + 1).joinToString("\n").trim()
            if (text.isNotEmpty()) out.add(SubCue(start, maxOf(end, start), text))
        }
        return out
    }

    private fun fmt(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return "%02d:%02d:%02d,%03d".format(t / 3_600_000, t / 60_000 % 60, t / 1_000 % 60, t % 1_000)
    }

    fun write(cues: List<SubCue>): String = buildString {
        cues.forEachIndexed { i, c ->
            append(i + 1).append('\n')
            append(fmt(c.startMs)).append(" --> ").append(fmt(c.endMs)).append('\n')
            append(c.text).append("\n\n")
        }
    }

    /** Geser semua cue sebesar [deltaMs] (negatif = lebih awal). Hasil tidak pernah di bawah 0. */
    fun shift(cues: List<SubCue>, deltaMs: Long): List<SubCue> =
        cues.map { it.copy(startMs = (it.startMs + deltaMs).coerceAtLeast(0), endMs = (it.endMs + deltaMs).coerceAtLeast(0)) }

    /**
     * Kalikan timestamp berkas dengan [factor] (mis. 23.976/25 untuk mengubah subtitle 25 fps ke 23.976 fps).
     * Perhatian: properti mpv sub-speed adalah KEBALIKANNYA (fps video / fps subtitle); lihat SubSpeeds.
     */
    fun scale(cues: List<SubCue>, factor: Double): List<SubCue> {
        require(factor > 0.0 && factor.isFinite()) { "faktor harus > 0" }
        return cues.map { it.copy(startMs = (it.startMs * factor).roundToLong(), endMs = (it.endMs * factor).roundToLong()) }
    }

    /** Ubah lirik bertimestamp menjadi cue SRT; akhir tiap baris = awal baris berikut (maks [maxMs]). Baris kosong dilewati. */
    fun fromLyrics(lines: List<LyricLine>, maxMs: Long = 8_000L): List<SubCue> =
        lines.mapIndexedNotNull { i, l ->
            if (l.text.isEmpty()) return@mapIndexedNotNull null
            val next = lines.getOrNull(i + 1)?.startMs ?: (l.startMs + maxMs)
            SubCue(l.startMs, minOf(next, l.startMs + maxMs).coerceAtLeast(l.startMs), l.text)
        }
}
