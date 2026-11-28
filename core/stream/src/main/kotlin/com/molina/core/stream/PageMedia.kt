package com.molina.core.stream

import com.molina.core.net.HttpHeaderNames
import com.molina.core.net.ShieldHttp

/**
 * [PAGE-MEDIA] Cadangan untuk halaman web yang tidak dikenali yt-dlp (mis. live TV di cubmu): URL manifest (.mpd/.m3u8)
 * sering ada di JSON halaman dengan karakter ter-escape (\u0026, \/), jadi tidak terlihat sebagai media. Di sini halaman
 * dibaca lewat [ShieldHttp] (tiap lompatan diperiksa Shield), manifest pertama yang terjangkau diperiksa enkripsinya:
 * [Result.Drm] (ContentProtection / #EXT-X-KEY non-AES-128; mpv tidak bisa memutar), [Result.Clear] (bisa diputar mpv
 * dengan header Referer), atau [Result.None] (tidak ada petunjuk; penelepon memakai galat lama). Tidak ada upaya
 * mengambil kunci atau melewati proteksi. Murni JVM.
 */
object PageMedia {
    sealed interface Result {
        object Drm : Result
        object None : Result
        class Clear(val url: String, val headers: Map<String, String>) : Result
    }

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private const val PAGE_MAX = 2_000_000
    private const val MANIFEST_MAX = 262_144
    private const val MAX_TRY = 3
    /** Label ShieldHttp sekaligus tag Log Screen (grup stream); satu sumber agar literal tidak ganda. */
    const val LABEL = "page-media"
    private val MANIFEST_URL = Regex("""https?://[^"'\s<>\\]+?\.(?:mpd|m3u8)(?:\?[^"'\s<>\\]*)?""", RegexOption.IGNORE_CASE)
    private val LICENSE_HINT = Regex("""(?:widevine|playready|fairplay)_license_url"?\s*[:=]\s*"?https?:""", RegexOption.IGNORE_CASE)
    private val HLS_METHOD = Regex("""method=([a-z0-9-]+)""")

    /** Buang escape JSON/HTML yang umum pada URL di dalam halaman. */
    fun unescape(s: String): String = s.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")

    /** URL .mpd/.m3u8 (tanpa duplikat, urutan kemunculan) dari isi halaman yang SUDAH di-[unescape]. */
    fun findManifestUrls(html: String): List<String> =
        MANIFEST_URL.findAll(html).map { it.value }.distinct().toList()

    /** True bila manifest terenkripsi dengan cara yang tidak bisa dibuka mpv tanpa CDM (DASH ContentProtection, HLS selain NONE/AES-128). */
    fun isEncrypted(manifest: String): Boolean {
        val m = manifest.lowercase()
        if ("<contentprotection" in m) return true
        for (raw in m.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("#ext-x-key") && !line.startsWith("#ext-x-session-key")) continue
            val method = HLS_METHOD.find(line)?.groupValues?.get(1) ?: continue
            if (method != "none" && method != "aes-128") return true
        }
        return false
    }

    /** True bila halaman (sudah di-[unescape]) memuat URL lisensi DRM yang terisi (bukan null). */
    fun hintsDrm(html: String): Boolean = LICENSE_HINT.containsMatchIn(html)

    private fun origin(url: String): String? = runCatching {
        val u = java.net.URI(url.trim())
        if (u.scheme.isNullOrEmpty() || u.host.isNullOrEmpty()) null else u.scheme + "://" + u.host + "/"
    }.getOrNull()

    /** Memblokir: thread IO. [http] hanya diganti oleh uji. */
    fun probe(
        pageUrl: String,
        timeoutMs: Int = 6_000,
        http: (ShieldHttp.Request) -> ShieldHttp.Result = { ShieldHttp.execute(it) },
    ): Result {
        val page = http(
            ShieldHttp.Request(
                pageUrl, timeoutMs,
                mapOf(HttpHeaderNames.USER_AGENT to UA, HttpHeaderNames.ACCEPT_LANGUAGE to "en-US,en;q=0.9"),
                maxBytes = PAGE_MAX, label = LABEL, gzip = true,
            )
        ) as? ShieldHttp.Result.Ok ?: return Result.None
        val html = unescape(String(page.body, Charsets.UTF_8))
        val hdr = buildMap {
            origin(pageUrl)?.let { put(HttpHeaderNames.REFERER, it) }
            put(HttpHeaderNames.USER_AGENT, UA)
        }
        for (u in findManifestUrls(html).take(MAX_TRY)) {
            val m = http(
                ShieldHttp.Request(u, timeoutMs, hdr, maxBytes = MANIFEST_MAX, label = LABEL, gzip = true)
            ) as? ShieldHttp.Result.Ok ?: continue
            if (m.code !in 200..299) continue
            return if (isEncrypted(String(m.body, Charsets.UTF_8))) Result.Drm else Result.Clear(u, hdr)
        }
        return if (hintsDrm(html)) Result.Drm else Result.None
    }
}
