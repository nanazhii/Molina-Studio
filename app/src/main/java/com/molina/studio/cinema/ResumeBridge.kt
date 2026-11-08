package com.molina.studio.cinema

import android.content.Context

/**
 * Jembatan Settings Molina -> pemutar mpv untuk "ingat posisi putar". Memakai kunci yang sama dengan
 * Setelan pemutar (save_position di SharedPreferences bawaan); MpvPrefs mendengarkan perubahan itu,
 * sehingga sakelar di sini langsung berlaku tanpa memulai ulang.
 */
internal object ResumeBridge {
    private const val KEY = "save_position"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(ctx.applicationContext.packageName + "_preferences", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = try {
        sp(ctx).getBoolean(KEY, false)
    } catch (e: ClassCastException) {
        false
    }

    fun setEnabled(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY, v).apply()
    }
}
