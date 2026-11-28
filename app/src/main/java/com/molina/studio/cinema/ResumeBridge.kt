package com.molina.studio.cinema

import android.content.Context

/**
 * Jembatan Settings Molina -> pemutar mpv untuk "ingat posisi putar". Memakai kunci yang sama dengan
 * Setelan pemutar (save_position di SharedPreferences bawaan); MpvPrefs mendengarkan perubahan itu,
 * sehingga sakelar di sini langsung berlaku tanpa memulai ulang.
 */
internal object ResumeBridge {
    private const val KEY = com.molina.cinema.MpvPrefs.KEY_SAVE_POSITION // [PREF-CENTRAL]

    private fun sp(ctx: Context) = com.molina.cinema.MpvPrefs.playerPrefs(ctx) // [SSOT-PREFS]

    fun enabled(ctx: Context): Boolean = try {
        sp(ctx).getBoolean(KEY, false)
    } catch (e: ClassCastException) {
        false
    }

    fun setEnabled(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY, v).apply()
    }
}
