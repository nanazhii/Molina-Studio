package com.molina.cinema

import android.content.Context
import androidx.preference.PreferenceManager

/** [SPECTRUM] Setelan hidup/mati visualizer spektrum (bawaan hidup). Disimpan di SharedPreferences bawaan aplikasi. */
internal object SpectrumPrefs {
    const val KEY = "audio_spectrum"

    fun enabled(ctx: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext).getBoolean(KEY, true)

    fun set(ctx: Context, on: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext).edit().putBoolean(KEY, on).apply()
    }
}
