package com.molina.core.net

/**
 * [CH-HEADERS] Header HTTP per channel (dari #EXTVLCOPT) yang dibawa Cinema ke pemutar lewat extra Intent.
 * Playlist memory:// tidak bisa membawa header per entri, jadi pemutar memasang ulang entri bersangkutan.
 * Murni Kotlin. Semua masukan disaring StreamHeaders; pembatas baris aman karena nilai header tanpa karakter kontrol.
 */
object ChannelHeaders {
    const val EXTRA_URLS = "ch_hdr_urls"
    const val EXTRA_VALUES = "ch_hdr_vals"
    const val MAX_CHANNELS = 64

    class Packed(val urls: ArrayList<String>, val values: ArrayList<String>)

    private fun isHttp(u: String) = u.startsWith("https://") || u.startsWith("http://")

    /** [CH-RECENT] Satu channel: header jadi teks "Nama: Nilai" per baris (kosong bila tanpa header). */
    fun pack(headers: Map<String, String>): String =
        StreamHeaders.sanitize(headers).entries.joinToString("\n") { it.key + ": " + it.value }

    fun unpack(value: String): Map<String, String> {
        if (value.isEmpty()) return emptyMap()
        val raw = LinkedHashMap<String, String>()
        for (line in value.split('\n')) {
            val k = line.indexOf(':')
            if (k <= 0) continue
            raw[line.substring(0, k).trim()] = line.substring(k + 1).trim()
        }
        return StreamHeaders.sanitize(raw)
    }

    fun encode(channels: List<IptvChannel>): Packed? {
        val urls = ArrayList<String>()
        val vals = ArrayList<String>()
        for (c in channels) {
            if (urls.size >= MAX_CHANNELS) break
            if (c.drm || !isHttp(c.url) || c.url in urls) continue
            val clean = StreamHeaders.sanitize(c.headers)
            if (clean.isEmpty()) continue
            urls.add(c.url)
            vals.add(clean.entries.joinToString("\n") { it.key + ": " + it.value })
        }
        return if (urls.isEmpty()) null else Packed(urls, vals)
    }

    fun decode(urls: List<String>?, values: List<String>?): Map<String, Map<String, String>> {
        if (urls == null || values == null || urls.size != values.size || urls.size > MAX_CHANNELS) return emptyMap()
        val out = LinkedHashMap<String, Map<String, String>>()
        for (i in urls.indices) {
            val u = urls[i]
            if (!isHttp(u)) continue
            val raw = LinkedHashMap<String, String>()
            for (line in values[i].split('\n')) {
                val k = line.indexOf(':')
                if (k <= 0) continue
                raw[line.substring(0, k).trim()] = line.substring(k + 1).trim()
            }
            val clean = StreamHeaders.sanitize(raw)
            if (clean.isNotEmpty()) out[u] = clean
        }
        return out
    }
}
