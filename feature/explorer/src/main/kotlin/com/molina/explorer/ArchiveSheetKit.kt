package com.molina.explorer

import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

// [ARCHIVE-SHEET-3] Kerangka lembar arsip bersama: dialog ZIP (W7ZipDialog) dan dialog 7z/tar/gz/dll (ArchiveExtractDialog)
// memakai satu bentuk, satu posisi, dan satu palet (ExplorerTheme). Dua mode, dipilih satu saklar:
//  - ARCHIVE_SHEET_FULLSCREEN = true : layar penuh sungguhan (tepi ke tepi, isi diberi padding bilah sistem)
//  - ARCHIVE_SHEET_FULLSCREEN = false: lembar bulat setinggi ARCHIVE_SHEET_HEIGHT_FRACTION, di tengah (mode patch 4)

/** Saklar mode. Bila tampilan di perangkat bermasalah (isi tertutup bilah status), ubah ke false lalu build ulang. */
internal const val ARCHIVE_SHEET_FULLSCREEN = true

/** Tinggi lembar mode jendela sebagai pecahan tinggi layar (1.0 = penuh). */
internal const val ARCHIVE_SHEET_HEIGHT_FRACTION = 0.94f

/** Jarak aman atas minimum bila sistem tidak melaporkan inset bilah status (terjadi di sebagian Android lama). */
internal val ARCHIVE_SHEET_MIN_TOP: Dp = 24.dp

internal val ArchiveSheetShape = RoundedCornerShape(16.dp)

/** Properti Dialog lembar arsip; mode layar penuh melepas inset bawaan dekor dan menolak tutup lewat ketuk di luar. */
internal fun archiveSheetProperties(): DialogProperties = DialogProperties(
    dismissOnClickOutside = !ARCHIVE_SHEET_FULLSCREEN,
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = !ARCHIVE_SHEET_FULLSCREEN
)

/** Memusatkan jendela Dialog secara vertikal; panggil di awal isi Dialog. */
@Composable
internal fun ArchiveSheetGravity() {
    val w = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect { w?.setGravity(Gravity.CENTER) }
}

@Composable
internal fun archiveSheetHeight(): Dp = (LocalConfiguration.current.screenHeightDp * ARCHIVE_SHEET_HEIGHT_FRACTION).dp

/** Modifier lembar mode jendela: lebar penuh, tinggi tetap [height], sudut membulat, latar hitam, bingkai tipis. */
internal fun Modifier.archiveSheet(height: Dp, th: ExplorerTheme): Modifier = this
    .fillMaxWidth()
    .padding(horizontal = 6.dp)
    .height(height)
    .clip(ArchiveSheetShape)
    .background(th.bg, ArchiveSheetShape)
    .border(1.dp, th.frame.copy(alpha = 0.4f), ArchiveSheetShape)

/** Modifier kerangka yang dipakai kedua dialog; memilih mode lewat [ARCHIVE_SHEET_FULLSCREEN]. Panggil di dalam isi Dialog. */
@Composable
internal fun archiveSheetModifier(th: ExplorerTheme): Modifier {
    if (!ARCHIVE_SHEET_FULLSCREEN) return Modifier.archiveSheet(archiveSheetHeight(), th)
    val bars = WindowInsets.systemBars.union(WindowInsets.ime).asPaddingValues() // [ARCHIVE-UNI] keyboard tidak menutup isian/tombol
    val extraTop = (ARCHIVE_SHEET_MIN_TOP - bars.calculateTopPadding()).coerceAtLeast(0.dp)
    return Modifier.fillMaxSize().background(th.bg).padding(bars).padding(top = extraTop)
}
