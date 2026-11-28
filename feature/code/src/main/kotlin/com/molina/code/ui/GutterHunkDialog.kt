package com.molina.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.molina.code.history.DiffLine
import com.molina.code.history.DiffOp
import com.molina.code.history.GutterHunk
import com.molina.code.history.HunkRevert

/** [CODE-HISTORY8] Hasil ketukan gutter: blok perubahan [hunk] pada [tab], lengkap dengan hasil diff ([lines]) dan penanda kata ([marks]). */
internal class GutterTap(val tab: EditorTab, val hunk: GutterHunk, val lines: List<DiffLine>, val marks: Map<DiffLine, List<IntRange>>)

private const val GUTTER_MAX_ROWS = 40

/** [CODE-HISTORY8] Popup "awal -> sekarang" untuk satu blok perubahan di editor, dengan tombol Urungkan blok (satu langkah undo). */
@Composable
internal fun GutterHunkDialog(session: CodeSession, tap: GutterTap, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    val tab = tap.tab
    val oldRows = remember(tap) { tap.lines.filterByRun(tap.hunk.index, DiffOp.DEL) }
    val newRows = remember(tap) { tap.lines.filterByRun(tap.hunk.index, DiffOp.ADD) }
    val canRevert = !tab.readOnly && !tab.loading && tab.editor != null
    val title = if (tap.hunk.pureDelete) "Dihapus sebelum baris ${tap.hunk.newFirst + 1}" else "Perubahan di baris ${tap.hunk.lineLabel()}"
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(max = 340.dp).win7DialogFrame(shape)) {
            Win7DialogTitleBar("Molina Code", onDismiss)
            Column(Modifier.padding(16.dp)) {
                BasicText(text = title, style = TextStyle(color = Win7Code.Ink, fontSize = 16.sp))
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    GutterLines("Awal", oldRows, false, tap.marks, "(tidak ada, baris baru)")
                    Spacer(Modifier.height(8.dp))
                    GutterLines("Sekarang", newRows, true, tap.marks, "(dihapus)")
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    Win7DialogButton("Tutup", onDismiss)
                    if (canRevert) {
                        Win7DialogButton("Urungkan blok", {
                            val cur = tab.editor?.text?.toString()
                            val out = if (cur == null) null else HunkRevert.revertAt(cur, tap.lines, tap.hunk.index)
                            if (out == null) {
                                session.toast("Blok ini sudah berubah di editor, tidak bisa diurungkan otomatis")
                            } else {
                                session.applyHunkRevert(tab, out)
                            }
                            onDismiss()
                        }, isDefault = true)
                    }
                }
            }
        }
    }
}

/** Baris diff milik blok ke-[index] (urutan [HunkRevert.runs]) berjenis [op]. */
private fun List<DiffLine>.filterByRun(index: Int, op: DiffOp): List<DiffLine> {
    val run = HunkRevert.runs(this).getOrNull(index) ?: return emptyList()
    return run.map { this[it] }.filter { it.op == op }
}

@Composable
private fun GutterLines(title: String, rows: List<DiffLine>, add: Boolean, marks: Map<DiffLine, List<IntRange>>, empty: String) {
    val base = if (add) CodeColors.Clean else CodeColors.Danger
    BasicText(text = title, style = TextStyle(color = CodeColors.TextSecondary, fontSize = 11.sp))
    Spacer(Modifier.height(3.dp))
    Column(Modifier.fillMaxWidth().background(base.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (rows.isEmpty()) {
            BasicText(text = empty, style = TextStyle(color = CodeColors.SecondaryNeutral, fontSize = 12.sp))
        } else {
            for (ln in rows.take(GUTTER_MAX_ROWS)) {
                val (txt, rel) = com.molina.code.history.WordDiff.window(ln.text, marks[ln].orEmpty())
                val shown = buildAnnotatedString {
                    append(if (txt.isEmpty()) " " else txt)
                    for (r in rel) addStyle(SpanStyle(background = base.copy(alpha = 0.60f), fontWeight = FontWeight.Bold), r.first, r.last + 1)
                }
                BasicText(
                    text = shown,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = CodeColors.TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                )
            }
            if (rows.size > GUTTER_MAX_ROWS) {
                BasicText(
                    text = "\u22EF " + (rows.size - GUTTER_MAX_ROWS) + " baris lagi",
                    style = TextStyle(color = CodeColors.SecondaryNeutral, fontSize = 11.sp)
                )
            }
        }
    }
}
