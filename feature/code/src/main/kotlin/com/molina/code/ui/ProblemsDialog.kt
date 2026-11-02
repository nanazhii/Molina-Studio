package com.molina.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.molina.code.check.CheckFix
import com.molina.code.check.Severity

/**
 * [CODE-CHECK] Daftar masalah hasil "Periksa kode". Ketuk baris = lompat ke posisinya di editor. Daftar adalah
 * potret saat pemeriksaan; setelah teks diedit, jalankan Periksa kode lagi untuk menyegarkan.
 */
@Composable
internal fun ProblemsDialog(session: CodeSession, list: ProblemList, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(CodeDimens.DialogCorner)
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    val errors = list.items.count { it.severity == Severity.ERROR }
    val warns = list.items.count { it.severity == Severity.WARNING }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).win7DialogFrame(shape)) {
            BasicText(
                text = "Masalah: " + list.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                style = TextStyle(color = CodeColors.IconDefault, fontSize = CodeDimens.DialogTitleSize)
            )
            BasicText(
                text = list.label + " \u00B7 " + errors + " galat, " + warns + " peringatan, " + (list.items.size - errors - warns) + " info",
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 12.sp)
            )
            Box(Modifier.fillMaxWidth().height(CodeDimens.Hairline).background(CodeColors.Hairline))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxH)) {
                itemsIndexed(list.items) { _, d ->
                    val fixLabel = CheckFix.label(d) // [CODE-CHECK-FIX]
                    val tone = when (d.severity) {
                        Severity.ERROR -> CodeColors.Danger
                        Severity.WARNING -> CodeColors.Gold
                        Severity.INFO -> CodeColors.SecondaryChampagne
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                session.jumpToProblem(list.tabId, d.line, d.col)
                                onDismiss()
                            }
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(Modifier.padding(top = 5.dp).size(8.dp).background(tone, CircleShape))
                        Column(Modifier.weight(1f)) {
                            BasicText(
                                text = d.line.toString() + ":" + d.col + (d.code?.let { " \u00B7 $it" } ?: ""),
                                maxLines = 1,
                                style = TextStyle(color = tone, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            )
                            BasicText(
                                text = d.message,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(color = CodeColors.TextPrimary, fontSize = 13.sp)
                            )
                            if (fixLabel != null) {
                                BasicText(
                                    text = "Perbaikan: " + fixLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 11.sp)
                                )
                            }
                        }
                        if (fixLabel != null) {
                            Win7DialogButton("Perbaiki", {
                                onDismiss()
                                session.applyQuickFix(list, d)
                            })
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(CodeDimens.Hairline).background(CodeColors.Hairline))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Win7DialogButton("Hapus tanda", {
                    session.tabs.firstOrNull { it.id == list.tabId }?.let { session.clearProblems(it) }
                    onDismiss()
                })
                Win7DialogButton("Tutup", onDismiss, isDefault = true)
            }
        }
    }
}
