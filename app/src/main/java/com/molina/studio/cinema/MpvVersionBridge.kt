package com.molina.studio.cinema

import android.content.Context

/** Pembaca versi libmpv/FFmpeg yang dicatat pemutar (MpvVersionNote di :feature:mpv); null = belum pernah memutar. */
internal object MpvVersionBridge {
    private const val PREFS = "molina_ui"

    fun mpv(ctx: Context): String? = read(ctx, "mpv_version")

    fun ffmpeg(ctx: Context): String? = read(ctx, "ffmpeg_version")

    /** [INFO-DECODER] Dekoder aktif terakhir (nilai mentah hwdec-current); kunci disalin dari MpvVersionNote. */
    fun decoder(ctx: Context): String? = read(ctx, "hwdec_active")

    private fun read(ctx: Context, key: String): String? =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null)?.takeIf { it.isNotBlank() }
}
