package com.molina.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures // [EXT-DIFF6]
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
import androidx.compose.runtime.LaunchedEffect // [EXT-DIFF4]
import androidx.compose.runtime.mutableStateMapOf // [EXT-DIFF3]
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput // [EXT-DIFF6]
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.molina.code.guard.ChangeFeed
import com.molina.code.guard.ProjectLog
import com.molina.code.history.HistoryKind // [EXT-DIFF]
import com.molina.code.history.RestorePlan // [EXT-DIFF3]
import com.molina.code.history.ShadowPolicy // [EXT-DIFF4]
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// [CODE-FEED] Dialog "Notifikasi perubahan": daftar peristiwa berkas berubah di folder mana pun (skrip dari Code atau lewat
// nano), terbaru dulu. Titik hijau = belum dibaca. Ketuk butir = buka/tutup daftar berkasnya; ketuk berkas = buka di editor.

/** Potret feed saat dialog dibuka (tidak menyegarkan sendiri; buka lagi untuk memperbarui). */
internal class ChangeFeedView(val items: List<ChangeFeed.Item>)

@Composable
internal fun ChangeFeedDialog(session: CodeSession, view: ChangeFeedView, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(CodeDimens.DialogCorner)
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    val open = remember(view) { mutableStateOf(-1) }
    val acts = remember(view) { mutableStateMapOf<String, Int>() } // [EXT-DIFF3] status tombol per berkas: 0 Pulihkan, 1 Yakin?, 2 Urungkan, 3 sibuk
    val fmt = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id", "ID")) }
    val confirmClear = remember { mutableStateOf(false) } // [EXT-DIFF4]
    LaunchedEffect(Unit) { session.refreshShadowUsage() }
    val preview = remember(view) { mutableStateOf<FeedPreview?>(null) } // [EXT-DIFF6] pratinjau pemulihan yang sedang dibuka
    val bulkConfirm = remember(view) { mutableStateOf(-1) } // [EXT-DIFF5] butir yang menunggu konfirmasi "Pulihkan semua"
    LaunchedEffect(view) { session.findUndoable(view.items) { keys -> for (k in keys) acts[k] = 2 } } // tombol Urungkan tahan restart
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).win7DialogFrame(shape)) {
            preview.value?.let { p -> // [EXT-DIFF6]
                RestorePreviewDialog(session, p.pv, onRestore = {
                    preview.value = null
                    acts[p.key] = 3
                    session.restoreFeedFile(p.dir, p.c, p.time) { ok -> acts[p.key] = if (ok) 2 else 0 }
                }, onDismiss = { preview.value = null })
            }
            BasicText(
                text = "Notifikasi perubahan",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                style = TextStyle(color = CodeColors.IconDefault, fontSize = CodeDimens.DialogTitleSize)
            )
            BasicText(
                text = if (view.items.isEmpty()) "Belum ada perubahan" else "${view.items.size} peristiwa \u00B7 terbaru dulu",
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                style = TextStyle(color = CodeColors.SecondaryNeutral, fontSize = 12.sp)
            )
            Box(Modifier.fillMaxWidth().height(CodeDimens.Hairline).background(CodeColors.Hairline))
            if (view.items.isEmpty()) {
                BasicText(
                    text = "Saat skrip mengubah, menambah, atau menghapus berkas di sebuah folder (dijalankan dari Molina Code " +
                        "atau lewat Alt+R di nano), ringkasannya muncul di sini.",
                    modifier = Modifier.padding(16.dp),
                    style = TextStyle(color = CodeColors.TextSecondary, fontSize = 13.sp)
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxH)) {
                    itemsIndexed(view.items) { idx, item ->
                        val expanded = open.value == idx
                        val e = item.entry
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable { open.value = if (expanded) -1 else idx }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            BasicText(
                                text = buildAnnotatedString {
                                    if (item.unread) withStyle(SpanStyle(color = CodeColors.Brand)) { append("\u25CF ") }
                                    append(ChangeFeed.headline(item))
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(color = CodeColors.TextPrimary, fontSize = 13.sp)
                            )
                            val via = ChangeFeed.sourceLabel(e.source)
                            BasicText(
                                text = buildAnnotatedString {
                                    append(fmt.format(Date(e.time)))
                                    if (via.isNotEmpty()) { append(" \u00B7 "); withStyle(SpanStyle(color = feedSourceColor(e.source))) { append(via) } }
                                    append(" \u00B7 ${item.folder}")
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(color = CodeColors.TextSecondary, fontSize = 11.sp)
                            )
                            if (expanded) {
                                Spacer(Modifier.height(4.dp))
                                for (c in e.files) FeedFileRow(c, feedAction(acts, idx, c, e.source), { feedActionClick(session, acts, idx, item.folder, c, e.time) },
                                    feedLongClick(session, acts, idx, item.folder, c, e.time, e.source) { preview.value = it }) {
                                    val f = File(File(item.folder), c.path)
                                    if (c.kind != 'D' && f.isFile) {
                                        onDismiss()
                                        val ext = e.source == ChangeFeed.SRC_EXTERNAL // [EXT-DIFF]
                                        if (c.kind == 'M' && (!ext || c.added + c.removed > 0)) {
                                            session.openFeedFileDiff(f, if (ext) HistoryKind.EXTERNAL_AFTER else HistoryKind.AFTER_SCRIPT)
                                        } else {
                                            session.openFile(f) // [CODE-FEED2]
                                        }
                                    } else {
                                        session.toast("Berkas tidak ada lagi: ${c.path}")
                                    }
                                }
                                if (e.source == ChangeFeed.SRC_EXTERNAL) { // [EXT-DIFF5]
                                    val todo = e.files.filter { RestorePlan.canOffer(true, it.kind, it.added, it.removed) && (acts["$idx|${it.path}"] ?: 0) == 0 }
                                    if (todo.isNotEmpty()) {
                                        BasicText(
                                            text = "Ketuk lama pada nama berkas untuk pratinjau pemulihan",
                                            modifier = Modifier.padding(top = 2.dp),
                                            style = TextStyle(color = CodeColors.SecondaryNeutral, fontSize = 11.sp)
                                        )
                                    }
                                    if (todo.size >= 2) {
                                        BasicText(
                                            text = if (bulkConfirm.value == idx) "Yakin pulihkan ${todo.size} berkas?" else "Pulihkan semua (${todo.size})",
                                            modifier = Modifier.padding(vertical = 6.dp).clickable {
                                                if (bulkConfirm.value == idx) {
                                                    bulkConfirm.value = -1
                                                    for (t in todo) acts["$idx|${t.path}"] = 3
                                                    session.restoreFeedBatch(File(item.folder), todo, e.time) { res ->
                                                        todo.forEachIndexed { i, t -> acts["$idx|${t.path}"] = if (res.getOrNull(i) == true) 2 else 0 }
                                                    }
                                                } else bulkConfirm.value = idx
                                            },
                                            style = TextStyle(color = CodeColors.Brand, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        )
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
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { // [EXT-DIFF4] pengaturan pantau
                ChoiceChip(
                    "Pantau perubahan otomatis: ${if (session.watchAuto) "aktif" else "mati"}",
                    session.watchAuto, Modifier.fillMaxWidth()
                ) { session.toggleWatchAuto() }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        text = ShadowPolicy.usageLine(session.shadowBytes),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        style = TextStyle(color = CodeColors.TextSecondary, fontSize = 12.sp)
                    )
                    BasicText(
                        text = if (confirmClear.value) "Yakin?" else "Bersihkan",
                        maxLines = 1,
                        modifier = Modifier.clickable {
                            if (confirmClear.value) { confirmClear.value = false; session.clearShadow() } else confirmClear.value = true
                        }.padding(start = 10.dp),
                        style = TextStyle(color = CodeColors.Brand, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    )
                }
                Spacer(Modifier.height(4.dp))
                BasicText(
                    text = ShadowPolicy.EXCLUSION_NOTE,
                    style = TextStyle(color = CodeColors.SecondaryNeutral, fontSize = 11.sp)
                )
            }
            Box(Modifier.fillMaxWidth().height(CodeDimens.Hairline).background(CodeColors.Hairline))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (view.items.isNotEmpty()) Win7DialogButton("Hapus semua", { session.clearChangeFeed() })
                Win7DialogButton("Tutup", onDismiss, isDefault = true)
            }
        }
    }
}

@Composable
private fun FeedFileRow(c: ProjectLog.FileChange, action: String?, onAction: () -> Unit, onLongClick: (() -> Unit)?, onClick: () -> Unit) {
    val (letter, color) = when (c.kind) {
        'A' -> "A" to CodeColors.OutputOk
        'D' -> "D" to CodeColors.Danger
        else -> "M" to CodeColors.Warning
    }
    Row(
        Modifier.fillMaxWidth()
            .pointerInput(onClick, onLongClick) { detectTapGestures(onLongPress = { onLongClick?.invoke() }, onTap = { onClick() }) } // [EXT-DIFF6] ketuk lama = pratinjau
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
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
        if (action != null) { // [EXT-DIFF3]
            Spacer(Modifier.width(10.dp))
            BasicText(
                text = action,
                maxLines = 1,
                modifier = Modifier.clickable(onClick = onAction),
                style = TextStyle(color = CodeColors.Brand, fontSize = CodeDimens.PaneMonoSize, fontWeight = FontWeight.Bold)
            )
        }
    }
}

/** [EXT-DIFF6] Aksi ketuk lama pada baris berkas: pratinjau pemulihan, hanya bila berkas sedang bisa dipulihkan (belum dipulihkan/diproses). */
private fun feedLongClick(
    session: CodeSession, acts: Map<String, Int>, idx: Int, folder: String, c: ProjectLog.FileChange, time: Long, source: String,
    onReady: (FeedPreview) -> Unit
): (() -> Unit)? {
    if (feedAction(acts, idx, c, source) == null) return null
    val key = "$idx|${c.path}"
    if ((acts[key] ?: 0) !in 0..1) return null
    val dir = File(folder)
    return { session.loadRestorePreview(dir, c, time) { pv -> if (pv != null) onReady(FeedPreview(dir, c, time, key, pv)) } }
}

/** [EXT-DIFF5] Warna label sumber butir: dari luar = amber (peringatan), pemulihan = hijau merek, lainnya netral. */
private fun feedSourceColor(source: String): androidx.compose.ui.graphics.Color = when (source) {
    ChangeFeed.SRC_EXTERNAL -> CodeColors.Warning
    ChangeFeed.SRC_RESTORE -> CodeColors.Brand
    else -> CodeColors.TextSecondary
}

/** [EXT-DIFF3] Label tombol pemulihan untuk satu berkas pada butir [idx]; null bila tak ditawarkan. */
private fun feedAction(acts: Map<String, Int>, idx: Int, c: ProjectLog.FileChange, source: String): String? {
    if (!RestorePlan.canOffer(source == ChangeFeed.SRC_EXTERNAL, c.kind, c.added, c.removed)) return null
    return when (acts["$idx|${c.path}"] ?: 0) {
        1 -> "Yakin?"
        2 -> "Urungkan"
        3 -> "\u2026"
        else -> "Pulihkan"
    }
}

/** [EXT-DIFF3] Ketuk pertama minta konfirmasi, ketuk kedua memulihkan, lalu tombol jadi Urungkan. */
private fun feedActionClick(session: CodeSession, acts: MutableMap<String, Int>, idx: Int, folder: String, c: ProjectLog.FileChange, time: Long) {
    val key = "$idx|${c.path}"
    val dir = File(folder)
    when (acts[key] ?: 0) {
        0 -> acts[key] = 1
        1 -> { acts[key] = 3; session.restoreFeedFile(dir, c, time) { ok -> acts[key] = if (ok) 2 else 0 } }
        2 -> { acts[key] = 3; session.undoFeedRestore(dir, c) { ok -> acts[key] = if (ok) 0 else 2 } }
    }
}
