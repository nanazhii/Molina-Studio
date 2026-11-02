package com.molina.code.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * Satu baris menu titik tiga. [enabled] false = tampil redup (alfa 38%) dan tidak bisa diketuk.
 * [checked] non-null = baris berkotak centang (emas bila aktif).
 */
internal class MenuEntry(
    val label: String,
    val enabled: Boolean = false,
    val checked: Boolean? = null,
    val icon: MenuIcon? = null, // [CODE-MENUICON]
    val danger: Boolean = false, // [MENU-WHITE] true = teks dan ikon merah
    val onClick: () -> Unit = {}
)

private const val MAX_SHARE_CHARS = 200_000

/**
 * Menu titik tiga (F3b, blueprint bagian 14): 5 grup dipisah hairline, baris 44dp, tinggi maks 72%
 * layar, di-anchor kanan atas. Item yang fiturnya belum ada tampil redup dan menyala bertahap
 * sesuai fase (Buka, Muat ulang, Aksi file aktif sejak F4).
 */
internal fun menuGroups(session: CodeSession, ctx: Context, onDismiss: () -> Unit): List<List<MenuEntry>> { // [CODE-PALETTE] dipakai menu dan Palet Perintah
    val tab = session.active
    val rev = tab?.revision ?: 0
    val ed = tab?.editor
    val hasText = rev >= 0 && (ed?.text?.length ?: 0) > 0

    return listOf(
        listOf(
            MenuEntry("Palet perintah", enabled = true, icon = MenuIcon.Command) { session.paletteOpen = true; onDismiss() }
        ),
        listOf(
            MenuEntry("Baru", enabled = true, icon = MenuIcon.New) { session.newTab(); onDismiss() },
            MenuEntry("Scratch baru", enabled = true, icon = MenuIcon.New) { session.scratchOpen = true; onDismiss() }, // [CODE-TEMPLATE]
            MenuEntry("Simpan sebagai", enabled = tab != null && !tab.loading, icon = MenuIcon.SaveAs) {
                session.saveAsActive()
                onDismiss()
            },
            MenuEntry("Buka", enabled = true, icon = MenuIcon.Open) {
                session.picker = PickerRequest(PickMode.File, session.defaultOpenDir(), "Buka berkas") { session.openFile(it) }
                onDismiss()
            },
            MenuEntry("Muat ulang", enabled = tab?.file != null && !tab.loading, icon = MenuIcon.Reload) {
                session.reloadActive()
                session.dir.refresh()
                onDismiss()
            },
            MenuEntry("Riwayat versi", enabled = tab?.file != null && !tab.loading && session.history != null, icon = MenuIcon.History) { session.historyOpen = true; onDismiss() }, // [CODE-HISTORY]
            MenuEntry("Aksi file", enabled = tab?.file != null && !session.isLibraryFile(tab.file), icon = MenuIcon.Actions) { // [CODE-DEBLIB]
                tab?.file?.let { session.fileDialog = FileDialog.Menu(it) }
                onDismiss()
            }
        ),
        listOf(
            MenuEntry("Cari", enabled = tab != null, icon = MenuIcon.Find) { session.openFind(); onDismiss() },
            MenuEntry("Cari di berkas", enabled = true, icon = MenuIcon.FindFiles) { session.findFilesOpen = true; onDismiss() }, // [CODE-SEARCH]
            MenuEntry("Urungkan ganti di berkas", enabled = session.lastReplace != null, icon = MenuIcon.Reload) { session.undoLastReplace(); onDismiss() }, // [CODE-REPLACE]
            MenuEntry("Struktur berkas", enabled = tab != null, icon = MenuIcon.Outline) { session.outlineOpen = true; onDismiss() },
            MenuEntry("Pergi ke simbol", enabled = true, icon = MenuIcon.Symbol) { session.symbolOpen = true; onDismiss() }, // [CODE-SYMBOL]
            MenuEntry("Pergi ke definisi", enabled = session.liveEnabled && tab?.file != null && !tab.loading && !session.definitionBusy, icon = MenuIcon.Symbol) { session.goToDefinition(); onDismiss() }, // [CODE-LSP6]
            MenuEntry("Kembali ke posisi sebelumnya", enabled = session.backDepth > 0, icon = MenuIcon.Back) { session.jumpBack(); onDismiss() }, // [CODE-BACK]
            MenuEntry("Pergi ke baris", enabled = tab != null, icon = MenuIcon.GoToLine) { session.gotoOpen = true; onDismiss() },
            MenuEntry("Informasi berkas", enabled = tab != null, icon = MenuIcon.Stats) { session.infoOpen = true; onDismiss() } // [CODE-INFO]
        ),
        listOf(
            MenuEntry("Live Template", enabled = tab != null && !tab.loading && !tab.readOnly, icon = MenuIcon.Snippet) { session.templatesOpen = true; onDismiss() },
            MenuEntry("Format kode", enabled = hasText && tab != null && !tab.loading && !tab.readOnly && !session.format.busy, icon = MenuIcon.Format) { session.formatActive(); onDismiss() }, // [CODE-FORMAT]
            MenuEntry("Format seleksi", enabled = hasText && tab != null && !tab.loading && !tab.readOnly && !session.format.busy && ed?.cursor?.isSelected == true, icon = MenuIcon.Format) { session.formatSelection(); onDismiss() },
            MenuEntry("Periksa kode", enabled = hasText && tab != null && !tab.loading && !session.checker.busy, icon = MenuIcon.Check) { session.checkActive(); onDismiss() }, // [CODE-CHECK]
            MenuEntry("Daftar masalah", enabled = tab != null && session.problems?.tabId == tab.id, icon = MenuIcon.Check) { session.problemsOpen = true; onDismiss() },
            MenuEntry("Format saat simpan", enabled = session.runStore != null, checked = session.formatOnSave, icon = MenuIcon.Format) { session.toggleFormatOnSave() },
            MenuEntry("Periksa saat simpan", enabled = session.runStore != null, checked = session.checkOnSave, icon = MenuIcon.Check) { session.toggleCheckOnSave() }, // [CODE-CHECK-SAVE]
            MenuEntry("Python langsung (diagnostik + saran kode)", enabled = session.runStore != null && session.lspExecutor != null, checked = session.liveEnabled, icon = MenuIcon.Check) { session.toggleLive() }, // [CODE-LSP3]
            MenuEntry("Bagikan", enabled = hasText, icon = MenuIcon.Share) {
                val text = ed?.text?.toString().orEmpty()
                if (text.length > MAX_SHARE_CHARS) {
                    Toast.makeText(ctx, "Teks terlalu besar untuk dibagikan", Toast.LENGTH_SHORT).show()
                } else {
                    shareText(ctx, text)
                }
                onDismiss()
            },
            MenuEntry("Kirim ke Script Execution", enabled = hasText && session.scriptSink != null, icon = MenuIcon.Script) {
                session.sendToScripts()
                onDismiss()
            },
            MenuEntry("Sisipkan ke Terminal", enabled = hasText && session.terminalSink != null, icon = MenuIcon.Insert) {
                session.sendToTerminal(false)
                onDismiss()
            },
            MenuEntry("Jalankan di Terminal", enabled = hasText && session.terminalSink != null, icon = MenuIcon.Run, danger = true) {
                if (session.runLang() != null) session.runInTerminalForLang() else session.sendToTerminal(true) // [CODE-PYRUN2]
                onDismiss()
            },
            MenuEntry("Urungkan jalankan Terminal", enabled = session.lastTerminalRun != null, icon = MenuIcon.Reload) { session.undoLastTerminalRun(); onDismiss() }, // [CODE-PYRUN2]
            MenuEntry("Dialog jalankan: ${if (session.runAsk()) "tanya dulu" else "langsung"}", enabled = session.runStore != null && session.runLang() != null, icon = MenuIcon.Script) { session.toggleRunAsk(); onDismiss() }, // [CODE-PYRUN2]
            MenuEntry("Simpan sebagai snippet", enabled = hasText && session.snippetSink != null, icon = MenuIcon.Snippet) {
                session.saveSelectionAsSnippet()
                onDismiss()
            },
        ),
        listOf(
            MenuEntry("Font editor: ${session.editorFont.label}, ${session.editorFontSp}sp", enabled = true, icon = MenuIcon.FontSize) { session.fontOpen = true; onDismiss() }, // [CODE-FONT]
            MenuEntry("Tema kode: ${MolinaTextMate.themeOf(session.editorTheme).label}", enabled = true, icon = MenuIcon.Syntax) { session.themeOpen = true; onDismiss() }, // [CODE-TM]
            MenuEntry("Akhir baris: ${tab?.eol?.label ?: "LF"}", enabled = tab != null && !tab.loading, icon = MenuIcon.Eol) {
                tab?.let {
                    it.eol = it.eol.other()
                    it.recomputeDirty()
                    session.scheduleDraft()
                }
            },
            MenuEntry("Pembungkus kata", enabled = tab != null, checked = tab?.wordWrap ?: true, icon = MenuIcon.Wrap) {
                tab?.let {
                    it.wordWrap = !it.wordWrap
                    it.editor?.setWordwrap(it.wordWrap)
                }
            },
            MenuEntry("Hanya baca", enabled = tab != null && !session.isLibraryFile(tab.file), checked = tab?.readOnly ?: false, icon = MenuIcon.ReadOnly) {
                tab?.let {
                    it.readOnly = !it.readOnly
                    it.editor?.setEditable(!it.readOnly && !it.loading)
                }
            }
        )
    ) // [CODE-STATUS] Statistik dipindah ke bilah status
}

@Composable
internal fun OverflowMenu(session: CodeSession, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val groups = menuGroups(session, ctx, onDismiss)
    val offsetY = with(LocalDensity.current) { CodeDimens.HeaderHeight.roundToPx() }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * CodeDimens.MenuMaxHeightFraction).dp
    val shape = RoundedCornerShape(CodeDimens.MenuCorner)

    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, offsetY),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Box(Modifier.padding(8.dp)) {
            Column(
                Modifier
                    .shadow(CodeDimens.MenuElevation, shape)
                    .clip(shape)
                    .background(CodeColors.SurfaceMenu)
                    .border(CodeDimens.Hairline, CodeColors.Hairline, shape)
                    .widthIn(min = CodeDimens.MenuMinWidth, max = CodeDimens.MenuMaxWidth)
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                groups.forEachIndexed { index, group ->
                    if (index > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .height(CodeDimens.Hairline)
                                .background(CodeColors.Hairline)
                        )
                    }
                    group.forEach { MenuRow(it) }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(e: MenuEntry) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(CodeDimens.MenuRowHeight)
            .clickable(enabled = e.enabled, onClick = e.onClick)
            .padding(horizontal = 16.dp)
            .alpha(if (e.enabled) 1f else CodeColors.DisabledAlpha),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val rowColor = if (e.danger) CodeColors.MenuDanger else CodeColors.MenuText // [MENU-WHITE]
        val ic = e.icon // [CODE-MENUICON]
        if (ic != null) Canvas(Modifier.size(CodeDimens.MenuIconSize)) { drawMenuIcon(ic, rowColor) }
        else Spacer(Modifier.width(CodeDimens.MenuIconSize))
        Spacer(Modifier.width(12.dp))
        BasicText(
            text = e.label,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = rowColor, fontSize = CodeDimens.MenuTextSize)
        )
        e.checked?.let {
            Spacer(Modifier.width(12.dp))
            CheckMark(it)
        }
    }
}

/** Kotak centang: terisi emas dengan centang hitam bila aktif; border champagne 50% bila tidak. */
@Composable
internal fun CheckMark(on: Boolean) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier
            .size(CodeDimens.MenuIconSize)
            .background(if (on) CodeColors.Gold else Color.Transparent, shape)
            .border(
                1.5.dp,
                if (on) CodeColors.Gold else CodeColors.IconDefault.copy(alpha = 0.5f),
                shape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (on) BasicText("\u2713", style = TextStyle(color = Color.Black, fontSize = 12.sp))
    }
}

internal fun shareText(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(send, "Bagikan").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
