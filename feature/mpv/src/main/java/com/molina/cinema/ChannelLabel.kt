package com.molina.cinema

/** [GROUP-VIEW] Nama cadangan baris daftar putar bila channel tanpa judul: "Channel • host", bukan potongan URL. */
internal object ChannelLabel {
    private val STREAM_EXT = setOf("m3u8", "m3u", "mpd", "php", "asp", "aspx", "jsp", "cgi")

    fun fallback(filename: String): String {
        val f = filename.trim()
        val remote = f.startsWith("http://", ignoreCase = true) || f.startsWith("https://", ignoreCase = true) ||
            f.startsWith("rtsp://", ignoreCase = true) || f.startsWith("rtmp://", ignoreCase = true)
        if (!remote) return Utils.fileBasename(f)
        val tail = f.substringBefore('#').substringBefore('?').substringAfterLast('/')
        val ext = tail.substringAfterLast('.', "").lowercase()
        // Berkas video biasa (mp4, mkv, ...) tetap memakai nama berkasnya; stream/manifest memakai host.
        if (ext.isNotEmpty() && ext !in STREAM_EXT) return Utils.fileBasename(f)
        val host = runCatching { java.net.URI(f).host }.getOrNull()?.let { shortHost(it) }
        return if (host.isNullOrBlank()) Utils.fileBasename(f) else "Channel \u2022 $host"
    }

    private val SLD = setOf("co", "go", "or", "ac", "web", "my", "net", "com", "org", "gov", "edu")

    /** op-group1-swiftservehd-1.dens.tv -> dens.tv; x.tvri.go.id -> tvri.go.id; IP dibiarkan utuh. */
    private fun shortHost(host: String): String {
        val h = host.removePrefix("www.")
        if (h.isEmpty() || h.contains(':') || h.all { it.isDigit() || it == '.' }) return h
        val p = h.split('.')
        if (p.size <= 2) return h
        val keep = if (p.last().length == 2 && p[p.size - 2] in SLD) 3 else 2
        return p.takeLast(minOf(keep, p.size)).joinToString(".")
    }
}
