package com.molina.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.molina.code.guard.ProjectStore
import com.molina.code.history.DiffOp
import com.molina.code.history.LineDiff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// [CODE-STORE] Panel "Perubahan": diff satu berkas pada satu commit terhadap commit sebelumnya, dibaca dari blob lokal
// (`.molina/objects`) di thread IO. Hanya baca: tidak mengubah berkas proyek.

@Composable
internal fun ProjectDiffDialog(root: File, time: Long, path: String, kind: Char, whenText: String, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(CodeDimens.DialogCorner)
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    val diff by produceState<ProjectStore.FileDiff?>(null, root, time, path, kind) {
        value = withContext(Dispatchers.IO) {
            try {
                ProjectStore.diffAt(root, time, path, kind)
            } catch (e: Exception) {
                ProjectStore.FileDiff(ProjectStore.DiffStatus.NO_BLOB)
            }
        }
    }
    val res = diff?.result
    val rows = remember(res) { if (res == null) emptyList() else LineDiff.compact(res, 3) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).win7DialogFrame(shape)) {
            BasicText(
                text = path,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                style = TextStyle(color = CodeColors.IconDefault, fontSize = CodeDimens.DialogTitleSize)
            )
            BasicText(
                text = "Commit $whenText" + if (res != null) " \u00B7 +${res.added} \u2212${res.removed}" else "",
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                style = TextStyle(color = CodeColors.SecondaryNeutral, fontSize = 12.sp)
            )
            Box(Modifier.fillMaxWidth().height(CodeDimens.Hairline).background(CodeColors.Hairline))
            val d = diff
            when {
                d == null -> Note("Membaca\u2026")
                d.status == ProjectStore.DiffStatus.BINARY -> Note("Berkas biner: tidak ada tampilan baris.")
                d.status == ProjectStore.DiffStatus.NO_BASE -> Note("Versi sebelumnya sudah dibuang (riwayat dibatasi), jadi tidak ada pembanding.")
                d.status == ProjectStore.DiffStatus.NO_BLOB -> Note("Isi berkas ini tidak tersimpan (terlalu besar atau penyimpanan penuh).")
                res != null && !res.changed -> Note("Tidak ada perubahan isi.")
                else -> {
                    if (res != null && res.coarse) Note("Berkas sangat besar: bagian tengah ditampilkan diganti seluruhnya.")
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxH)) {
                        items(rows) { row ->
                            val ln = row.line
                            if (ln == null) {
                                BasicText(
                                    text = "\u22EF ${row.skipped} baris sama",
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                                    style = TextStyle(color = CodeColors.SecondaryNeutral.copy(alpha = 0.6f), fontSize = 11.sp)
                                )
                            } else {
                                val bg = when (ln.op) {
                                    DiffOp.ADD -> CodeColors.Clean.copy(alpha = 0.16f)
                                    DiffOp.DEL -> CodeColors.Danger.copy(alpha = 0.18f)
                                    DiffOp.SAME -> Color.Transparent
                                }
                                val sign = when (ln.op) { DiffOp.ADD -> "+"; DiffOp.DEL -> "\u2212"; DiffOp.SAME -> " " }
                                val no = if (ln.op == DiffOp.DEL) ln.oldNo else ln.newNo
                                Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp, vertical = 1.dp)) {
                                    BasicText(
                                        text = no.toString(),
                                        modifier = Modifier.width(40.dp),
                                        maxLines = 1,
                                        style = TextStyle(color = CodeColors.SecondaryNeutral, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    )
                                    BasicText(
                                        text = sign,
                                        modifier = Modifier.width(14.dp),
                                        maxLines = 1,
                                        style = TextStyle(color = CodeColors.TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                    )
                                    BasicText(
                                        text = ln.text,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis,
                                        style = TextStyle(color = CodeColors.TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                    )
                                }
                            }
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
                Win7DialogButton("Tutup", onDismiss, isDefault = true)
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    BasicText(
        text = text,
        modifier = Modifier.padding(16.dp),
        style = TextStyle(color = CodeColors.TextSecondary, fontSize = 13.sp)
    )
}
