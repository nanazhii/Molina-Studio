package com.molina.cinema

/**
 * [SUBTITLE-EXTRACT] Penyusun argv FFmpeg untuk mengekstrak satu stream subtitle. Murni (tanpa Android) supaya
 * bisa diuji di scripts/mpv-tests. Argv dijalankan TANPA shell (ProotScriptExecutor mode guestArgv), jadi spasi,
 * kutip, dan `;` pada nama berkas tetap harfiah.
 */
object SubtitleExtractCommand {
    private const val MAX_STREAM_INDEX = 1023
    private const val MAX_PATH_CHARS = 4000
    private const val MAX_ERROR_CHARS = 160

    /** Nama codec/muxer FFmpeg untuk [target]; null untuk UNKNOWN. */
    fun formatName(target: SubFormat): String? = when (target) {
        SubFormat.SRT -> "srt"
        SubFormat.ASS -> "ass"
        SubFormat.UNKNOWN -> null
    }

    /**
     * `ffmpeg -nostdin -y -i <masuk> -map 0:<indeks> -c:s <fmt> -f <fmt> <keluar>`. Null bila masukan tidak aman:
     * target UNKNOWN, indeks di luar 0..1023, jalur bukan absolut / berisi "..", baris baru, atau NUL, atau masuk == keluar.
     * [guestIn] dan [guestOut] adalah jalur DI DALAM guest (/sdcard/...), bukan jalur Android.
     */
    fun argv(guestIn: String, streamIndex: Int, target: SubFormat, guestOut: String): List<String>? {
        val fmt = formatName(target) ?: return null
        if (streamIndex !in 0..MAX_STREAM_INDEX) return null
        if (!safePath(guestIn) || !safePath(guestOut) || guestIn == guestOut) return null
        return listOf(
            "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-i", guestIn, "-map", "0:$streamIndex", "-c:s", fmt, "-f", fmt, guestOut,
        )
    }

    private fun safePath(path: String): Boolean {
        if (!path.startsWith("/") || path.length > MAX_PATH_CHARS) return false
        if (path.any { it == '\u0000' || it == '\n' || it == '\r' }) return false
        return path.split('/').none { it == ".." }
    }

    /** Baris galat terakhir dari stderr FFmpeg (maks 160 karakter), atau "" bila kosong. */
    fun errorLine(stderr: String): String =
        stderr.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty().take(MAX_ERROR_CHARS)
}
