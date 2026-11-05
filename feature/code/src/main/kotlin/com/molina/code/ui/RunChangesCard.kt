package com.molina.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.molina.code.guard.RunReport

// [CODE-RUNREPORT] Kartu "Perubahan skrip" di bawah status Keluaran + dialog konfirmasi Urungkan.

/** Pindaian sebelum skrip ([snap], berisi cadangan isi) dan hasil perbandingannya ([report]). */
internal class RunChanges(val snap: RunReport.Snapshot, val report: RunReport.Report)

private const val MAX_ROWS = 100

@Composable
internal fun RunChangesCard(session: CodeSession) {
    val c = session.runChanges ?: return
    if (session.run.running) return
    val rep = c.report
    val expanded = remember(c) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(CodeColors.Surface1)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(CodeDimens.PaneHeaderHeight)
                .clickable { expanded.value = !expanded.value }
                .padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                text = (if (expanded.value) "\u25B4 " else "\u25BE ") + "Perubahan: ${rep.changes.size} berkas",
                maxLines = 1,
                style = TextStyle(color = CodeColors.TextPrimary, fontSize = CodeDimens.StatusTextSize)
            )
            if (rep.problems > 0) {
                BasicText(
                    text = " \u00B7 ${rep.problems} peringatan",
                    maxLines = 1,
                    style = TextStyle(color = CodeColors.Warning, fontSize = CodeDimens.StatusTextSize)
                )
            }
            Spacer(Modifier.weight(1f))
            if (rep.undoableCount > 0) CardAction("URUNGKAN", CodeColors.Danger) { session.undoRunOpen = true }
            CardAction("TUTUP", CodeColors.IconDim) { session.dismissRunChanges() }
        }
        if (expanded.value) {
            Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                for (ch in rep.changes.take(MAX_ROWS)) ChangeRow(session, ch)
                if (rep.changes.size > MAX_ROWS) {
                    BasicText(
                        text = "dan ${rep.changes.size - MAX_ROWS} berkas lain",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = TextStyle(color = CodeColors.TextSecondary, fontSize = CodeDimens.StatusTextSize)
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(CodeDimens.Hairline).background(CodeColors.Hairline))
    }
}

@Composable
private fun CardAction(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxHeight().clickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(text = label, maxLines = 1, style = TextStyle(color = color, fontSize = CodeDimens.PaneLabelSize))
    }
}

@Composable
private fun ChangeRow(session: CodeSession, ch: RunReport.Change) {
    val (letter, color) = when (ch.kind) {
        RunReport.Kind.ADDED -> "A" to CodeColors.OutputOk
        RunReport.Kind.MODIFIED -> "M" to CodeColors.Warning
        RunReport.Kind.DELETED -> "D" to CodeColors.Danger
    }
    Row(
        Modifier.fillMaxWidth().clickable { session.openRunChange(ch.path) }.padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        BasicText(
            text = letter,
            modifier = Modifier.width(18.dp),
            style = TextStyle(color = color, fontSize = CodeDimens.PaneMonoSize, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        )
        Column(Modifier.weight(1f)) {
            BasicText(
                text = ch.path,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = CodeColors.TextPrimary, fontSize = CodeDimens.PaneMonoSize, fontFamily = FontFamily.Monospace)
            )
            if (ch.issues.isNotEmpty()) {
                BasicText(
                    text = "\u26A0 " + ch.issues.joinToString(", ") { it.label },
                    style = TextStyle(color = CodeColors.Warning, fontSize = 11.sp)
                )
            }
            if (!ch.undoable) {
                BasicText(
                    text = "tidak bisa diurungkan (terlalu besar atau biner)",
                    style = TextStyle(color = CodeColors.TextSecondary, fontSize = 11.sp)
                )
            }
        }
        if (ch.added + ch.removed > 0) {
            Spacer(Modifier.width(8.dp))
            BasicText(
                text = "+${ch.added} \u2212${ch.removed}",
                maxLines = 1,
                style = TextStyle(color = CodeColors.TextSecondary, fontSize = CodeDimens.PaneMonoSize, fontFamily = FontFamily.Monospace)
            )
        }
    }
}

/** Konfirmasi Urungkan hasil skrip; Batal adalah pilihan bawaan (aman). */
@Composable
internal fun UndoRunDialog(session: CodeSession) {
    val c = session.runChanges ?: return
    val rep = c.report
    val restore = rep.changes.count { it.undoable && it.kind != RunReport.Kind.ADDED }
    val remove = rep.changes.count { it.undoable && it.kind == RunReport.Kind.ADDED }
    val keep = rep.changes.count { !it.undoable }
    val shape = RoundedCornerShape(9.dp)
    Dialog(onDismissRequest = { session.undoRunOpen = false }) {
        Column(Modifier.widthIn(max = 340.dp).win7DialogFrame(shape)) {
            Win7DialogTitleBar("Molina Code") { session.undoRunOpen = false }
            Column(Modifier.padding(16.dp)) {
                BasicText(
                    text = "Urungkan hasil skrip?",
                    style = TextStyle(color = Win7Code.Ink, fontSize = 16.sp)
                )
                Spacer(Modifier.height(8.dp))
                BasicText(
                    text = "\u2022 $restore berkas dikembalikan ke isi sebelum skrip\n" +
                        "\u2022 $remove berkas baru dihapus" +
                        (if (keep > 0) "\n\u2022 $keep berkas tidak bisa diurungkan dan dibiarkan" else ""),
                    style = TextStyle(color = CodeColors.TextPrimary, fontSize = 14.sp)
                )
                Spacer(Modifier.height(6.dp))
                BasicText(
                    text = "Berkas yang diubah lagi setelah skrip selesai dilewati, tidak ditimpa. " +
                        "Isi sebelum skrip juga tercatat di Riwayat (\"Sebelum skrip\").",
                    style = TextStyle(color = CodeColors.TextSecondary, fontSize = 12.sp)
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    Win7DialogButton("Urungkan", { session.undoRunConfirm() }, accent = CodeColors.Danger)
                    Win7DialogButton("Batal", { session.undoRunOpen = false }, isDefault = true)
                }
            }
        }
    }
}
