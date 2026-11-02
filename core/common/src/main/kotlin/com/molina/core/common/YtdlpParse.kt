package com.molina.core.common

/**
 * Keluaran `yt-dlp -J` (satu video) -> [ResolvedStream]. Murni, tanpa jaringan.
 * Hanya stream gabungan (video+audio, satu URL); video terpisah menunggu 5d-3.
 */
object YtdlpParse {
    /** Android 8 tanpa AV1: H.264 dulu, maks 1080p (HEVC bisa berujung suara tanpa gambar). */
    const val FORMAT = "best[height<=?1080][vcodec^=avc]/best[height<=?1080][vcodec^=h264]/" +
        "best[height<=?1080][vcodec!*=?av01]/best"

    private val EXPIRE = Regex("[?&]expire=(\\d{9,11})|/expire/(\\d{9,11})")

    const val MAX_ENTRIES = 200

    /** Satu video ([single]) atau playlist `-J --flat-playlist` (daftar channel, dll.). */
    fun resolve(json: String): ResolvedStream? {
        val o = MiniJson.parseObject(json) ?: return null
        if (o["_type"] != "playlist") return single(json)
        val seen = HashSet<String>()
        val list = ArrayList<StreamEntry>()
        for (e in (o["entries"] as? List<*>).orEmpty()) {
            val m = e as? Map<*, *> ?: continue
            val url = ((m["url"] as? String) ?: (m["webpage_url"] as? String))?.trim() ?: continue
            if (!url.startsWith("https://") && !url.startsWith("http://")) continue
            if (!seen.add(url)) continue
            list.add(StreamEntry(url, (m["title"] as? String)?.trim()?.ifEmpty { null }))
            if (list.size >= MAX_ENTRIES) break
        }
        if (list.isEmpty()) return null
        return ResolvedStream(
            url = list[0].url,
            headers = StreamHeaders.sanitize(strings(o["http_headers"])),
            title = list[0].title,
            entries = list,
        )
    }

    fun single(json: String): ResolvedStream? {
        val o = MiniJson.parseObject(json) ?: return null
        if (o["_type"] == "playlist") return null
        val f = if (o["url"] is String) o else combined(o) ?: return null
        val url = (f["url"] as? String)?.trim() ?: return null
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        return ResolvedStream(
            url = url,
            headers = StreamHeaders.sanitize(strings(f["http_headers"] ?: o["http_headers"])),
            title = (o["title"] as? String)?.trim()?.ifEmpty { null },
            durationSec = (o["duration"] as? Number)?.toLong()?.takeIf { it > 0 },
            expiresAtMs = expiresAtMs(url),
        )
    }

    fun expiresAtMs(url: String): Long? {
        val g = EXPIRE.find(url)?.groupValues ?: return null
        return (g[1].ifEmpty { g[2] }).toLongOrNull()?.times(1000)
    }

    private fun combined(o: Map<String, Any?>): Map<String, Any?>? =
        (o["requested_formats"] as? List<*>)?.firstNotNullOfOrNull { e ->
            @Suppress("UNCHECKED_CAST")
            (e as? Map<String, Any?>)?.takeIf { it["vcodec"] != "none" && it["acodec"] != "none" }
        }

    private fun strings(m: Any?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        (m as? Map<*, *>)?.forEach { (k, v) -> if (k is String && v is String) out[k] = v }
        return out
    }
}
