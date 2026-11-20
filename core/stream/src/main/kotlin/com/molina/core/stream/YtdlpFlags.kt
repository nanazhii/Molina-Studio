package com.molina.core.stream

/** Satu sumber flag CLI yt-dlp yang dipakai bersama (stream: YtdlpFetch; download: YtdlpCommand). */
object YtdlpFlags {
    const val IGNORE_CONFIG = "--ignore-config"
    const val NO_PLAYLIST = "--no-playlist"
    const val NO_WARNINGS = "--no-warnings"
    const val NO_PROGRESS = "--no-progress"
    const val NO_CACHE_DIR = "--no-cache-dir"
    const val SOCKET_TIMEOUT = "--socket-timeout"
    const val RETRIES = "--retries"
}
