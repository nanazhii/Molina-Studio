package com.molina.core.common

/**
 * [WATCH-PROGRESS] Persentase tontonan per video untuk garis progres di hasil pencarian (murni JVM, dapat diuji).
 * Disimpan sebagai teks "kunci<TAB>persen" satu baris per video, terbaru di atas, maks. [MAX].
 */
object WatchProgress {
    const val MAX = 500
    const val MIN_POSITION_MS = 5_000L
    const val DONE_FRACTION = 0.95

    /** 1..100, atau null bila durasi tidak sah / posisi terlalu awal. >= 95% dianggap selesai (100). */
    fun percent(positionMs: Long, durationMs: Long): Int? {
        if (durationMs <= 0L || positionMs < MIN_POSITION_MS) return null
        val f = positionMs.toDouble() / durationMs.toDouble()
        if (f >= DONE_FRACTION) return 100
        return (f * 100).toInt().coerceIn(1, 99)
    }

    /** Taruh [key] di paling atas dengan [pct] (tanpa duplikat), potong sampai [max]. */
    fun push(list: List<Pair<String, Int>>, key: String, pct: Int, max: Int = MAX): List<Pair<String, Int>> =
        (listOf(key to pct.coerceIn(1, 100)) + list.filter { it.first != key }).take(max)

    fun encode(list: List<Pair<String, Int>>): String = list.joinToString("\n") { it.first + "\t" + it.second }

    /** Tahan teks null/rusak: baris tak sah dibuang, duplikat dibuang, persen dijepit 1..100, maks [MAX]. */
    fun decode(text: String?): List<Pair<String, Int>> =
        text.orEmpty().lineSequence().mapNotNull { line ->
            val i = line.indexOf('\t')
            if (i <= 0) {
                null
            } else {
                val k = line.substring(0, i).trim()
                val p = line.substring(i + 1).trim().toIntOrNull()
                if (k.isEmpty() || p == null) null else k to p.coerceIn(1, 100)
            }
        }.distinctBy { it.first }.take(MAX).toList()
}
