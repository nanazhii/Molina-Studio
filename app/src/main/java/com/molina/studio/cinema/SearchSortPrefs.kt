package com.molina.studio.cinema

import android.content.Context
import com.molina.core.common.SearchSort

/** [RESULTS-SORT-PREF] Urutan hasil pencarian yang terakhir dipilih (bawaan: Terbaru). SharedPreferences molina_ui. */
internal object SearchSortPrefs {
    private const val KEY = "search_sort"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("molina_ui", Context.MODE_PRIVATE)

    fun load(ctx: Context): SearchSort = try {
        SearchSort.fromName(sp(ctx).getString(KEY, null))
    } catch (e: ClassCastException) {
        SearchSort.NEWEST
    }

    fun save(ctx: Context, v: SearchSort) {
        sp(ctx).edit().putString(KEY, v.name).apply()
    }
}
