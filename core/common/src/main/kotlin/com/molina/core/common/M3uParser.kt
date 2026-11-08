package com.molina.core.common

/** Satu saluran dari playlist M3U/IPTV. [group] kosong bila playlist tidak mengelompokkan. */
class IptvChannel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String = "", // [M3U-META] tvg-logo (http/https lolos UrlPolicy) atau kosong
    val headers: Map<String, String> = emptyMap(), // [M3U-META] dari #EXTVLCOPT, sudah disanitasi StreamHeaders
)

/**
 * Hasil membaca playlist. [skipped] = entri dibuang (skema bukan stream atau ditolak [UrlPolicy]);
 * [truncated] = daftar dipotong di [M3uParser.MAX_CHANNELS]; [isHlsStream] = berkas ternyata playlist
 * HLS satu stream (bukan daftar saluran), jadi [channels] sengaja kosong.
 */
class M3uResult(
    val channels: List<IptvChannel>,
    val skipped: Int,
    val truncated: Boolean,
    val isHlsStream: Boolean,
)

/**
 * Pembaca playlist M3U/M3U8 untuk daftar saluran IPTV. Murni Kotlin (tanpa Android) agar bisa diuji
 * di JVM. Teks playlist dianggap tidak tepercaya: tiap URL wajib stream jarak jauh dan lolos
 * [UrlPolicy]; nama dibersihkan dari karakter kontrol dan pengarah-teks (cegah penyamaran).
 */
object M3uParser {
    const val MAX_CHANNELS = 5000
    const val MAX_NAME = 120
    const val MAX_GROUP = 60

    private val STREAM_SCHEMES = setOf("http", "https", "rtsp", "rtmp", "rtmps")
    private val ATTR = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")
    private val SPACES = Regex("\\s+")

    fun parse(text: String): M3uResult {
        val src = text.removePrefix("\uFEFF")
        // Playlist HLS (media/master) memuat tag #EXT-X-*: itu satu stream, bukan daftar saluran.
        if (src.contains("#EXT-X-TARGETDURATION") || src.contains("#EXT-X-STREAM-INF")) {
            return M3uResult(emptyList(), 0, false, true)
        }
        val out = ArrayList<IptvChannel>()
        val seen = HashSet<String>()
        var skipped = 0
        var truncated = false
        var name = ""
        var group = ""
        var extGroup = ""
        var logo = "" // [M3U-META]
        val hdr = LinkedHashMap<String, String>() // [M3U-META]

        for (raw in src.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                if (line.startsWith("#EXTINF", ignoreCase = true)) {
                    val body = line.substringAfter(':', "")
                    // Koma pemisah judul adalah koma pertama di luar tanda kutip atribut.
                    var inQuote = false
                    var comma = -1
                    for ((i, c) in body.withIndex()) {
                        if (c == '"') inQuote = !inQuote
                        else if (c == ',' && !inQuote) { comma = i; break }
                    }
                    val head = if (comma >= 0) body.substring(0, comma) else body
                    val title = if (comma >= 0) body.substring(comma + 1) else ""
                    val attrs = ATTR.findAll(head).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    group = clean(attrs["group-title"].orEmpty(), MAX_GROUP)
                    logo = safeLogo(attrs["tvg-logo"].orEmpty()) // [M3U-META]
                    name = clean(title, MAX_NAME).ifEmpty { clean(attrs["tvg-name"].orEmpty(), MAX_NAME) }
                } else if (line.startsWith("#EXTVLCOPT:", ignoreCase = true)) { // [M3U-META]
                    val kv = line.substring(11)
                    val key = kv.substringBefore('=', "").trim().lowercase()
                    val value = kv.substringAfter('=', "").trim()
                    when (key) {
                        "http-referrer", "http-referer" -> hdr["Referer"] = value
                        "http-origin" -> hdr["Origin"] = value
                        "http-user-agent" -> hdr["User-Agent"] = value
                    }
                } else if (line.startsWith("#EXTGRP:", ignoreCase = true)) {
                    extGroup = clean(line.substring(8), MAX_GROUP)
                }
                continue
            }

            val scheme = UrlPolicy.scheme(line)
            if (scheme == null || scheme !in STREAM_SCHEMES || UrlPolicy.check(line) !is UrlPolicy.Verdict.Allowed) {
                skipped++
            } else if (seen.add(line)) {
                if (out.size >= MAX_CHANNELS) { truncated = true; break }
                out += IptvChannel(
                    name.ifEmpty { "Saluran ${out.size + 1}" },
                    line,
                    group.ifEmpty { extGroup },
                    logo,
                    StreamHeaders.sanitize(hdr),
                )
            }
            name = ""
            group = ""
            logo = "" // [M3U-META]
            hdr.clear()
        }
        return M3uResult(out, skipped, truncated, false)
    }

    /** [M3U-META] Logo hanya http/https, lolos UrlPolicy, panjang wajar; selain itu dibuang (kosong). */
    internal fun safeLogo(u: String): String {
        val t = u.trim()
        if (t.isEmpty() || t.length > 512) return ""
        val sc = UrlPolicy.scheme(t)
        return if ((sc == "http" || sc == "https") && UrlPolicy.check(t) is UrlPolicy.Verdict.Allowed) t else ""
    }

    /** Buang karakter kontrol, zero-width, dan pengarah-teks (bidi); rapatkan spasi; batasi panjang. */
    internal fun clean(s: String, max: Int): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val bad = c.isISOControl() || c in '\u200B'..'\u200F' || c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069'
            sb.append(if (bad) ' ' else c)
        }
        return sb.toString().replace(SPACES, " ").trim().take(max)
    }
}
