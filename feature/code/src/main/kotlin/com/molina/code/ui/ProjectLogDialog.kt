package com.molina.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.molina.code.guard.ProjectCommit
import com.molina.code.guard.ProjectLog
import com.molina.code.guard.ProjectStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// [CODE-PROJLOG] Dialog "Riwayat proyek": daftar peristiwa (skrip jalan, urungkan) di proyek bertanda `.molina`,
// terbaru dulu. Ketuk entri = buka/tutup daftar berkasnya; ketuk berkas = buka di editor.

/** Potret log proyek [root] saat dialog dibuka (tidak menyegarkan sendiri; buka lagi untuk memperbarui). */
// [CODE-STORE] snapshots = waktu commit yang punya manifest (isinya bisa dipulihkan)
internal class ProjectLogView(
    val root: File,
    val entries: List<ProjectLog.Entry>,
    val pending: Int? = null, // [CODE-COMMIT]
    val snapshots: Set<Long> = emptySet()
)

@Composable
internal fun ProjectLogDialog(session: CodeSession, view: ProjectLogView, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(CodeDimens.DialogCorner)
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    val open = remember(view) { mutableStateOf(-1) }
    val commitAsk = remember(view) { mutableStateOf(false) } // [CODE-COMMIT]
    val fileSel = remember(view) { mutableStateOf<Pair<ProjectLog.Entry, ProjectLog.FileChange>?>(null) } // [CODE-STORE]
    val fmt = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id", "ID")) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).win7DialogFrame(shape)) {
            BasicText(
                text = "Riwayat proyek: " + view.root.name.ifEmpty { view.root.path },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                style = TextStyle(color = CodeColors.IconDefault, fontSize = CodeDimens.DialogTitleSize)
            )
            BasicText(
                text = (if (view.entries.isEmpty()) "Belum ada catatan" else "${view.entries.size} catatan \u00B7 terbaru dulu") + pendingText(view.pending),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                style = TextStyle(color = CodeColors.SecondaryNeutral, fontSize = 12.sp)
            )
            Box(Modifier.fillMaxWidth().height(CodeDimens.Hairline).background(CodeColors.Hairline))
            if (view.entries.isEmpty()) {
                BasicText(
                    text = "Setiap kali skrip dijalankan dari Molina Code dan mengubah berkas di folder ini, " +
                        "ringkasannya dicatat di sini.",
                    modifier = Modifier.padding(16.dp),
                    style = TextStyle(color = CodeColors.TextSecondary, fontSize = 13.sp)
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxH)) {
                    itemsIndexed(view.entries) { idx, e ->
                        val expanded = open.value == idx
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable { open.value = if (expanded) -1 else idx }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            BasicText(
                                text = (if (expanded) "\u25B4 " else "\u25BE ") + e.message,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(color = CodeColors.TextPrimary, fontSize = 13.sp)
                            )
                            BasicText(
                                text = fmt.format(Date(e.time)) + sourceLabel(e.source) + summary(e),
                                maxLines = 1,
                                style = TextStyle(color = CodeColors.TextSecondary, fontSize = 11.sp)
                            )
                            if (expanded) {
                                Spacer(Modifier.height(4.dp))
                                for (c in e.files) FileRow(c) {
                                    val f = File(view.root, c.path)
                                    if (e.source == ProjectCommit.SRC && e.time in view.snapshots && c.kind != 'D') {
                                        fileSel.value = e to c // [CODE-STORE] buka atau kembalikan ke versi commit ini
                                    } else if (c.kind != 'D' && f.isFile) {
                                        onDismiss()
                                        session.openFile(f)
                                    } else {
                                        session.toast("Berkas tidak ada lagi: ${c.path}")
                                    }
                                }
                                if (e.omitted > 0) {
                                    BasicText(
                                        text = "dan ${e.omitted} berkas lain",
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        style = TextStyle(color = CodeColors.TextSecondary, fontSize = 11.sp)
                                    )
                                }
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(CodeDimens.Hairline).background(CodeColors.Hairline))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(CodeDimens.Hairline).background(CodeColors.Hairline))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Win7DialogButton("Commit baru", { commitAsk.value = true })
                Win7DialogButton("Tutup", onDismiss, isDefault = true)
                if (commitAsk.value) CommitDialog(session, view.root) { commitAsk.value = false }
                fileSel.value?.let { (en, ch) ->
                    ProjectFileDialog(
                        session, view.root, en.time, ch.path, fmt.format(Date(en.time)),
                        onOpen = {
                            val f = File(view.root, ch.path)
                            if (f.isFile) { onDismiss(); session.openFile(f) } else session.toast("Berkas tidak ada lagi: ${ch.path}")
                        },
                        onDismiss = { fileSel.value = null }
                    )
                }
            }
        }
    }
}

/** " \u00B7 3 berkas (+1 ~2 \u22120)"; entri tanpa berkas (catatan Urungkan) tidak menampilkan ringkasan. */
private fun summary(e: ProjectLog.Entry): String =
    if (e.fileCount == 0) ""
    else " \u00B7 ${e.fileCount} berkas" + if (e.omitted == 0) " (+${e.addedFiles} ~${e.modifiedFiles} \u2212${e.deletedFiles})" else ""

/** [CODE-STORE] Label sumber entri di baris waktu; catatan skrip tidak diberi label. */
private fun sourceLabel(src: String): String = when (src) {
    ProjectCommit.SRC -> " \u00B7 commit"
    ProjectStore.SRC_RESTORE -> " \u00B7 pulih"
    else -> ""
}

/** [CODE-COMMIT] Ringkasan jumlah berkas yang belum di-commit; kosong bila belum pernah commit. */
private fun pendingText(n: Int?): String = when {
    n == null -> ""
    n == 0 -> " \u00B7 tidak ada perubahan baru"
    else -> " \u00B7 $n berkas belum di-commit"
}

@Composable
private fun FileRow(c: ProjectLog.FileChange, onClick: () -> Unit) {
    val (letter, color) = when (c.kind) {
        'A' -> "A" to CodeColors.OutputOk
        'D' -> "D" to CodeColors.Danger
        else -> "M" to CodeColors.Warning
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        BasicText(
            text = letter,
            modifier = Modifier.width(18.dp),
            style = TextStyle(color = color, fontSize = CodeDimens.PaneMonoSize, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        )
        BasicText(
            text = c.path,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            style = TextStyle(color = CodeColors.TextPrimary, fontSize = CodeDimens.PaneMonoSize, fontFamily = FontFamily.Monospace)
        )
        if (c.added + c.removed > 0) {
            Spacer(Modifier.width(8.dp))
            BasicText(
                text = "+${c.added} \u2212${c.removed}",
                maxLines = 1,
                style = TextStyle(color = CodeColors.TextSecondary, fontSize = CodeDimens.PaneMonoSize, fontFamily = FontFamily.Monospace)
            )
        }
    }
}
