package com.molina.studio.cinema

import android.content.Context

/** Setelan sortir Penjelajah (SharedPreferences molina_explorer): diingat antar pembukaan. */
internal object ExplorerPrefs {
    private const val PREFS = "molina_explorer"

    class Data(
        val key: ExplorerList.Key,
        val asc: Boolean,
        val foldersFirst: Boolean,
        val showHidden: Boolean,
        val deep: Boolean,
        val playedFirst: Boolean = false
    )

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): Data {
        val s = p(ctx)
        val key = try {
            ExplorerList.Key.valueOf(s.getString("sort_key", "NAME") ?: "NAME")
        } catch (e: Exception) {
            ExplorerList.Key.NAME
        }
        return Data(
            key, s.getBoolean("sort_asc", true), s.getBoolean("folders_first", true),
            s.getBoolean("show_hidden", false), s.getBoolean("deep_search", false), s.getBoolean("played_first", false)
        )
    }

    fun save(ctx: Context, d: Data) {
        p(ctx).edit()
            .putString("sort_key", d.key.name).putBoolean("sort_asc", d.asc)
            .putBoolean("folders_first", d.foldersFirst).putBoolean("show_hidden", d.showHidden)
            .putBoolean("deep_search", d.deep).putBoolean("played_first", d.playedFirst).apply()
    }
}
