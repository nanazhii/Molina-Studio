package com.molina.cinema

import android.content.Context

/**
 * Mencatat versi libmpv dan FFmpeg yang benar-benar termuat (properti mpv-version dan ffmpeg-version),
 * dipanggil sekali setelah mpv diinisialisasi. Disimpan di SharedPreferences molina_ui agar halaman About
 * di :app dapat menampilkannya tanpa menyentuh JNI. Kunci disalin di app/.../cinema/MpvVersionBridge.kt.
 */
internal object MpvVersionNote {
    fun record(ctx: Context) {
        val mpv = read("mpv-version")
        val ffmpeg = read("ffmpeg-version")
        if (mpv == null && ffmpeg == null) return
        ctx.applicationContext.getSharedPreferences("molina_ui", Context.MODE_PRIVATE).edit().apply {
            if (mpv != null) putString("mpv_version", mpv)
            if (ffmpeg != null) putString("ffmpeg_version", ffmpeg)
        }.apply()
    }

    /** [INFO-DECODER] Mencatat dekoder aktif terakhir (properti hwdec-current: no, mediacodec, mediacodec-copy, dst.). */
    fun recordDecoder(ctx: Context, hwdecCurrent: String) {
        val v = hwdecCurrent.trim().take(40)
        if (v.isEmpty()) return
        ctx.applicationContext.getSharedPreferences("molina_ui", Context.MODE_PRIVATE)
            .edit().putString("hwdec_active", v).apply()
    }

    private fun read(property: String): String? =
        runCatching { MPVLib.getPropertyString(property) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.take(80)
}
