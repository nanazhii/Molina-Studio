package com.molina.core.policy

import java.io.File

/**
 * [EXT-INTENT] Kebijakan sumber putar dari intent. MPVActivity bersifat exported, jadi aplikasi lain bisa mengirim
 * extra "filepath" dan playlist memory://. Murni JVM: bagian Android (Uri, UrlPolicy, dataDir) disuntikkan oleh pemanggil.
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
    fun isAllowedCustomPath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith("memory://", ignoreCase = true)) return isAllowedMemoryPlaylist(path)
        return isAllowedEntry(path)
    }

    /** Skema memory sendiri ditolak di dalam isi (cegah rekursi); daftar kosong atau terlalu panjang ditolak. */
    fun isAllowedMemoryPlaylist(uri: String): Boolean {
        val header = "memory://#EXTM3U"
        if (!uri.startsWith(header, ignoreCase = true)) return false
        val entries = uri.substring(header.length)
            .split('\n')
            .map { it.trimEnd('\r') }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        if (entries.isEmpty() || entries.size > MAX_MEMORY_PLAYLIST_ENTRIES) return false
        return entries.all { isAllowedEntry(it) }
    }

    fun isAllowedEntry(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.contains("://")) {
            val scheme = path.substringBefore("://").lowercase()
            if (scheme !in ALLOWED_SCHEMES) return false
            if (scheme == "file") return isOutsidePrivateData(fileUriPath(path))
            return urlAllowed(path)
        }
        return isOutsidePrivateData(path)
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

        /** Hanya skema yang juga diizinkan UrlPolicy. */
        val ALLOWED_SCHEMES: Set<String> = setOf("http", "https", "rtmp", "rtmps", "rtsp", "content", "file")
    }
}
