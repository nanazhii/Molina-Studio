package com.molina.studio.cinema

import android.content.Context
import org.json.JSONArray
import java.io.File

/** Riwayat folder terakhir Penjelajah (maks. 5, terbaru di depan) dan folder terakhir yang dibuka. */
internal object ExplorerRecent {
    private const val PREFS = "molina_explorer"
    private const val KEY = "recent_v1"
    private const val MAX = 5

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

    /** Folder terakhir yang masih ada, terbaru lebih dulu. */
    fun load(ctx: Context): List<File> = paths(ctx).map { File(it) }.filter { it.isDirectory }

    fun last(ctx: Context): File? = load(ctx).firstOrNull()

    fun push(ctx: Context, dir: File) {
        if (!dir.isDirectory) return
        val l = paths(ctx)
        l.removeAll { it == dir.path }
        l.add(0, dir.path)
        while (l.size > MAX) l.removeAt(l.size - 1)
        prefs(ctx).edit().putString(KEY, JSONArray(l).toString()).apply()
    }

    // [NANO-EXT] Berkas terakhir yang dipilih di Penjelajah: dipakai sebagai nama + ekstensi bawaan "Berkas baru (nano)".
    private const val KEY_FILE = "last_file_v1"

    fun lastFileName(ctx: Context): String? = prefs(ctx).getString(KEY_FILE, null)?.takeIf { it.isNotBlank() }

    fun pushFile(ctx: Context, f: File) {
        if (!f.isFile) return
        prefs(ctx).edit().putString(KEY_FILE, f.name).apply()
    }
}
