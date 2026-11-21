package com.molina.core.policy

import java.io.File

/**
 * [EXT-INTENT] Kebijakan sumber putar dari intent. MPVActivity bersifat exported, jadi aplikasi lain bisa mengirim
 * extra "filepath" dan playlist memory://. Murni JVM: bagian Android (Uri, UrlPolicy, dataDir) disuntikkan oleh pemanggil.
 *
 * [E-3b-3c] Setiap keputusan lahir dari satu fungsi: [denyReason] (null = diterima). Fungsi isAllowed* hanya membandingkan
 * dengan null, jadi alasan yang dicatat di log tidak bisa menyimpang dari keputusan yang diambil.
 *
 * @param dataDir direktori data privat aplikasi; path di dalamnya (termasuk lewat symlink) tidak boleh dibuka dari luar.
 * @param urlAllowed true bila URL lolos kebijakan jaringan (UrlPolicy).
 * @param fileUriPath path dari URI file:// (null bila tidak terbaca).
 */
class PlaybackSourcePolicy(
    private val dataDir: String,
    private val urlAllowed: (String) -> Boolean,
    private val fileUriPath: (String) -> String?,
) {
    /** Tolak skema tak dikenal dan path di data privat; playlist memory:// hanya bila setiap barisnya lolos. */
    fun isAllowedCustomPath(path: String): Boolean = denyReason(path) == null

    /** Skema memory sendiri ditolak di dalam isi (cegah rekursi); daftar kosong atau terlalu panjang ditolak. */
    fun isAllowedMemoryPlaylist(uri: String): Boolean = memoryDenyReason(uri) == null

    fun isAllowedEntry(path: String): Boolean = entryDenyReason(path) == null

    /**
     * Alasan penolakan [path] berupa kode pendek yang aman untuk log (tidak pernah memuat isi path, URL, atau
     * skema mentah), atau null bila diterima.
     */
    fun denyReason(path: String): String? {
        if (path.isBlank()) return "kosong"
        if (path.startsWith("memory://", ignoreCase = true)) return memoryDenyReason(path)
        return entryDenyReason(path)
    }

    private fun memoryDenyReason(uri: String): String? {
        val header = "memory://#EXTM3U"
        if (!uri.startsWith(header, ignoreCase = true)) return "playlist-bukan-m3u"
        val entries = uri.substring(header.length)
            .split('\n')
            .map { it.trimEnd('\r') }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        if (entries.isEmpty() || entries.size > MAX_MEMORY_PLAYLIST_ENTRIES) return "playlist-kosong-atau-terlalu-panjang"
        entries.firstNotNullOfOrNull { entryDenyReason(it) }?.let { return "playlist-entri:$it" }
        return null
    }

    private fun entryDenyReason(path: String): String? {
        if (path.isBlank()) return "kosong"
        if (path.contains("://")) {
            val scheme = path.substringBefore("://").lowercase()
            if (scheme !in ALLOWED_SCHEMES) return "skema:" + SourceScheme.safeLabel(scheme)
            if (scheme == "file") return if (isOutsidePrivateData(fileUriPath(path))) null else "data-privat"
            return if (urlAllowed(path)) null else "url-diblokir"
        }
        // Skema tanpa "//" (data:..., file:/..., http:host) bukan path lokal: ffmpeg/mpv tetap mengenalinya sebagai protokol.
        // Path absolut diawali "/", jadi tidak terpengaruh.
        if (!path.startsWith("/") && SCHEME_PREFIX.containsMatchIn(path)) {
            return "skema-tanpa-garis:" + SourceScheme.safeLabel(path.substringBefore(':'))
        }
        return if (isOutsidePrivateData(path)) null else "data-privat"
    }

    /** Path di dalam data privat aplikasi (termasuk lewat symlink) tidak boleh dibuka dari luar. */
    fun isOutsidePrivateData(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return try {
            val real = File(path).canonicalPath
            val privateRoot = File(dataDir).canonicalPath
            !(real == privateRoot || real.startsWith("$privateRoot/"))
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        const val MAX_MEMORY_PLAYLIST_ENTRIES = 1000

        private val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:")

        /** Hanya skema yang juga diizinkan UrlPolicy. */
        val ALLOWED_SCHEMES: Set<String> = SourceScheme.NETWORK_SCHEMES + setOf("content", "file")
    }
}
