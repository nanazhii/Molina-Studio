package com.molina.code.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.molina.code.preview.PreviewKind
import io.github.rosemoe.sora.widget.SelectionMovement
import kotlinx.coroutines.delay

/**
 * Layar tab Code (F2, F3, F4): header path, tab bar, editor, toolbar, dan panel direktori.
 * Menu titik tiga ada di OverflowMenu.kt (F3b). Semua warna/ukuran dari [CodeColors] dan [CodeDimens].
 */
@Composable
fun CodeScreen(session: CodeSession, modifier: Modifier = Modifier) {
    var pendingClose by remember { mutableStateOf<EditorTab?>(null) }
    val ctx = LocalContext.current
    SideEffect { session.toast = { msg -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() } }
    session.active?.let { t -> // [CODE-LSP3] sinkron ke server saat tab aktif/dimuat/berkasnya berubah atau saklar dinyalakan
        LaunchedEffect(t.id, t.loading, t.file, t.editor, session.liveEnabled) { session.liveSync(t) }
    }

    // Pratinjau langsung: bangun ulang setelah mengetik berhenti sejenak (debounce). Pindah tab
    // atau membuka panel langsung dibangun tanpa jeda.
    val liveTab = session.active
    LaunchedEffect(
        liveTab?.id, liveTab?.revision, liveTab?.file,
        session.preview.open, session.preview.mode, session.preview.htmlWebView
    ) {
        val tab = liveTab ?: return@LaunchedEffect
        val pv = session.preview
        if (!pv.open || pv.mode != PaneMode.Preview) return@LaunchedEffect
        if (pv.shownTabId == tab.id) delay(PreviewController.DEBOUNCE_MS)
        pv.refresh(tab)
    }

    Box(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxSize().background(CodeColors.EditorBg)) {
            PathHeader(session)
            EditorTabBar(session) { tab ->
                if (tab.dirty) pendingClose = tab else session.close(tab.id)
            }
            EditorHost(session, Modifier.weight(1f).fillMaxWidth())
            session.active?.let { t ->
                // [CODE-LSP3] lencana hanya untuk berkas yang didukung server (Python) dan saat saklar menyala
                val supported = session.liveEnabled && t.file != null && com.molina.code.lsp.LspServers.of(t.file!!.name, if (t.revision >= 0) t.firstLineOrNull() else null) != null
                CodeStatusBar(t, if (supported) liveBadge(session.liveState, session.liveDetail) else null, library = session.isLibraryFile(t.file)) // [CODE-DEBLIB]
            }
            if (session.find.open) {
                session.active?.let { FindBar(session, it) }
            }
            session.active?.let { tab ->
                // Membaca revision: jenis berkas bisa berubah saat shebang diketik.
                val kind = if (tab.revision >= 0) tab.previewKind() else PreviewKind.NONE
                val previewing = (kind == PreviewKind.MARKDOWN || kind == PreviewKind.JSON || kind == PreviewKind.HTML) &&
                    session.preview.open && session.preview.mode == PaneMode.Preview
                val play = when {
                    session.run.running -> PlayState.Running
                    kind == PreviewKind.NONE -> PlayState.Off
                    previewing -> PlayState.On
                    else -> PlayState.Ready
                }
                EditorToolbar(
                    tab = tab,
                    onSave = { session.save(tab) },
                    onPaste = { tab.editor?.pasteText() },
                    onUndo = { tab.editor?.undo() },
                    onRedo = { tab.editor?.redo() },
                    onLeft = { tab.editor?.moveSelection(SelectionMovement.LEFT) },
                    onRight = { tab.editor?.moveSelection(SelectionMovement.RIGHT) },
                    onSelectAllCopy = {
                        tab.editor?.let { ed ->
                            if (ed.text.length > 0) {
                                ed.selectAll()
                                ed.copyText()
                                Toast.makeText(ctx, "Semua teks disalin", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onFind = { session.openFind() },
                    play = play,
                    onPlay = { session.playActive() },
                    onClose = { if (tab.dirty) pendingClose = tab else session.close(tab.id) }
                )
            }
        }
        // Pegangan geser dari tepi kiri area editor (di bawah header + tab bar, di atas toolbar)
        EdgeSwipeHandle(
            session,
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(CodeDimens.EdgeSwipeWidth)
                .padding(top = CodeDimens.HeaderHeight + CodeDimens.TabBarHeight, bottom = CodeDimens.ToolbarHeight + Win7Code.StatusHeight)
        )
        BackPill( // [CODE-BACK]
            session,
            Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = CodeDimens.ToolbarHeight + Win7Code.StatusHeight + 10.dp),
        )
        DirectoryPanel(session)
        // Halaman penuh: Tinjauan (Markdown/JSON/HTML) atau Keluaran skrip (.sh/.py), menutupi editor sampai ditutup.
        if (session.previewFullScreen) {
            PreviewPage(
                session,
                onJump = { line, col ->
                    session.preview.open = false
                    session.active?.editor?.jumpToLineHighlighted(line - 1, col)
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    FileDialogs(session)

    if (session.gotoOpen) {
        session.active?.let { GotoLineDialog(it) { session.gotoOpen = false } }
    }

    if (session.paletteOpen) CommandPalette(session) { session.paletteOpen = false } // [CODE-PALETTE]
    if (session.findFilesOpen) FindInFilesDialog(session) { session.findFilesOpen = false } // [CODE-SEARCH]
    if (session.templatesOpen) LiveTemplateDialog(session) { session.templatesOpen = false } // [CODE-TEMPLATE]
    if (session.scratchOpen) ScratchDialog(session) { session.scratchOpen = false }
    if (session.pyRunOpen) session.active?.let { PythonRunDialog(session, it) { session.pyRunOpen = false } } // [CODE-PYRUN]
    if (session.historyOpen) session.active?.let { HistoryDialog(session, it) { session.historyOpen = false } } // [CODE-HISTORY]
    if (session.symbolOpen) SymbolDialog(session) { session.symbolOpen = false } // [CODE-SYMBOL]
    session.definitionChoices?.let { DefinitionDialog(session, it) { session.definitionChoices = null } } // [CODE-LSP6]
    if (session.problemsOpen) session.problems?.let { ProblemsDialog(session, it) { session.problemsOpen = false } } // [CODE-CHECK]
    if (session.outlineOpen) session.active?.let { OutlineDialog(it) { session.outlineOpen = false } }
    if (session.infoOpen) session.active?.let { FileInfoScreen(it) { session.infoOpen = false } } // [CODE-INFO]
    if (session.fontOpen) FontSizeDialog(session) { session.fontOpen = false } // [CODE-FONT]
    if (session.themeOpen) ThemeDialog(session) { session.themeOpen = false } // [CODE-TM]

    session.saveAs?.let { SaveAsDialog(session, it) }

    pendingClose?.let { tab ->
        ConfirmCloseDialog(
            title = tab.title,
            onSave = {
                pendingClose = null
                session.save(tab, after = { session.close(tab.id) })
            },
            onDiscard = {
                session.close(tab.id)
                pendingClose = null
            },
            onCancel = { pendingClose = null }
        )
    }
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(CodeDimens.Hairline).background(CodeColors.Hairline))
}

/**
 * Header: ikon folder (buka panel direktori), path berkas aktif (atau folder utama bila tab baru),
 * chip status isi (Diubah / Tersimpan / Baru), dan tombol titik tiga yang membuka [OverflowMenu].
 */
@Composable
private fun PathHeader(session: CodeSession) {
    var menuOpen by remember { mutableStateOf(false) }
    val tab = session.active
    val path = tab?.file?.path ?: session.dir.root.path
    Row(
        Modifier
            .fillMaxWidth()
            .height(CodeDimens.HeaderHeight)
            .win7Aero(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(CodeDimens.HeaderHeight)
                .fillMaxHeight()
                .clickable { session.dir.openPanel() },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(CodeDimens.DrawerHeaderIcon)) { drawDirIcon(DirIcon.Folder, CodeColors.FolderYellow) }
        }
        BasicText(
            text = path,
            modifier = Modifier.weight(1f).clickable { session.dir.openPanel() },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = Win7Code.Ink, fontSize = CodeDimens.PathTextSize)
        )
        if (tab != null) StatusChip(tab)
        Box(
            Modifier
                .width(CodeDimens.HeaderHeight)
                .fillMaxHeight()
                .clickable { menuOpen = true },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(CodeDimens.ToolbarIconSize)) {
                drawToolbarIcon(ToolbarIcon.More, CodeColors.IconDefault, false)
            }
            if (menuOpen) OverflowMenu(session) { menuOpen = false }
        }
    }
    Hairline()
}

/** Status isi tab: titik emas + "Diubah" bila beda dari yang tersimpan; "Tersimpan" bila sama; "Baru" bila belum punya berkas. */
@Composable
private fun StatusChip(tab: EditorTab) {
    val label: String
    val color: androidx.compose.ui.graphics.Color
    when {
        tab.loading -> { label = "Memuat"; color = Win7Code.InkDim }
        tab.dirty -> { label = "Diubah"; color = CodeColors.Danger }
        tab.file != null -> { label = "Tersimpan"; color = Win7Code.InkDim }
        else -> { label = "Baru"; color = Win7Code.InkDim }
    }
    Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!tab.loading) {
            Box(
                Modifier.size(CodeDimens.DirtyDot)
                    .background(if (tab.dirty) CodeColors.Danger else CodeColors.Clean, CircleShape)
            )
            Spacer(Modifier.width(4.dp))
        }
        BasicText(
            text = label,
            maxLines = 1,
            style = TextStyle(color = color, fontSize = CodeDimens.StatusTextSize)
        )
    }
}

@Composable
private fun EditorTabBar(session: CodeSession, onRequestClose: (EditorTab) -> Unit) {
    val scroll = rememberScrollState()
    var viewport by remember { mutableIntStateOf(0) }
    // Posisi (kiri, lebar) tiap tab di dalam area scroll, untuk auto-scroll ke tab aktif.
    val bounds = remember { mutableStateMapOf<Int, Pair<Int, Int>>() }
    val activeBounds = bounds[session.activeId]

    LaunchedEffect(session.activeId, activeBounds, viewport) {
        val (left, width) = activeBounds ?: return@LaunchedEffect
        val right = left + width
        val target = when {
            left < scroll.value -> left
            right > scroll.value + viewport -> right - viewport
            else -> return@LaunchedEffect
        }
        scroll.animateScrollTo(target.coerceAtLeast(0))
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(CodeDimens.TabBarHeight)
            .background(Win7Code.TabBarBrush)
            .onSizeChanged { viewport = it.width }
            .horizontalScroll(scroll)
    ) {
        session.tabs.forEach { tab ->
            key(tab.id) {
                DisposableEffect(Unit) { onDispose { bounds.remove(tab.id) } }
                TabItem(
                    tab = tab,
                    active = tab.id == session.activeId,
                    onSelect = { session.select(tab.id) },
                    onClose = { onRequestClose(tab) },
                    onBounds = { x, w -> bounds[tab.id] = x to w }
                )
            }
        }
        Box(
            Modifier
                .width(CodeDimens.TabBarHeight)
                .fillMaxHeight()
                .clickable { session.newTab() },
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = "+",
                style = TextStyle(color = CodeColors.IconDefault, fontSize = 20.sp)
            )
        }
    }
    Hairline()
}

/** Tab aktif: garis emas di ATAS, latar lebih gelap. Titik emas = belum disimpan. */
@Composable
private fun TabItem(
    tab: EditorTab,
    active: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onBounds: (Int, Int) -> Unit
) {
    Box(
        Modifier
            .height(CodeDimens.TabBarHeight)
            .widthIn(min = CodeDimens.TabMinWidth, max = CodeDimens.TabMaxWidth)
            .onGloballyPositioned { onBounds(it.positionInParent().x.toInt(), it.size.width) }
            .win7Tab(active)
            .clickable(onClick = onSelect)
    ) {
        if (active) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(CodeDimens.TabTopLine)
                    .background(Win7Code.TabLine)
            )
        }
        Row(
            Modifier.fillMaxWidth().fillMaxHeight().padding(start = 12.dp), // [CODE-TABCLOSE-EDGE]
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!tab.loading) {
                Box(
                    Modifier.size(CodeDimens.DirtyDot)
                        .background(if (tab.dirty) CodeColors.Danger else CodeColors.Clean, CircleShape)
                )
                Spacer(Modifier.width(6.dp))
            }
            BasicText(
                text = tab.title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = if (active) CodeColors.TextPrimary else CodeColors.TextSecondary,
                    fontSize = CodeDimens.TabTitleSize
                )
            )
            Box(
                Modifier.width(CodeDimens.TabCloseWidth).fillMaxHeight().clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = "\u2715",
                    style = TextStyle(color = CodeColors.IconDefault, fontSize = 12.sp)
                )
            }
        }
    }
}

/** Konfirmasi tutup tab dirty (gaya Win7): Simpan, Buang, atau Batal. */
@Composable
private fun ConfirmCloseDialog(
    title: String,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit
) {
    val shape = RoundedCornerShape(9.dp)
    Dialog(onDismissRequest = onCancel) {
        Column(Modifier.widthIn(max = 340.dp).win7DialogFrame(shape)) {
            Win7DialogTitleBar("Molina Code", onCancel)
            Column(Modifier.padding(16.dp)) {
                BasicText(
                    text = "Simpan perubahan?",
                    style = TextStyle(color = Win7Code.Ink, fontSize = 16.sp)
                )
                Spacer(Modifier.height(8.dp))
                BasicText(
                    text = "\"$title\" belum disimpan. Simpan sebelum menutup?",
                    style = TextStyle(color = CodeColors.TextPrimary, fontSize = 14.sp)
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    Win7DialogButton("Batal", onCancel)
                    Win7DialogButton("Buang", onDiscard, accent = CodeColors.Danger)
                    Win7DialogButton("Simpan", onSave, isDefault = true)
                }
            }
        }
    }
}
