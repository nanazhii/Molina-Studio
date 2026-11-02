package com.molina.core.common

/**
 * Mode "unduh ke cache" untuk situs yang menolak mpv (mis. TikTok: CDN-nya membalas 403 pada
 * mpv/ffmpeg walau yt-dlp sendiri berhasil mengambil berkas yang sama). yt-dlp mengunduh ke
 * [CACHE_GUEST_DIR] di dalam rootfs Debian; :app memetakannya ke berkas host dan memutarnya
 * sebagai berkas lokal. Murni, tanpa Android.
 */
object YtdlpFetch {
    /** Folder cache di guest (host: rootfs/tmp/molina-stream). */
    const val CACHE_GUEST_DIR = "/tmp/molina-stream"

    /** Situs yang diambil lewat yt-dlp sendiri, bukan oleh mpv. */
    private val LOCAL_FETCH_DOMAINS = listOf("tiktok.com")

    private val HOST = Regex("^[A-Za-z][A-Za-z0-9+.-]*://(?:[^/@?#]*@)?([^/:?#]+)")
    private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val SAFE_EXT = Regex("^[A-Za-z0-9]{1,8}$")

    class Downloaded(val guestPath: String, val title: String?, val durationSec: Long?)

    fun hostOf(url: String): String? =
        HOST.find(url.trim())?.groupValues?.get(1)?.lowercase()

    fun needsLocalFetch(pageUrl: String): Boolean {
        val h = hostOf(pageUrl) ?: return false
        return LOCAL_FETCH_DOMAINS.any { h == it || h.endsWith(".$it") }
    }

    /**
     * Unduh satu video lalu cetak JSON-nya (termasuk `requested_downloads[].filepath`).
     * Pesan kemajuan yt-dlp ke stderr karena `-J` membuat mode senyap; `--` mencegah URL dibaca sebagai opsi.
     */
    fun fetchArgs(url: String): List<String> = listOf(
        "--ignore-config", "-J", "--no-simulate", "--no-playlist", "--no-warnings", "--no-progress", "--no-cache-dir",
        "--no-mtime", "--socket-timeout", "20", "--retries", "2",
        "-f", YtdlpParse.FORMAT,
        "-o", "$CACHE_GUEST_DIR/%(id)s.%(ext)s",
        "--", url,
    )

    /** Jalur berkas hasil unduhan; null bila JSON rusak, playlist, atau jalur di luar [CACHE_GUEST_DIR]. */
    fun parseDownloaded(json: String): Downloaded? {
        // Bila ada baris lain di stdout, JSON dari -J selalu satu baris terakhir yang diawali '{'.
        val text = json.lineSequence().lastOrNull { it.trimStart().startsWith("{") } ?: json
        val o = MiniJson.parseObject(text) ?: return null
        if (o["_type"] == "playlist") return null
        val listed = (o["requested_downloads"] as? List<*>)?.firstNotNullOfOrNull { e ->
            val m = e as? Map<*, *> ?: return@firstNotNullOfOrNull null
            (m["filepath"] as? String) ?: (m["_filename"] as? String)
        }
        val path = listed
            ?: (o["filepath"] as? String)
            ?: (o["_filename"] as? String)
            ?: derived(o)
            ?: return null
        if (!safePath(path)) return null
        return Downloaded(
            guestPath = path,
            title = (o["title"] as? String)?.trim()?.ifEmpty { null },
            durationSec = (o["duration"] as? Number)?.toLong()?.takeIf { it > 0 },
        )
    }

    private fun derived(o: Map<String, Any?>): String? {
        val id = o["id"] as? String ?: return null
        val ext = o["ext"] as? String ?: return null
        if (!SAFE_ID.matches(id) || !SAFE_EXT.matches(ext)) return null
        return "$CACHE_GUEST_DIR/$id.$ext"
    }

    private fun safePath(p: String): Boolean =
        p.length <= 300 &&
            p.startsWith("$CACHE_GUEST_DIR/") &&
            !p.contains("..") &&
            p.none { it < ' ' || it == '\u007f' }
}
