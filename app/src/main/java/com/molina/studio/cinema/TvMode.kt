package com.molina.studio.cinema

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

/**
 * Sakelar "Mode TV" (SIDE-5, tahap 3). Bila aktif, Beranda Molina Cinema menampilkan tile "Mode TV" yang
 * membuka [TvBrowser] layar penuh. Disimpan di SharedPreferences sendiri (bukan milik mpv); [version]
 * adalah state Compose supaya Beranda langsung menyesuaikan setelah sakelar di Setelan diubah.
 */
internal object TvMode {
    private const val FILE = "molina_cinema_ui"
    private const val KEY = "tv_mode"

    var version by mutableIntStateOf(0)
        private set

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = try {
        sp(ctx).getBoolean(KEY, false)
    } catch (e: ClassCastException) {
        false
    }

    fun setEnabled(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY, v).apply()
        version++
    }
}

/** [TvBrowser] di dialog layar penuh; tombol Kembali menutupnya (onDismissRequest). */
@Composable
internal fun TvBrowserDialog(
    onPlay: (File) -> Unit,
    onDismiss: () -> Unit,
    recents: List<CinemaRecent.Item> = emptyList(),
    onPlayRecent: (CinemaRecent.Item) -> Unit = {},
    onClearRecent: () -> Unit = {}
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) { TvBrowser(onPlay = onPlay, onClose = onDismiss, recents = recents, onPlayRecent = onPlayRecent, onClearRecent = onClearRecent) }
    }
}
