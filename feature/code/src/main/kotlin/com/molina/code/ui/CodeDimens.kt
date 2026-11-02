package com.molina.code.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Token ukuran Code Editor. Acuan: docs/blueprint-code-editor.md bagian 3-5, 13-16, 20-21. */
object CodeDimens {
    // Editor
    val EditorTextSize = 13.sp
    val CaretWidth = 2.dp
    val ActiveMatchBorder = 1.5.dp

    // Header path dan tab bar (nilai tidak ada di blueprint; ditetapkan di F2, ubah bila perlu)
    val HeaderHeight = 40.dp
    val PathTextSize = 12.sp
    val TabBarHeight = 40.dp
    val TabMinWidth = 96.dp
    val TabMaxWidth = 180.dp
    val TabCloseWidth = 32.dp
    val TabTopLine = 2.dp
    val TabTitleSize = 13.sp
    val DirtyDot = 6.dp

    // Toolbar: 10 ikon rata lebar (36dp), area sentuh setinggi 48dp
    val ToolbarHeight = 48.dp
    val ToolbarItemWidth = 36.dp
    val ToolbarIconSize = 22.dp
    val ToolbarGroupDividerHeight = 16.dp
    val Hairline = 1.dp
    val RailHeight = 1.dp
    val TabBadgeTextSize = 10.sp

    // Panel direktori
    const val DrawerWidthFraction = 0.72f
    val DrawerMaxWidth = 320.dp
    val DrawerHeaderHeight = 48.dp
    val DrawerHeaderIcon = 20.dp
    val TreeRowHeight = 32.dp
    val TreeIndent = 14.dp
    val TreeExpanderSize = 10.dp
    val TreeFolderIcon = 18.dp
    val TreeTextSize = 13.sp
    val TreeSelectCorner = 3.dp
    val TreeSelectBorder = 1.dp
    val TreeMenuButtonWidth = 36.dp
    val TreeMenuDots = 16.dp

    val DrawerButton = 32.dp
    val PickerRowHeight = 40.dp
    val EdgeSwipeWidth = 14.dp
    val StatusTextSize = 11.sp

    // Empty state
    val EmptyTextSize = 13.sp
    val EmptyButtonHeight = 36.dp
    val EmptyButtonMinWidth = 128.dp
    val EmptyButtonTextSize = 12.sp
    val EmptyButtonCorner = 4.dp
    val EmptyButtonGap = 8.dp

    // Menu titik tiga
    val MenuCorner = 12.dp
    val MenuElevation = 8.dp
    val MenuMinWidth = 208.dp
    val MenuMaxWidth = 248.dp
    const val MenuMaxHeightFraction = 0.72f
    val MenuRowHeight = 44.dp
    val MenuTextSize = 15.sp
    val MenuIconSize = 18.dp

    // Find bar
    val FindRowHeight = 44.dp
    val FindButtonWidth = 36.dp
    val FindTextSize = 14.sp
    val FindUnderline = 1.5.dp

    // Dialog
    val DialogCorner = 12.dp
    val DialogTitleSize = 18.sp
    val SuggestionRowHeight = 40.dp

    // Panel pratinjau / keluaran (di bawah editor, terbuka lewat tombol Play)
    val PaneHeaderHeight = 36.dp
    val PaneLabelSize = 12.sp
    val PaneActionWidth = 52.dp
    val PanePadding = 14.dp
    val PaneBodySize = 14.sp
    val PaneMonoSize = 12.sp
    val PaneTableCellWidth = 128.dp

    // Tinjauan
    val PreviewTitleSize = 18.sp
    val PreviewSubtitleSize = 12.sp
}
