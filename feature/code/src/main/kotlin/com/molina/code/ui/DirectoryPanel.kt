package com.molina.code.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Panel direktori (F4, blueprint bagian 5): laci dari kiri selebar 72% layar (maks 320dp) dengan
 * scrim. Ditutup dengan geser ke kiri, ketuk scrim, atau tombol X. Dibuka dengan geser dari tepi
 * kiri area editor ([EdgeSwipeHandle]) atau ikon folder di header.
 */
@Composable
internal fun DirectoryPanel(session: CodeSession, modifier: Modifier = Modifier) {
    val dir = session.dir
    if (!dir.open) return
    val screenW = LocalConfiguration.current.screenWidthDp
    val width = minOf((screenW * CodeDimens.DrawerWidthFraction).dp, CodeDimens.DrawerMaxWidth)
    val closeDragPx = with(LocalDensity.current) { 48.dp.toPx() }
    val activePath = session.active?.file?.path
    val rows = dir.rows()

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(CodeColors.Scrim)
                .pointerInput(Unit) { detectTapGestures(onTap = { dir.closePanel() }) }
        )
        Column(
            Modifier
                .width(width)
                .fillMaxHeight()
                .background(CodeColors.SurfaceDrawer)
                .pointerInput(Unit) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = { if (total < -closeDragPx) dir.closePanel() },
                        onDragCancel = { total = 0f },
                        onHorizontalDrag = { _, delta -> total += delta }
                    )
                }
        ) {
            DrawerHeader(session)
            Box(Modifier.fillMaxWidth().height(CodeDimens.Hairline).background(CodeColors.Hairline))
            when {
                dir.rootUnreadable -> DrawerEmptyState(session)
                dir.isRootEmpty() -> Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.TopCenter) {
                    BasicText(
                        text = "Folder kosong. Ketuk ikon file baru atau folder baru di atas.",
                        style = TextStyle(
                            color = CodeColors.TextSecondary,
                            fontSize = CodeDimens.EmptyTextSize,
                            textAlign = TextAlign.Center
                        )
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.entry.file.path }) { row ->
                        val path = row.entry.file.path
                        TreeRow(
                            row = row,
                            session = session,
                            selected = path == dir.selected || path == activePath,
                            onTap = {
                                dir.selected = path
                                if (row.entry.isDir) dir.toggle(row.entry.file) else session.openFile(row.entry.file)
                            },
                            onMenu = {
                                dir.selected = path
                                session.fileDialog = FileDialog.Menu(row.entry.file, fromTree = true)
                            }
                        )
                    }
                }
            }
        }
    }
}

/** Header panel: naik satu level, nama + path folder utama (ketuk = ganti folder), file baru, folder baru, tutup. */
@Composable
private fun DrawerHeader(session: CodeSession) {
    val dir = session.dir
    Row(
        Modifier
            .fillMaxWidth()
            .height(CodeDimens.DrawerHeaderHeight)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DirIconButton(DirIcon.Up, enabled = dir.root.path != FileStore.STORAGE_ROOT) { dir.up() }
        Column(
            Modifier
                .weight(1f)
                .clickable {
                    session.picker = PickerRequest(PickMode.Folder, dir.root, "Pilih folder utama") { dir.changeRoot(it) }
                }
                .padding(horizontal = 4.dp)
        ) {
            BasicText(
                text = dir.root.name.ifEmpty { "/" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = CodeColors.TextPrimary, fontSize = 14.sp)
            )
            BasicText(
                text = dir.root.path,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 10.sp)
            )
        }
        DirIconButton(DirIcon.NewFile) { session.fileDialog = FileDialog.NewFile(dir.root) }
        DirIconButton(DirIcon.NewFolder) { session.fileDialog = FileDialog.NewFolder(dir.root) }
        DirIconButton(DirIcon.Close, tint = CodeColors.Danger) { dir.closePanel() }
    }
}

/** Satu baris tree 32dp: indent, expander (folder), ikon, nama. Titik tiga di kanan = menu konteks. */
@Composable
private fun TreeRow(row: DirRow, session: CodeSession, selected: Boolean, onTap: () -> Unit, onMenu: () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    val menu by rememberUpdatedState(onMenu)
    val shape = RoundedCornerShape(CodeDimens.TreeSelectCorner)
    val entry = row.entry
    val base = Modifier
        .fillMaxWidth()
        .height(CodeDimens.TreeRowHeight)
        .padding(horizontal = 6.dp)
        .clip(shape)
    val styled = if (selected) {
        base
            .background(CodeColors.SelectFill, shape)
            .border(CodeDimens.TreeSelectBorder, CodeColors.SelectBorder, shape)
    } else {
        base
    }
    Row(
        styled
            .alpha(if (entry.hidden) CodeColors.HiddenFileAlpha else 1f)
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(entry.file.path) { detectTapGestures(onTap = { tap() }) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(CodeDimens.TreeIndent * row.depth))
            if (entry.isDir) {
                Canvas(Modifier.size(CodeDimens.TreeExpanderSize)) { drawExpander(row.expanded) }
            } else {
                Spacer(Modifier.size(CodeDimens.TreeExpanderSize))
            }
            Spacer(Modifier.width(6.dp))
            Canvas(Modifier.size(CodeDimens.TreeFolderIcon)) {
                if (entry.isDir) drawFolderIcon(row.expanded, CodeColors.FolderYellow) else drawFileIcon(extColor(entry.file.name))
            }
            Spacer(Modifier.width(6.dp))
            BasicText(
                text = entry.file.name,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = CodeColors.TextPrimary, fontSize = CodeDimens.TreeTextSize)
            )
        }
        // Titik tiga: membuka menu aksi untuk folder maupun berkas (tanpa tekan lama)
        Box(
            Modifier
                .width(CodeDimens.TreeMenuButtonWidth)
                .fillMaxHeight()
                .pointerInput(entry.file.path) { detectTapGestures(onTap = { menu() }) },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(CodeDimens.TreeMenuDots)) { drawMoreDots(CodeColors.IconDim) }
            // Dropdown ringkas menempel di titik tiga baris ini (bukan dialog layar penuh)
            val d = session.fileDialog
            if (d is FileDialog.Menu && d.fromTree && d.file.path == entry.file.path) {
                TreeActionMenu(session, d.file)
            }
        }
    }
}

/** Empty state "Belum ada folder" (blueprint bagian 5.4). Tombol ditumpuk karena panel sempit. */
@Composable
private fun DrawerEmptyState(session: CodeSession) {
    val dir = session.dir
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BasicText(
            text = "Belum ada folder yang bisa dibuka.\n${dir.root.path}",
            style = TextStyle(
                color = CodeColors.TextSecondary,
                fontSize = CodeDimens.EmptyTextSize,
                textAlign = TextAlign.Center
            )
        )
        Spacer(Modifier.height(16.dp))
        DrawerTextButton("BUKA FOLDER") {
            session.picker = PickerRequest(PickMode.Folder, java.io.File(FileStore.STORAGE_ROOT), "Pilih folder utama") {
                dir.changeRoot(it)
            }
        }
        Spacer(Modifier.height(CodeDimens.EmptyButtonGap))
        DrawerTextButton("BUKA BERKAS") {
            session.picker = PickerRequest(PickMode.File, session.defaultOpenDir(), "Buka berkas") { session.openFile(it) }
        }
    }
}

@Composable
private fun DrawerTextButton(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(CodeDimens.EmptyButtonCorner)
    Box(
        Modifier
            .height(CodeDimens.EmptyButtonHeight)
            .widthIn(min = CodeDimens.EmptyButtonMinWidth)
            .clip(shape)
            .background(CodeColors.ButtonBrown, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            style = TextStyle(color = CodeColors.TextPrimary, fontSize = CodeDimens.EmptyButtonTextSize)
        )
    }
}

/** Tombol ikon 32dp dengan ikon 20dp. Nonaktif = alfa 38% dan tidak bisa diketuk. */
@Composable
internal fun DirIconButton(
    kind: DirIcon,
    enabled: Boolean = true,
    tint: Color = CodeColors.IconDefault,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(CodeDimens.DrawerButton)
            .alpha(if (enabled) 1f else CodeColors.DisabledAlpha)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(CodeDimens.DrawerHeaderIcon)) { drawDirIcon(kind, tint) }
    }
}

/** Pegangan geser di tepi kiri area editor: geser ke kanan membuka panel direktori. */
@Composable
internal fun EdgeSwipeHandle(session: CodeSession, modifier: Modifier = Modifier) {
    val openPx = with(LocalDensity.current) { 24.dp.toPx() }
    Box(
        modifier.pointerInput(Unit) {
            var total = 0f
            detectHorizontalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = { if (total > openPx) session.dir.openPanel() },
                onDragCancel = { total = 0f },
                onHorizontalDrag = { _, delta -> total += delta }
            )
        }
    )
}
