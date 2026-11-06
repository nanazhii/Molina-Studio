package com.molina.cinema

/**
 * [LYRICS] Offset lirik per lagu, disimpan sebagai satu string di SharedPreferences.
 * Dibatasi [maxEntries] (yang paling lama tidak diubah dibuang) supaya tidak tumbuh tanpa batas.
 * Offset 0 tidak disimpan. Kotlin murni, diuji di scripts/mpv-tests.
 */
internal class LyricsOffsets(private val maxEntries: Int = DEFAULT_MAX) {
    private val map = LinkedHashMap<String, Long>() // urutan: terlama diubah di depan

    val size: Int get() = map.size

    fun get(key: String): Long = map[key] ?: 0L

    fun put(key: String, ms: Long) {
        map.remove(key)
        val v = ms.coerceIn(-MAX_ABS_MS, MAX_ABS_MS)
        if (v == 0L) return
        map[key] = v
        while (map.size > maxEntries) map.remove(map.keys.first())
    }

    fun serialize(): String = map.entries.joinToString("\n") { "${escape(it.key)}\t${it.value}" }

    companion object {
        const val DEFAULT_MAX = 200
        const val MAX_ABS_MS = 60_000L

        fun parse(raw: String?, maxEntries: Int = DEFAULT_MAX): LyricsOffsets {
            val o = LyricsOffsets(maxEntries)
            if (raw.isNullOrEmpty()) return o
            for (line in raw.lineSequence()) {
                val tab = line.lastIndexOf('\t')
                if (tab <= 0) continue
                val ms = line.substring(tab + 1).toLongOrNull() ?: continue
                o.put(unescape(line.substring(0, tab)), ms)
            }
            return o
        }

        // Kunci berupa path; karakter pemisah (tab, baris baru) dan '%' di-escape agar serialisasi aman.
        private fun escape(s: String) = s.replace("%", "%25").replace("\t", "%09").replace("\n", "%0A").replace("\r", "%0D")
        private fun unescape(s: String) = s.replace("%09", "\t").replace("%0A", "\n").replace("%0D", "\r").replace("%25", "%")
    }
}
