package com.molina.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.molina.code.guard.ProjectLog
import com.molina.code.history.LineDiff
import com.molina.code.history.RestorePlan
import com.molina.code.history.WordDiff
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// [EXT-DIFF6] Pratinjau pemulihan: perbedaan isi berkas SEKARANG (merah = akan dihapus) dengan versi yang akan dipasang
// (hijau = akan ditambah), dilihat sebelum konfirmasi Pulihkan. Berkas yang dibuat ulang (D) = semua baris hijau.

/** Bahan pratinjau: [current] = isi disk sekarang (null bila berkas tak ada), [target] = isi yang akan ditulis (LF). */
internal class RestorePreview(val file: File, val kind: Char, val current: String?, val target: String)

/** Pratinjau yang sedang dibuka di dialog notifikasi, beserta bahan untuk memulihkan dari sana. */
internal class FeedPreview(val dir: File, val c: ProjectLog.FileChange, val time: Long, val key: String, val pv: RestorePreview)

@Composable
internal fun RestorePreviewDialog(session: CodeSession, pv: RestorePreview, onRestore: () -> Unit, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(CodeDimens.DialogCorner)
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    val state by produceState<HistDiffState?>(null, pv) {
        value = withContext(Dispatchers.IO) {
            val r = LineDiff.diff(pv.current ?: "", pv.target)
            HistDiffState(pv.current, r, LineDiff.compact(r, 2), pv.target, WordDiff.mark(r.lines))
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).win7DialogFrame(shape)) {
            BasicText(
                text = "Pratinjau pemulihan",
                maxLines = 1,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
                style = TextStyle(color = CodeColors.IconDefault, fontSize = CodeDimens.DialogTitleSize)
            )
            BasicText(
                text = pv.file.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
                style = TextStyle(color = CodeColors.TextPrimary, fontSize = 13.sp)
            )
            val r = state?.result
            BasicText(
                text = RestorePlan.previewCaption(pv.kind) + " " +
                    (if (r == null) "Menghitung perbedaan\u2026" else "${r.removed} baris dihapus, ${r.added} baris ditambah."),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
                style = TextStyle(color = CodeColors.SecondaryNeutral, fontSize = 11.sp)
            )
            Box(Modifier.fillMaxWidth().height(CodeDimens.Hairline).background(CodeColors.Hairline))
            DiffBody(state, maxH, session.diffContrast)
            Box(Modifier.fillMaxWidth().height(CodeDimens.Hairline).background(CodeColors.Hairline))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Win7DialogButton("Batal", onDismiss)
                Win7DialogButton("Pulihkan", onRestore, isDefault = true)
            }
        }
    }
}
