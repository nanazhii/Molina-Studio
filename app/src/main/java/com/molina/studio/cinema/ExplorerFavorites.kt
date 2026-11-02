package com.molina.studio.cinema

import android.content.Context
import org.json.JSONArray
import java.io.File

/** Favorit buatan pengguna di panel kiri Penjelajah (SharedPreferences, daftar jalur dalam JSON). */
internal object ExplorerFavorites {
    private const val PREFS = "molina_explorer"
    private const val KEY = "favorites_v1"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun paths(ctx: Context): MutableList<String> {
        val raw = prefs(ctx).getString(KEY, null) ?: return mutableListOf()
        return try {
            val a = JSONArray(raw)
            MutableList(a.length()) { a.getString(it) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun save(ctx: Context, list: List<String>) {
        prefs(ctx).edit().putString(KEY, JSONArray(list).toString()).apply()
    }

    /** Favorit tersimpan yang foldernya masih ada. */
    fun load(ctx: Context): List<File> = paths(ctx).map { File(it) }.filter { it.isDirectory }

    fun contains(ctx: Context, dir: File): Boolean = paths(ctx).any { it == dir.path }

    fun add(ctx: Context, dir: File) {
        val l = paths(ctx)
        if (l.none { it == dir.path }) { l.add(dir.path); save(ctx, l) }
    }

    fun remove(ctx: Context, dir: File) {
        val l = paths(ctx)
        if (l.removeAll { it == dir.path }) save(ctx, l)
    }
}
