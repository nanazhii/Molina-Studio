package com.molina.core.common

/**
 * [EXT-CONFIRM] MPVActivity bersifat exported (BROWSABLE untuk http/rtmp/rtsp), jadi halaman web atau aplikasi lain
 * bisa menyuruh Molina memuat URL jaringan. UrlPolicy menahan SSRF/LAN, tetapi tidak mencegah pemutaran otomatis ke
 * server pihak ketiga (bocor IP, konten tak diminta). Intent internal Molina membawa token pintasan; intent tanpa
 * token yang meminta sambungan jaringan harus dikonfirmasi pengguna. Berkas lokal tidak terpengaruh.
 * Default-tolak: skema apa pun selain file/content/memory dianggap jaringan. Murni, tanpa Android.
 */
object ExternalIntentGate {
    /** Nama extra token; sama dengan token pintasan Molina. */
    const val EXTRA = "molina_shortcut_token"

    /** Diisi :app saat start (MolinaApp). Null = tidak ada token yang bisa dipercaya. */
    @Volatile
    var tokenProvider: (() -> String?)? = null

    fun isTrusted(given: String?): Boolean {
        val expected = try { tokenProvider?.invoke() } catch (e: Exception) { null }
        return IntentToken.matches(expected, given)
    }

    private fun isRemoteLine(raw: String): Boolean {
        val t = raw.trim().lowercase()
        if (!t.contains("://")) return false
        return !(t.startsWith("file://") || t.startsWith("content://") || t.startsWith("memory://"))
    }

    /** Daftar memory:// dipecah per baris; entri null diabaikan. */
    fun firstRemote(entries: List<String?>): String? =
        entries.asSequence()
            .flatMap { it?.lineSequence() ?: emptySequence() }
            .map { it.trim() }
            .firstOrNull { isRemoteLine(it) }

    fun requestsNetwork(entries: List<String?>): Boolean = firstRemote(entries) != null

    /** Nama host untuk dialog konfirmasi: tanpa userinfo dan port, tanpa karakter kontrol, maksimal 80 karakter. */
    fun displayHost(url: String?): String {
        val t = url?.trim() ?: return ""
        val i = t.indexOf("://")
        if (i < 0) return ""
        val auth = t.substring(i + 3).takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
        val host = if (auth.startsWith("[")) auth.substringBefore(']') + "]" else auth.substringBefore(':')
        return host.filter { it >= ' ' && it != '\u007f' }.take(80)
    }
}
