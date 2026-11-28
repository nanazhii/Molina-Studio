package com.molina.core.stream

import com.molina.core.net.HttpHeaderNames // [HDR-NAMES]
import com.molina.core.net.ShieldHttp

/**
 * [YTDL-PRERESOLVE] Tautan share Facebook (facebook.com/share/r/..., fb.watch) dialihkan ke halaman reel. Tanpa ini yt-dlp
 * memulai dari extractor generic: mengunduh halaman (~477 KB), baru mengikuti pengalihan, lalu mengunduh halaman akhir lagi.
 * Di sini pengalihan diikuti lewat [ShieldHttp] (tiap lompatan diperiksa Shield) dan yt-dlp diberi URL akhir.
 * Fail-open: gagal/ditolak/tidak berubah = null, penelepon memakai URL asli (perilaku lama). Murni JVM.
 */
object YtdlPreResolve {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private val FB_HOST = Regex("""^((www|m|web|mbasic)\.)?facebook\.com$""")
    private val REFRESH_URL = Regex("""url\s*=\s*['"]?\s*([^'";\s]+)""", RegexOption.IGNORE_CASE)

    private fun host(url: String) = runCatching { java.net.URI(url.trim()).host?.lowercase() }.getOrNull().orEmpty()
    private fun path(url: String) = runCatching { java.net.URI(url.trim()).path }.getOrNull().orEmpty()

    /** True untuk tautan share yang pasti dialihkan: host fb.watch, atau facebook.com dengan path berawalan /share/. */
    fun isShareLink(url: String): Boolean {
        val h = host(url)
        return h == "fb.watch" || (FB_HOST.matches(h) && path(url).startsWith("/share/"))
    }

    /** Target dari header Refresh ("0; url=..."), absolut terhadap [base]; null bila tidak ada. */
    fun refreshTarget(header: String?, base: String): String? {
        val raw = header?.let { REFRESH_URL.find(it)?.groupValues?.get(1) } ?: return null
        return runCatching { java.net.URI(base).resolve(raw).toString() }.getOrNull()
    }

    /** Buang query/fragmen (rdid, share_url: token sesi tanpa guna bagi yt-dlp). */
    fun clean(url: String): String = url.substringBefore('#').substringBefore('?')

    /** Hanya http(s) ke keluarga host Facebook, dan bukan lagi tautan share yang sama. */
    fun accept(original: String, candidate: String): Boolean {
        val c = candidate.trim()
        if (!c.startsWith("https://", true) && !c.startsWith("http://", true)) return false
        val h = host(c)
        if (!(h == "fb.watch" || FB_HOST.matches(h))) return false
        return c != original.trim() && !isShareLink(c)
    }

    private fun fetch(url: String, timeoutMs: Int): ShieldHttp.Result = ShieldHttp.execute(
        ShieldHttp.Request(
            url, timeoutMs, mapOf(HttpHeaderNames.USER_AGENT to UA, HttpHeaderNames.ACCEPT_LANGUAGE to "en-US,en;q=0.9"),
            maxBytes = 0, label = "ytdl-pre",
        )
    )

    /** URL akhir untuk yt-dlp, atau null (pakai URL asli). Memblokir: thread IO. [http] hanya diganti oleh uji. */
    fun resolve(url: String, timeoutMs: Int = 4_000, http: (String, Int) -> ShieldHttp.Result = ::fetch): String? {
        if (!isShareLink(url)) return null
        val ok = http(url, timeoutMs) as? ShieldHttp.Result.Ok ?: return null
        if (accept(url, ok.finalUrl)) return clean(ok.finalUrl) // pengalihan HTTP biasa (Location)
        val refresh = ok.headers.entries.firstOrNull { it.key.equals("Refresh", true) }?.value?.firstOrNull()
        val t = refreshTarget(refresh, ok.finalUrl) ?: return null
        return if (accept(url, t)) clean(t) else null
    }
}
