package com.molina.cinema

import android.content.Context
import androidx.preference.PreferenceManager

/** [SPECTRUM] Setelan hidup/mati visualizer spektrum (bawaan hidup). Disimpan di SharedPreferences bawaan aplikasi. */
internal object SpectrumPrefs {
    const val KEY = "audio_spectrum"
    const val KEY_FREQ_COLORS = "audio_spectrum_freq_colors" // lama (boolean), hanya untuk migrasi
    const val KEY_COLOR_MODE = "audio_spectrum_color_mode"

    fun enabled(ctx: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext).getBoolean(KEY, true)

    fun set(ctx: Context, on: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext).edit().putBoolean(KEY, on).apply()
    }

    /** Mode warna batang. Bawaan CINEMA; pemakai lama mewarisi opsi boolean lama (KEY_FREQ_COLORS) bila pernah diatur. */
    fun colorMode(ctx: Context): SpectrumColorMode {
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)
        val legacy = if (sp.contains(KEY_FREQ_COLORS)) sp.getBoolean(KEY_FREQ_COLORS, true) else null
        return SpectrumColorMode.resolve(sp.getString(KEY_COLOR_MODE, null), legacy)
    }

    fun setColorMode(ctx: Context, mode: SpectrumColorMode) {
        PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext).edit().putString(KEY_COLOR_MODE, mode.name).apply()
    }
}
