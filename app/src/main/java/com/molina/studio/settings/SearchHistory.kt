package com.molina.studio.settings

import android.content.Context

/**
 * [INFO-HISTORY] Lima kata cari terakhir (terbaru di depan, tanpa duplikat), disimpan di SharedPreferences privat aplikasi.
 * Dicatat hanya saat pengguna membuka sebuah hasil, jadi ketikan setengah jalan tidak ikut tersimpan.
 */
internal object SearchHistory {
    private const val PREFS = "molina_search_history"
    private const val KEY = "items"
    private const val MAX = 5
    private const val MIN_LEN = 2

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(c: Context): List<String> =
        prefs(c).getString(KEY, "")?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    fun add(c: Context, query: String) {
        val q = query.trim().replace('\n', ' ')
        if (q.length < MIN_LEN) return
        val next = (listOf(q) + load(c).filterNot { it.equals(q, ignoreCase = true) }).take(MAX)
        prefs(c).edit().putString(KEY, next.joinToString("\n")).apply()
    }

    fun clear(c: Context) {
        prefs(c).edit().remove(KEY).apply()
    }
}
