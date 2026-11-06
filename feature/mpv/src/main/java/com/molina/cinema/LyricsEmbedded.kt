package com.molina.cinema

/**
 * [LYRICS] Lirik tertanam di tag audio (ID3 USLT/SYLT, Vorbis LYRICS, dst.) sebagai cadangan bila tidak ada .lrc.
 * Kotlin murni: pembacaan properti mpv ada di LyricsController, di sini hanya memilih kandidat terbaik.
 */
internal object LyricsEmbedded {
    const val MAX_CHARS = 200_000
    private val TIME_TAG = Regex("""\[\d{1,3}:\d{2}""")

    /** Pilih nilai tag lirik terbaik dari pasangan (kunci, nilai) metadata; utamakan yang bertimestamp. Null bila tak ada. */
    fun pick(entries: List<Pair<String, String>>): String? {
        var best: String? = null
        var bestRank = Int.MAX_VALUE
        for ((k, v) in entries) {
            val key = k.trim().lowercase()
            if (!(key.contains("lyric") || key == "uslt" || key == "sylt")) continue
            val text = v.removePrefix("\uFEFF").trim()
            if (text.isEmpty()) continue
            val rank = if (TIME_TAG.containsMatchIn(text)) 0 else 1
            if (rank < bestRank) { best = text; bestRank = rank }
        }
        return best?.let { if (it.length > MAX_CHARS) it.take(MAX_CHARS) else it }
    }
}
