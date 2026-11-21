package com.molina.core.ui

import androidx.compose.runtime.Composable
import com.molina.core.files.FolderBadges
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** [TREE-ICON] Ukuran baku daftar berkas bergaya tree (drawer Terminal, tree Code): ikon cukup besar agar label SH/PY terbaca. */
object FileTreeIconSize {
    val Icon: Dp = 30.dp
    val RowHeight: Dp = 44.dp
}

/**
 * [TREE-ICON] Satu pintu ikon baris berkas: folder (hijau Mint, terbuka bila [open]) atau berkas menurut ekstensi
 * lewat [MintFileIcon] (warna dan label dari W7Glyph). Dipakai drawer Terminal dan tree/picker Code agar identik.
 */
@Composable
fun FileTreeIcon(
    isDir: Boolean,
    name: String,
    open: Boolean = false,
    box: Dp = FileTreeIconSize.Icon,
    tint: MintFolderTint = MintFolderTint.Mint
) {
    if (isDir) MintFolder(box, badge = FolderBadges.of(name), open = open, tint = tint) /* [FOLDER-BADGE] */ else MintFileIcon(name.substringAfterLast('.', ""), box)
}
