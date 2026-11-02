package com.molina.studio.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.molina.studio.cinema.Win7Folder
import com.molina.studio.cinema.Win7OpenDialog

/**
 * Isi tab Berkas (nav bawah). Penjelajah Molina dibuka otomatis saat tab dipilih; bila ditutup,
 * layar ini menyisakan tombol untuk membukanya lagi. Dulu hidup di ubin Berkas Settings.
 */
@Composable
fun FilesTabScreen(modifier: Modifier = Modifier, onExit: () -> Unit = {}) {
    val ctx = LocalContext.current
    remember { MolinaExplorer.attach(ctx) }
    Win7OpenDialog(
        onDismiss = onExit,
        browse = true,
        asScreen = true,
        screenModifier = modifier,
        onCopyPath = { f -> MolinaExplorer.copyPath(ctx, f) },
        onOpenTerminal = { f -> MolinaExplorer.openTerminalHere(ctx, f) },
        onShare = { files -> MolinaExplorer.share(ctx, files) },
        onOpenWith = { f -> MolinaExplorer.openWith(ctx, f) },
        onInsertPaths = { fs -> MolinaExplorer.insertPaths(ctx, fs) },
        isVisibleInDebian = { f -> MolinaExplorer.visibleInDebian(f) },
        onPlay = { files -> MolinaExplorer.play(ctx, files) },
        onOpenInCode = { files -> MolinaExplorer.openInCode(ctx, files) },
        onSendToScripts = { f -> MolinaExplorer.sendToScripts(f) }
    )
}

/**
 * Ikon tab Berkas: folder satu warna (mengikuti warna tab) digambar dengan Canvas,
 * sehingga tidak bergantung pada glyph font Android 8.
 */
@Composable
fun TabFolderIcon(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(color.copy(alpha = 0.70f), Offset(w * 0.06f, h * 0.16f), Size(w * 0.40f, h * 0.22f), CornerRadius(w * 0.05f))
        drawRoundRect(color, Offset(w * 0.06f, h * 0.28f), Size(w * 0.88f, h * 0.58f), CornerRadius(w * 0.08f))
    }
}
