package com.molina.studio.cinema

import android.content.Context
import com.molina.cinema.PerfTier
import com.molina.cinema.PerfTierPolicy

/** [RESULTS-THUMB-TOGGLE] Sakelar "Tampilkan thumbnail" (bawaan nyala). SharedPreferences molina_ui. */
internal object SearchThumbPrefs {
    private const val KEY = "show_thumbs"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("molina_ui", Context.MODE_PRIVATE)

    /** [PERF-LITE] Belum dipilih pengguna: nyala, kecuali tier LOW (hemat RAM dan data). */
    fun enabled(ctx: Context): Boolean = try {
        val p = sp(ctx)
        if (p.contains(KEY)) p.getBoolean(KEY, true) else PerfTierPolicy.current(ctx) != PerfTier.LOW
    } catch (e: ClassCastException) {
        true
    }

    fun setEnabled(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY, v).apply()
    }
}
