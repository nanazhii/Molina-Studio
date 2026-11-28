package com.molina.core.net

/**
 * [UNPLAYABLE-CHIP] Daftar kecil URL channel yang terbukti membalas manifest XML (DASH/DRM) alih-alih video.
 * Format simpan: satu baris per URL, "<epoch-ms>\t<url>". Entri kedaluwarsa setelah [TTL_MS] supaya channel
 * yang pulih dicoba lagi. Murni Kotlin.
 */
object UnplayableUrls {
    const val MAX = 200
    const val TTL_MS = 24L * 60 * 60 * 1000
    const val MAX_COUNT = 999 // [ERR-COUNT]

    /** [ERR-COUNT] [ts] = penandaan terakhir; [count] = berapa kali ditandai berturut-turut dalam jendela [TTL_MS]. */
    class Entry(val ts: Long, val count: Int)

    fun isValid(url: String): Boolean {
        val u = url.trim()
        if (!(u.startsWith("https://") || u.startsWith("http://")) || u.length > 2048) return false
        return u.none { it == '<' || it == '>' || it == '"' || it.isWhitespace() || it < ' ' }
    }

    /** Format baris: "<ts>\t<count>\t<url>"; baris lama "<ts>\t<url>" dibaca sebagai count = 1. */
    fun parse(raw: String?, now: Long): LinkedHashMap<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        if (raw.isNullOrEmpty()) return out
        for (line in raw.split('\n')) {
            val parts = line.split('\t', limit = 3)
            if (parts.size < 2) continue
            val ts = parts[0].toLongOrNull() ?: continue
            val count = if (parts.size == 3) (parts[1].toIntOrNull() ?: continue).coerceIn(1, MAX_COUNT) else 1
            val url = if (parts.size == 3) parts[2] else parts[1]
            if (now - ts in 0L..TTL_MS && isValid(url)) out[url] = Entry(ts, count)
        }
        return out
    }

    /** Mengembalikan hitungan baru (ke-N kali), atau 0 bila URL tidak valid. */
    fun add(map: LinkedHashMap<String, Entry>, url: String, now: Long): Int {
        if (!isValid(url)) return 0
        val n = ((map[url]?.count ?: 0) + 1).coerceAtMost(MAX_COUNT)
        map.remove(url)
        map[url] = Entry(now, n)
        while (map.size > MAX) map.remove(map.keys.first())
        return n
    }

    fun serialize(map: Map<String, Entry>): String =
        map.entries.joinToString("\n") { it.value.ts.toString() + "\t" + it.value.count + "\t" + it.key }
}
