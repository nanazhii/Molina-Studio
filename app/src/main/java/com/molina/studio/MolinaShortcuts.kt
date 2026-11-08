package com.molina.studio

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.molina.core.common.LogLevel
import com.molina.studio.cinema.CinemaRecent
import com.molina.studio.log.LogBus
import com.molina.studio.security.ShortcutToken
import java.util.concurrent.Executors

/**
 * Shortcut dinamis ikon (tekan lama): "Putar terakhir" (hanya bila riwayat Cinema tidak kosong), "Unduhan",
 * "Penyimpanan", dan "Code" (dulu statis). Pelengkap shortcut statis Cinema dan Terminal (molina_shortcuts.xml).
 * Urutan = prioritas tampil di launcher (umumnya 4 teratas, statis lebih dulu). Intent hanya membawa nama tujuan
 * (molina_open_tab); URL atau judul riwayat TIDAK dimasukkan karena shortcut dapat dibaca aplikasi launcher.
 * Jumlah yang dipublikasikan dibatasi sisa kuota (maks. per aktivitas dikurangi shortcut statis).
 */
internal object MolinaShortcuts {
    private const val EXTRA_TAB = "molina_open_tab"
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "molina-shortcuts").apply { isDaemon = true } }

    /** Aman dipanggil dari thread mana pun; kerja IPC dilakukan di thread latar. */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        io.execute {
            runCatching { publish(app) }.onFailure {
                LogBus.log("sistem", LogLevel.Warn, "shortcut", "Gagal memperbarui shortcut: ${it.message}")
            }
            runCatching { ScriptShortcuts.refreshPinned(app) } // [INTENT-TOKEN]
        }
    }

    private fun item(ctx: Context, id: String, tab: String, short: Int, long: Int, icon: Int, rank: Int): ShortcutInfo =
        ShortcutInfo.Builder(ctx, id)
            .setShortLabel(ctx.getString(short))
            .setLongLabel(ctx.getString(long))
            .setIcon(Icon.createWithResource(ctx, icon))
            .setIntent(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_MAIN).putExtra(EXTRA_TAB, tab).putExtra(ShortcutToken.EXTRA, ShortcutToken.get(ctx))) // [INTENT-TOKEN]
            .setRank(rank)
            .build()

    private fun publish(ctx: Context) {
        val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
        if (sm.isRateLimitingActive) return
        val all = ArrayList<ShortcutInfo>()
        if (CinemaRecent.load(ctx).isNotEmpty()) {
            all += item(ctx, "molina_last", "play_last", R.string.shortcut_last_short, R.string.shortcut_last_long, R.mipmap.ic_shortcut_last, 0)
        }
        all += item(ctx, "molina_downloads", "downloads", R.string.shortcut_downloads_short, R.string.shortcut_downloads_long, R.mipmap.ic_shortcut_downloads, 1)
        all += item(ctx, "molina_storage", "storage", R.string.shortcut_storage_short, R.string.shortcut_storage_long, R.mipmap.ic_shortcut_storage, 2)
        all += item(ctx, "molina_code_dyn", "code", R.string.shortcut_code_short, R.string.shortcut_code_long, R.mipmap.ic_shortcut_code, 3)
        val room = (sm.maxShortcutCountPerActivity - sm.manifestShortcuts.size).coerceAtLeast(0)
        if (!sm.setDynamicShortcuts(all.take(room))) {
            LogBus.log("sistem", LogLevel.Warn, "shortcut", "Shortcut dinamis ditolak sistem (kemungkinan dibatasi laju)")
        }
    }
}
