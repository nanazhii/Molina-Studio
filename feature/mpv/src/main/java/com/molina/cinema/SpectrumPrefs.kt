package com.molina.cinema

import android.content.Context
import androidx.preference.PreferenceManager

/** [SPECTRUM] Setelan hidup/mati visualizer spektrum (bawaan hidup). Disimpan di SharedPreferences bawaan aplikasi. */
internal object SpectrumPrefs {
    const val KEY = "audio_spectrum"
    const val KEY_FREQ_COLORS = "audio_spectrum_freq_colors" // lama (boolean), hanya untuk migrasi
    const val KEY_COLOR_MODE = "audio_spectrum_color_mode"

    /** [SPECTRUM-HEMAT] Bawaan hidup, kecuali tier efektif Hemat (RAM kecil). Bila kunci sudah ada (pengguna pernah mengatur), pilihannya menang. */
    fun enabled(ctx: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext).getBoolean(KEY, defaultOn(ctx))

    fun defaultOn(ctx: Context): Boolean = effectiveTier(ctx) != PerfTier.LOW

    /** [SPECTRUM-FPS] Jeda minimum antar-gambar (ms). Tier Hemat ~30 fps (hemat CPU/baterai); tier lain 0 = setiap frame. */
    fun frameIntervalMs(ctx: Context): Long = if (effectiveTier(ctx) == PerfTier.LOW) 33L else 0L

    /** Tier efektif tanpa efek samping (PerfTierPolicy.current men-cache dan menerapkan tier; di sini hanya dibaca). */
    private fun effectiveTier(ctx: Context): PerfTier = PerfTierPolicy.effective(ctx) // [T23]

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
