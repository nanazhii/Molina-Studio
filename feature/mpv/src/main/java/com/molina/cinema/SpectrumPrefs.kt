package com.molina.cinema

import android.content.Context
import androidx.preference.PreferenceManager

/** [SPECTRUM] Setelan hidup/mati visualizer spektrum (bawaan hidup). Disimpan di SharedPreferences bawaan aplikasi. */
internal object SpectrumPrefs {
    const val KEY = "audio_spectrum"
    const val KEY_FREQ_COLORS = "audio_spectrum_freq_colors"

    fun enabled(ctx: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext).getBoolean(KEY, true)

    fun set(ctx: Context, on: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext).edit().putBoolean(KEY, on).apply()
    }

    /** True (bawaan) = warna batang bergradasi menurut frekuensi; false = satu warna aksen. */
    fun frequencyColors(ctx: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext).getBoolean(KEY_FREQ_COLORS, true)

    fun setFrequencyColors(ctx: Context, on: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext).edit().putBoolean(KEY_FREQ_COLORS, on).apply()
    }
}
