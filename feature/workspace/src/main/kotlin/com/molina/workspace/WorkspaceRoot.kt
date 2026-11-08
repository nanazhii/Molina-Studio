package com.molina.workspace

import android.content.Context
import java.io.File

/**
 * Folder kerja yang tersimpan (SharedPreferences). Aturan ada di [WorkspaceRootPolicy].
 * Folder tersimpan yang hilang TIDAK menimpa nilai tersimpan: pemanggil (UI) cukup menampilkan
 * peringatan [WorkspaceRootPolicy.Resolved.fellBack] sekali per proses, jadi penyimpanan yang
 * sementara tak terpasang tidak mereset pilihan pengguna.
 */
object WorkspaceRoot {
    private const val PREFS = "molina_workspace"
    private const val KEY_ROOT = "root_v1"
    private const val KEY_RECENT = "recent_v1"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun resolve(ctx: Context, fallback: File): WorkspaceRootPolicy.Resolved =
        WorkspaceRootPolicy.resolve(prefs(ctx).getString(KEY_ROOT, null), fallback)

    fun get(ctx: Context, fallback: File): File = resolve(ctx, fallback).dir

    /** Simpan [dir] sebagai folder kerja dan masukkan ke riwayat. False bila bukan folder. */
    fun set(ctx: Context, dir: File): Boolean {
        if (!dir.isDirectory) return false
        val d = WorkspaceRootPolicy.normalize(dir).path
        val p = prefs(ctx)
        val recent = WorkspaceRootPolicy.pushRecent(WorkspaceRootPolicy.decode(p.getString(KEY_RECENT, null)), d)
        p.edit()
            .putString(KEY_ROOT, d)
            .putString(KEY_RECENT, WorkspaceRootPolicy.encode(recent))
            .apply()
        return true
    }

    /** Naik satu level dari folder kerja saat ini; null bila sudah di batas atau gagal. */
    fun up(ctx: Context, fallback: File): File? {
        val parent = WorkspaceRootPolicy.parent(get(ctx, fallback)) ?: return null
        return if (set(ctx, parent)) parent else null
    }

    /** Riwayat folder kerja (maks. 5, terbaru di depan) yang masih ada. */
    fun recent(ctx: Context): List<File> =
        WorkspaceRootPolicy.decode(prefs(ctx).getString(KEY_RECENT, null)).map { File(it) }.filter { it.isDirectory }
}
