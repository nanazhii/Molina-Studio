package com.molina.studio

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.molina.studio.code.ScriptItem
import com.molina.studio.security.ShortcutToken

/**
 * Pintasan skrip di layar utama (pinned shortcut), bukan shortcut dinamis: kuota launcher sudah terpakai
 * Cinema, Terminal, dan shortcut dinamis. Intent HANYA membawa id skrip (isi/judul tidak ikut karena shortcut
 * dapat dibaca launcher), dan MainActivity selalu menampilkan dialog konfirmasi sebelum menjalankan.
 */
internal object ScriptShortcuts {
    const val EXTRA_RUN = "molina_run_script"
    private const val ID_PREFIX = "molina_script_"
    private fun shortcutId(id: String) = ID_PREFIX + id

    /** [INTENT-TOKEN] Intent pintasan membawa token per-pemasangan agar aplikasi lain tidak bisa memunculkan dialog jalankan skrip. */
    private fun runIntent(app: Context, scriptId: String, token: String): Intent =
        Intent(app, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .putExtra(EXTRA_RUN, scriptId).putExtra(ShortcutToken.EXTRA, token)

    /** Pesan siap tampil sebagai Toast. */
    fun pin(ctx: Context, item: ScriptItem): String {
        val app = ctx.applicationContext
        val sm = app.getSystemService(ShortcutManager::class.java) ?: return "Pintasan tidak didukung perangkat ini"
        if (!sm.isRequestPinShortcutSupported) return "Launcher tidak mendukung pintasan di layar utama"
        val info = ShortcutInfo.Builder(app, shortcutId(item.id))
            .setShortLabel(item.title.take(12))
            .setLongLabel(item.title.take(40))
            .setIcon(Icon.createWithResource(app, R.mipmap.ic_shortcut_code))
            .setIntent(runIntent(app, item.id, ShortcutToken.get(app)))
            .build()
        return if (runCatching { sm.requestPinShortcut(info, null) }.getOrDefault(false)) {
            "Konfirmasi pintasan \"${item.title}\" di layar utama"
        } else {
            "Gagal meminta pintasan"
        }
    }

    /** [INTENT-TOKEN] Pintasan yang disematkan sebelum token ada (atau setelah data dihapus) diperbarui intent-nya. */
    fun refreshPinned(ctx: Context) {
        val app = ctx.applicationContext
        val sm = app.getSystemService(ShortcutManager::class.java) ?: return
        val token = ShortcutToken.get(app)
        val stale = sm.pinnedShortcuts.filter {
            it.id.startsWith(ID_PREFIX) && it.isEnabled && it.intent?.getStringExtra(ShortcutToken.EXTRA) != token
        }
        if (stale.isEmpty()) return
        sm.updateShortcuts(stale.map {
            ShortcutInfo.Builder(app, it.id).setIntent(runIntent(app, it.id.removePrefix(ID_PREFIX), token)).build()
        })
    }

    /** Nonaktifkan pintasan yang sudah disematkan saat skrip dihapus. */
    fun onDeleted(ctx: Context, id: String) {
        runCatching {
            ctx.applicationContext.getSystemService(ShortcutManager::class.java)
                ?.disableShortcuts(listOf(shortcutId(id)), "Skrip sudah dihapus")
        }
    }
}
