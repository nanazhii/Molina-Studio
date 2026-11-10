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

    // [RUN-HINT] Tips skrip (shebang, izin eksekusi). Dibaca molina-run dari ~/.config/molina/run-notes; berkas tidak ada = tampil.
    private fun notesFile(ctx: Context) = File(RootfsManager.rootfsDir(ctx), "root/.config/molina/run-notes")

    fun notesOn(ctx: Context): Boolean = runCatching {
        notesFile(ctx).takeIf { it.isFile }?.readText()?.lineSequence()?.firstOrNull()?.trim() != "off"
    }.getOrDefault(true)

    fun setNotes(ctx: Context, on: Boolean): Boolean = runCatching {
        val f = notesFile(ctx)
        if (on) {
            f.delete()
        } else {
            f.parentFile?.mkdirs()
            f.writeText("off\n")
        }
        true
    }.getOrDefault(false)

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
