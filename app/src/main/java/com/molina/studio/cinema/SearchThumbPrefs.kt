package com.molina.studio.cinema

import android.content.Context

/** [RESULTS-THUMB-TOGGLE] Sakelar "Tampilkan thumbnail" (bawaan nyala). SharedPreferences molina_ui. */
internal object SearchThumbPrefs {
    private const val KEY = "show_thumbs"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("molina_ui", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = try {
        sp(ctx).getBoolean(KEY, true)
    } catch (e: ClassCastException) {
        true
    }

    fun setEnabled(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY, v).apply()
    }
}
