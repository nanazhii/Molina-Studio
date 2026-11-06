package com.molina.cinema

/*
 * [LYRICS] Fondasi lirik bertimestamp untuk Molina Cinema (mode audio). Kotlin murni, tanpa Android,
 * jadi bisa diuji lewat scripts/mpv-tests. Sumber posisi: time-pos/full (Double, detik) dari mpv.
 */

/** Satu kata pada LRC "enhanced" (<mm:ss.xx>). */
internal data class LyricWord(val startMs: Long, val text: String)

/** Satu baris lirik. [text] kosong = jeda/instrumental (dipakai untuk mengosongkan tampilan). */
internal data class LyricLine(val startMs: Long, val text: String, val words: List<LyricWord> = emptyList())

internal data class LrcDocument(
    val lines: List<LyricLine>,
    /** Baris tanpa timestamp (berkas lirik biasa). Terisi hanya bila [synced] false. */
    val plainLines: List<String>,
    val title: String?,
    val artist: String?,
    val album: String?,
) {
    val synced: Boolean get() = lines.isNotEmpty()
}

internal object LrcParser {
    private const val MAX_LINES = 5_000          // batas aman untuk berkas aneh/berbahaya
    private const val MAX_CHARS = 2_000_000
    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")
    private val META = Regex("""^\[([A-Za-z#]+):(.*)]$""")

    private fun fracMs(frac: String): Long = when (frac.length) {
        0 -> 0L
        1 -> frac.toLong() * 100
        2 -> frac.toLong() * 10
        else -> frac.take(3).toLong()
    }

    private fun ms(mm: String, ss: String, frac: String): Long =
        mm.toLong() * 60_000 + ss.toLong() * 1_000 + fracMs(frac)

    fun parse(input: String): LrcDocument {
        val raw = input.removePrefix("\uFEFF").let { if (it.length > MAX_CHARS) it.take(MAX_CHARS) else it }
        var title: String? = null; var artist: String? = null; var album: String? = null
        var offsetMs = 0L
        val timed = ArrayList<LyricLine>()
        val plain = ArrayList<String>()

        for (line in raw.lineSequence()) {
            if (timed.size >= MAX_LINES) break
            val t = line.trim()
            if (t.isEmpty()) continue
            val tags = leadingTags(t)
            if (tags.isEmpty()) {
                val meta = META.matchEntire(t)
                if (meta != null) {
                    val v = meta.groupValues[2].trim()
                    when (meta.groupValues[1].lowercase()) {
                        "ti" -> title = v.ifEmpty { null }
                        "ar" -> artist = v.ifEmpty { null }
                        "al" -> album = v.ifEmpty { null }
                        "offset" -> offsetMs = v.toLongOrNull() ?: 0L
                    }
                } else if (!t.startsWith("[")) {
                    plain.add(t)
                }
                continue
            }
            val body = t.substring(tags.last().range.last + 1)
            val words = parseWords(body, ms(tags.first().groupValues[1], tags.first().groupValues[2], tags.first().groupValues[3]))
            val text = body.replace(WORD_TAG, "").trim()
            for (m in tags) {
                val (mm, ss, frac) = m.destructured
                timed.add(LyricLine(ms(mm, ss, frac), text, words))
            }
        }

        // Spesifikasi LRC: offset positif = lirik tampil lebih awal.
        val shifted = timed.map { l ->
            l.copy(
                startMs = (l.startMs - offsetMs).coerceAtLeast(0L),
                words = l.words.map { it.copy(startMs = (it.startMs - offsetMs).coerceAtLeast(0L)) },
            )
        }.sortedBy { it.startMs }
        return LrcDocument(shifted, if (shifted.isEmpty()) plain else emptyList(), title, artist, album)
    }

    // Tag waktu hanya sah bila berurutan dari awal baris ("[00:01.00][00:05.00]teks").
    private fun leadingTags(line: String): List<MatchResult> {
        val out = ArrayList<MatchResult>()
        var pos = 0
        while (pos < line.length) {
            val m = TIME_TAG.matchAt(line, pos) ?: break
            out.add(m)
            pos = m.range.last + 1
        }
        return out
    }

    /**
     * Kata per timestamp. Teks SEBELUM tag kata pertama menjadi kata di [lineStartMs]; spasi yang berdiri
     * sendiri digabung ke kata sebelumnya supaya jarak antar kata tidak hilang.
     */
    private fun parseWords(body: String, lineStartMs: Long): List<LyricWord> {
        val marks = WORD_TAG.findAll(body).toList()
        if (marks.isEmpty()) return emptyList()
        val out = ArrayList<LyricWord>(marks.size + 1)
        val prefix = body.substring(0, marks.first().range.first)
        if (prefix.isNotBlank()) out.add(LyricWord(lineStartMs, prefix))
        for ((i, m) in marks.withIndex()) {
            val end = if (i + 1 < marks.size) marks[i + 1].range.first else body.length
            val w = body.substring(m.range.last + 1, end)
            if (w.isBlank()) {
                if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(text = out.last().text + w)
                continue
            }
            val (mm, ss, frac) = m.destructured
            out.add(LyricWord(ms(mm, ss, frac), w))
        }
        return out
    }
}

/** Indeks baris aktif pada [positionMs] (binary search), atau -1 bila belum ada baris yang dimulai. */
internal fun List<LyricLine>.activeIndex(positionMs: Long): Int {
    var lo = 0; var hi = lastIndex; var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].startMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
    }
    return ans
}

/** Indeks kata aktif di [line] pada [positionMs], atau -1 bila baris tidak punya timestamp per kata. */
internal fun activeWordIndex(line: LyricLine, positionMs: Long): Int {
    var ans = -1
    for ((i, w) in line.words.withIndex()) if (w.startMs <= positionMs) ans = i else break
    return ans
}

/**
 * Pelacak baris aktif. Dipanggil berkala (polling ~150 ms dari time-pos/full), tetapi hanya mengembalikan
 * nilai saat indeks BERUBAH, supaya UI tidak digambar ulang tanpa perlu.
 */
internal class LyricsTracker(private val lines: List<LyricLine>) {
    private var last = Int.MIN_VALUE

    /**
     * [userOffsetMs] positif memajukan lirik (muncul lebih awal), konsisten dengan tag [offset:] LRC.
     * Mengembalikan indeks baru bila berubah, atau null bila sama dengan panggilan sebelumnya.
     */
    fun update(positionMs: Long, userOffsetMs: Long = 0L): Int? {
        val idx = lines.activeIndex(positionMs + userOffsetMs)
        if (idx == last) return null
        last = idx
        return idx
    }

    /** Indeks baris aktif terakhir yang dilaporkan, atau -1 bila belum ada. */
    val current: Int get() = if (last == Int.MIN_VALUE) -1 else last

    /** Panggil setelah seek/ganti lagu agar emisi berikutnya selalu terkirim. */
    fun reset() { last = Int.MIN_VALUE }
}

/** Potongan teks satu baris untuk sorotan karaoke: [sung] true = sudah dinyanyikan (diwarnai aksen). */
internal data class LyricSegment(val text: String, val sung: Boolean)

/**
 * Segmen untuk digambar. Tanpa timestamp per kata: satu segmen utuh. Dengan kata: kata 0..[activeWord]
 * dianggap sudah dinyanyikan (isi dimulai saat kata mulai); [activeWord] -1 = belum ada yang dimulai.
 */
internal fun LyricLine.segments(activeWord: Int): List<LyricSegment> =
    if (words.isEmpty()) listOf(LyricSegment(text, true))
    else words.mapIndexed { i, w -> LyricSegment(w.text, i <= activeWord) }
