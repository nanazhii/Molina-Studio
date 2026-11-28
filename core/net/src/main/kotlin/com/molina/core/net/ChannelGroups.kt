package com.molina.core.net

/**
 * [GROUP-VIEW] group-title M3U per channel yang dibawa Cinema ke pemutar lewat extra Intent, supaya daftar
 * putar bisa diberi header grup (TVRI, VIVA Group, dst.). Murni Kotlin. Nilai disaring dari karakter kontrol.
 */
object ChannelGroups {
    const val EXTRA_URLS = "ch_grp_urls"
    const val EXTRA_VALUES = "ch_grp_vals"
    const val MAX_ENTRIES = 1000
    const val MAX_CHARS = 120_000
    const val MAX_GROUP = 60

    class Packed(val urls: ArrayList<String>, val values: ArrayList<String>)

    private fun cleanGroup(raw: String): String =
        raw.filter { it >= ' ' && it != '\u007f' }.trim().take(MAX_GROUP)

    fun encode(channels: List<IptvChannel>): Packed? {
        val urls = ArrayList<String>()
        val vals = ArrayList<String>()
        val seen = HashSet<String>()
        var chars = 0
        for (c in channels) {
            if (urls.size >= MAX_ENTRIES) break
            if (c.drm) continue
            val g = cleanGroup(c.group)
            if (g.isEmpty() || !seen.add(c.url)) continue
            chars += c.url.length + g.length
            if (chars > MAX_CHARS) break
            urls.add(c.url)
            vals.add(g)
        }
        return if (urls.isEmpty()) null else Packed(urls, vals)
    }

    fun decode(urls: List<String>?, values: List<String>?): Map<String, String> {
        if (urls == null || values == null || urls.size != values.size || urls.size > MAX_ENTRIES) return emptyMap()
        val out = HashMap<String, String>(urls.size)
        for (i in urls.indices) {
            val g = cleanGroup(values[i])
            if (g.isNotEmpty()) out[urls[i]] = g
        }
        return out
    }
}
