package com.molina.core.net

/** Satu saluran dari playlist M3U/IPTV. [group] kosong bila playlist tidak mengelompokkan. */
class IptvChannel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String = "", // [M3U-META] tvg-logo (http/https lolos UrlPolicy) atau kosong
    val headers: Map<String, String> = emptyMap(), // [M3U-META] dari #EXTVLCOPT, sudah disanitasi StreamHeaders
    val drm: Boolean = false, // [DRM-FLAG] KODIPROP license_type/license_key: mpv tidak bisa memutar DRM
    val broken: Boolean = false, // [CHIP-SPLIT] pernah membalas manifest/halaman error (bukan DRM); dicoba ulang setelah TTL
    val unverified: Boolean = false, // [UNVERIFIED-BADGE] feed menandai x-unverified="1": belum terbukti hidup dari runner, mungkin hanya jalan di IP Indonesia
) {
    /** [IPTV-TRUST] dihitung dari URL di perangkat. */
    val trust: IptvTrust.Level by lazy(LazyThreadSafetyMode.NONE) { IptvTrust.of(url) }

    /** [DASH-CHIP] Hanya petunjuk dari ekstensi URL (.mpd); tidak menolak atau melewati channel, sebagian DASH tetap terputar. */
    val isDash: Boolean get() = url.substringBefore('?').substringBefore('#').endsWith(".mpd", ignoreCase = true)
}

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
        var drm = false // [DRM-FLAG]
        var unverified = false // [UNVERIFIED-BADGE]
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
                    unverified = attrs["x-unverified"] == "1" // [UNVERIFIED-BADGE]
                    name = clean(title, MAX_NAME).ifEmpty { clean(attrs["tvg-name"].orEmpty(), MAX_NAME) }
                } else if (line.startsWith("#EXTVLCOPT:", ignoreCase = true)) { // [M3U-META]
                    val kv = line.substring(11)
                    val key = kv.substringBefore('=', "").trim().lowercase()
                    val value = kv.substringAfter('=', "").trim()
                    when (key) {
                        "http-referrer", "http-referer" -> hdr[HttpHeaderNames.REFERER] = value
                        "http-origin" -> hdr[HttpHeaderNames.ORIGIN] = value
                        "http-user-agent" -> hdr[HttpHeaderNames.USER_AGENT] = value // [HDR-NAMES]
                    }
                } else if (line.startsWith("#KODIPROP:", ignoreCase = true)) { // [DRM-FLAG]
                    // [EXTHTTP-NOTE] Hanya license_* yang dibaca; KODIPROP stream_headers sengaja diabaikan (header sama dibawa
                    // #EXTVLCOPT atau #EXTHTTP, lihat cabang [EXTHTTP-JSON]).
                    val k = line.substring(10).substringBefore('=').trim().lowercase()
                    if (k == "inputstream.adaptive.license_type" || k == "inputstream.adaptive.license_key") drm = true
                } else if (line.startsWith("#EXTHTTP:", ignoreCase = true)) { // [EXTHTTP-JSON] cadangan: #EXTVLCOPT selalu menang
                    for ((k, v) in parseFlatJson(line.substring(9))) {
                        val name = when (k.trim().lowercase().replace("-", "").replace("_", "")) { // [EXTHTTP-NORM] tanpa tanda hubung: hindari literal ganda lintas unit (dup_gate)
                            "referer", "referrer" -> HttpHeaderNames.REFERER
                            "origin" -> HttpHeaderNames.ORIGIN
                            "useragent" -> HttpHeaderNames.USER_AGENT
                            else -> continue
                        }
                        if (name !in hdr) hdr[name] = v.trim()
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
                    drm,
                    unverified = unverified, // [UNVERIFIED-BADGE]
                )
            }
            name = ""
            group = ""
            logo = "" // [M3U-META]
            hdr.clear()
            drm = false // [DRM-FLAG]
            unverified = false // [UNVERIFIED-BADGE]
        }
        return M3uResult(out, skipped, truncated, false)
    }

    /**
     * [EXTHTTP-JSON] Objek JSON datar (kunci string, nilai string) tanpa dependensi (core/net JVM murni).
     * Nilai bukan-string dilewati; objek/larik bersarang menghentikan pembacaan; salah format = kosong.
     */
    internal fun parseFlatJson(s: String): Map<String, String> {
        val t = s.trim()
        if (t.length > 4096 || !t.startsWith("{") || !t.endsWith("}")) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val end = t.length - 1
        var i = 1
        fun skipWs() { while (i < end && t[i].isWhitespace()) i++ }
        fun readString(): String? {
            if (i >= end || t[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < end) {
                val c = t[i++]
                if (c == '"') return sb.toString()
                if (c != '\\') { sb.append(c); continue }
                if (i >= end) return null
                when (val e = t[i++]) {
                    '"', '\\', '/' -> sb.append(e)
                    'n' -> sb.append(' ')
                    't' -> sb.append(' ')
                    'u' -> {
                        if (i + 4 > end) return null
                        val h = t.substring(i, i + 4).toIntOrNull(16) ?: return null
                        sb.append(h.toChar())
                        i += 4
                    }
                    else -> return null
                }
            }
            return null
        }
        while (true) {
            skipWs()
            if (i >= end) break
            val k = readString() ?: return emptyMap()
            skipWs()
            if (i >= end || t[i] != ':') return emptyMap()
            i++
            skipWs()
            if (i < end && t[i] == '"') {
                out[k] = readString() ?: return emptyMap()
            } else {
                if (i < end && (t[i] == '{' || t[i] == '[')) return out
                while (i < end && t[i] != ',') i++
            }
            skipWs()
            if (i < end && t[i] == ',') { i++; if (out.size >= 32) break } else break
        }
        return out
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
