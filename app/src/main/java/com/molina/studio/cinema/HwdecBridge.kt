package com.molina.studio.cinema

import android.content.Context

/**
 * Jembatan Settings Molina -> pemutar mpv untuk dekoder perangkat keras (MediaCodec). Memakai kunci yang
 * sama dengan Setelan pemutar (hardware_decoding, bawaan true); MpvPrefs membacanya saat pemutar dimulai,
 * jadi perubahan berlaku pada video berikutnya yang dibuka.
 */
internal object HwdecBridge {
    private const val KEY = "hardware_decoding"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(ctx.applicationContext.packageName + "_preferences", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = try {
        sp(ctx).getBoolean(KEY, true)
    } catch (e: ClassCastException) {
        true
    }

    fun setEnabled(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY, v).apply()
    }
}
