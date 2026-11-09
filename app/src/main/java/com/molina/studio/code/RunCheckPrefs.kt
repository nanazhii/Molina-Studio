package com.molina.studio.code

import android.content.Context
import com.molina.studio.terminal.RootfsManager
import java.io.File

/**
 * [CODE-ROLES] Mode pemeriksaan molina-run (ask | fix | no). Disimpan di berkas yang dibaca molina-run sendiri
 * (~/.config/molina/run-ask di Debian), jadi berlaku untuk Jalankan di Terminal dan Alt+R di nano tanpa env tambahan.
 * Bawaan (ask) = berkas tidak ada.
 */
object RunCheckPrefs {
    val MODES = listOf("ask", "fix", "no")

    private fun file(ctx: Context) = File(RootfsManager.rootfsDir(ctx), "root/.config/molina/run-ask")

    fun get(ctx: Context): String {
        val v = runCatching { file(ctx).takeIf { it.isFile }?.readText()?.lineSequence()?.firstOrNull()?.trim() }.getOrNull()
        return if (v != null && v in MODES) v else "ask"
    }

    fun set(ctx: Context, mode: String): Boolean = runCatching {
        val f = file(ctx)
        if (mode == "ask") {
            f.delete()
        } else {
            f.parentFile?.mkdirs()
            f.writeText(mode + "\n")
        }
        true
    }.getOrDefault(false)
}
