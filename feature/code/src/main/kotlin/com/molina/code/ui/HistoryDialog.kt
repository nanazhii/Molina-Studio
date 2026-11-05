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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.molina.code.history.DiffLine
import com.molina.code.history.DiffOp
import com.molina.code.history.DiffRow
import com.molina.code.history.HistoryFormat
import com.molina.code.history.HistoryKind
import com.molina.code.history.HistoryStore
import com.molina.code.history.HistoryVersion
import com.molina.code.history.HunkRevert
import com.molina.code.history.LineDiff
import com.molina.code.history.VersionCompare
import com.molina.code.history.VersionDelta
import com.molina.code.history.WordDiff
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [CODE-HISTORY] Hasil membaca versi dan membandingkannya (dengan isi editor, atau dua versi: [new] terisi). */
private class HistDiffState(val old: String?, val result: LineDiff.Result?, val rows: List<DiffRow>, val new: String? = null,
    /** [CODE-HISTORY3] Rentang kata berubah per baris diff (kunci = objek baris). */
    val marks: Map<DiffLine, List<IntRange>> = emptyMap()
)

/**
 * [CODE-HISTORY] Riwayat versi lokal: daftar snapshot berkas tab aktif (dibuat tiap simpan, plus isi disk sebelum
 * ditimpa). Ketuk versi = lihat perbedaan dengan isi editor sekarang (merah: ada di versi itu, hijau: ada di isi
 * sekarang); "Kembalikan" memasang versi itu ke editor sebagai SATU langkah undo, tanpa menyimpan ke disk.
 *
 * [CODE-HISTORY2] Ketuk bintang = sematkan versi penting (tidak ikut dipangkas otomatis, maks [HistoryStore.MAX_PINS]).
 * "Bandingkan dua versi" = tandai dua versi lalu lihat perbedaan lama -> baru.
 */
@Composable
internal fun HistoryDialog(session: CodeSession, tab: EditorTab, onDismiss: () -> Unit) {
    val store = session.history
    val file = tab.file
    var versions by remember { mutableStateOf<List<HistoryVersion>?>(null) }
    var picked by remember { mutableStateOf<HistoryVersion?>(null) }
    var compareMode by remember { mutableStateOf(false) }
    var marked by remember { mutableStateOf<List<String>>(emptyList()) }
    var pair by remember { mutableStateOf<Pair<HistoryVersion, HistoryVersion>?>(null) }
    val now = remember { System.currentTimeMillis() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(file?.path) {
        versions = if (store == null || file == null) emptyList() else withContext(Dispatchers.IO) { store.list(file) }
        // [CODE-HISTORY6] dibuka dari kartu perubahan skrip: langsung tampilkan versi terbaru berjenis itu ("Sesudah skrip")
        val focus = session.historyFocus
        if (focus != null) {
            session.historyFocus = null
            if (picked == null && !compareMode) picked = VersionCompare.latestOfKind(versions.orEmpty(), focus)
        }
    }

    fun isPinned(v: HistoryVersion): Boolean = versions?.firstOrNull { it.name == v.name }?.pinned ?: v.pinned

    fun togglePin(v: HistoryVersion) {
        if (store == null || file == null) return
        val want = !isPinned(v)
        scope.launch {
            val ok = withContext(Dispatchers.IO) { store.setPin(file, v, want) }
            if (ok) {
                versions = withContext(Dispatchers.IO) { store.list(file) }
            } else {
                session.toast(
                    if (want) "Gagal menyematkan (maksimal " + HistoryStore.MAX_PINS + " versi disematkan per berkas)"
                    else "Gagal melepas sematan"
                )
            }
        }
    }

    fun onPickVersion(v: HistoryVersion) {
        if (!compareMode) { picked = v; return }
        val next = if (v.name in marked) marked - v.name else marked + v.name
        if (next.size < 2) { marked = next; return }
        val vs = versions.orEmpty()
        val x = vs.firstOrNull { it.name == next[0] }
        val y = vs.firstOrNull { it.name == next[1] }
        marked = emptyList()
        if (x != null && y != null) pair = if (x.time <= y.time) Pair(x, y) else Pair(y, x)
    }

    val shape = RoundedCornerShape(CodeDimens.DialogCorner)
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    Dialog(
        onDismissRequest = {
            if (pair != null) pair = null else if (picked != null) picked = null else onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).win7DialogFrame(shape)) {
            val v = picked
            val pr = pair
            if (store == null || file == null) {
                HistoryMessage("Riwayat versi tidak tersedia untuk tab ini (berkas belum disimpan).")
                HistoryButtons { Win7DialogButton("Tutup", onDismiss, isDefault = true) }
            } else if (pr != null) {
                HistoryComparePane(
                    session = session, tab = tab, store = store, file = file, a = pr.first, b = pr.second,
                    now = now, maxH = maxH, onBack = { pair = null }, onDone = onDismiss
                )
            } else if (v == null) {
                HistoryListPane(
                    store = store, file = file, versions = versions, now = now, maxH = maxH,
                    compareMode = compareMode, marked = marked,
                    onPick = { onPickVersion(it) }, onTogglePin = { togglePin(it) },
                    onToggleCompare = { compareMode = !compareMode; marked = emptyList() },
                    onCleared = { versions = emptyList(); compareMode = false; marked = emptyList() }, onClose = onDismiss
                )
            } else {
                HistoryDiffPane(
                    session = session, tab = tab, store = store, file = file, v = v, prev = VersionCompare.previous(versions.orEmpty(), v), pinned = isPinned(v), now = now, maxH = maxH,
                    onTogglePin = { togglePin(v) }, onBack = { picked = null }, onDone = onDismiss
                )
            }
        }
    }
}

@Composable
private fun HistoryMessage(text: String) {
    BasicText(
        text = text,
        modifier = Modifier.padding(16.dp),
        style = TextStyle(color = CodeColors.SecondaryNeutral, fontSize = 13.sp)
    )
}

@Composable
private fun HistoryButtons(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(CodeDimens.Hairline).background(CodeColors.Hairline))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

@Composable
private fun HistoryListPane(
    store: HistoryStore, file: File, versions: List<HistoryVersion>?, now: Long, maxH: Dp,
    compareMode: Boolean, marked: List<String>,
    onPick: (HistoryVersion) -> Unit, onTogglePin: (HistoryVersion) -> Unit, onToggleCompare: () -> Unit,
    onCleared: () -> Unit, onClose: () -> Unit
) {
    var confirmClear by remember { mutableStateOf(false) }
    // [CODE-HISTORY3] ringkasan perubahan tiap versi dibanding versi sebelumnya (-baris +baris), dihitung bertahap di thread IO
    val deltas by produceState<Map<String, String>>(emptyMap(), versions?.map { it.name }) {
        val vs = versions ?: return@produceState
        val acc = HashMap<String, String>()
        var cur: String? = if (vs.isEmpty()) null else withContext(Dispatchers.IO) { store.read(file, vs[0]) }
        for (i in vs.indices) {
            val older = vs.getOrNull(i + 1)
            val olderText = if (older == null) null else withContext(Dispatchers.IO) { store.read(file, older) }
            val from = cur
            acc[vs[i].name] = if (older == null) "versi pertama" else withContext(Dispatchers.IO) { VersionDelta.between(olderText, from) }
            cur = olderText
            if (i % 4 == 3 || i == vs.lastIndex) value = HashMap(acc)
        }
    }
    val scope = rememberCoroutineScope()
    BasicText(
        text = "Riwayat: " + file.name + (if (versions != null) " (" + versions.size + ")" else ""),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
        style = TextStyle(color = CodeColors.IconDefault, fontSize = CodeDimens.DialogTitleSize)
    )
    if (compareMode) {
        BasicText(
            text = "Pilih dua versi untuk dibandingkan (" + marked.size + "/2)",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
            style = TextStyle(color = CodeColors.Brand, fontSize = 12.sp)
        )
    }
    when {
        versions == null -> HistoryMessage("Membaca riwayat\u2026")
        versions.isEmpty() -> HistoryMessage("Belum ada versi. Riwayat dibuat setiap kali berkas disimpan.")
        else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxH)) {
            items(versions, key = { it.name }) { v ->
                val on = v.name in marked
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (on) CodeColors.Capsule else Color.Transparent)
                        .clickable { onPick(v) }
                        .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        BasicText(
                            text = (if (on) "\u2713 " else "") + HistoryFormat.time(v.time, now),
                            maxLines = 1,
                            style = TextStyle(color = if (on) CodeColors.Brand else CodeColors.IconDefault, fontSize = 14.sp)
                        )
                        BasicText(
                            text = v.lines.toString() + " baris \u00B7 " + HistoryFormat.size(v.chars.toLong()) +
                                (deltas[v.name]?.takeIf { it.isNotEmpty() }?.let { " \u00B7 " + it } ?: ""),
                            maxLines = 1,
                            style = TextStyle(color = CodeColors.SecondaryNeutral, fontSize = 11.sp)
                        )
                    }
                    BasicText(
                        text = v.kind.label,
                        maxLines = 1,
                        style = TextStyle(color = kindColor(v.kind), fontSize = 11.sp)
                    )
                    BasicText(
                        text = if (v.pinned) "\u2605" else "\u2606",
                        modifier = Modifier.clickable { onTogglePin(v) }.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = TextStyle(
                            color = if (v.pinned) CodeColors.Brand else CodeColors.SecondaryNeutral.copy(alpha = 0.55f),
                            fontSize = 20.sp
                        )
                    )
                }
            }
        }
    }
    BasicText(
        text = "Disimpan di penyimpanan privat aplikasi, maksimal " + HistoryStore.MAX_VERSIONS + " versi per berkas. " +
            "Ketuk \u2606 untuk menyematkan versi penting (mis. sebelum refactor besar); versi bersemat tidak ikut dipangkas, maksimal " +
            HistoryStore.MAX_PINS + ".",
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = TextStyle(color = CodeColors.SecondaryNeutral.copy(alpha = 0.7f), fontSize = 11.sp)
    )
    if ((versions?.size ?: 0) >= 2) {
        ChoiceChip(
            "Bandingkan dua versi", compareMode,
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        ) { onToggleCompare() }
    }
    HistoryButtons {
        if (!versions.isNullOrEmpty()) {
            Win7DialogButton(
                label = if (confirmClear) "Yakin hapus?" else "Hapus riwayat",
                onClick = {
                    if (confirmClear) {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.clear(file) }
                            onCleared()
                            confirmClear = false
                        }
                    } else {
                        confirmClear = true
                    }
                },
                accent = CodeColors.Danger
            )
        }
        Win7DialogButton("Tutup", onClose, isDefault = true)
    }
}

/** [CODE-HISTORY7] Penanda awal blok perubahan ke-[run] di daftar diff (tempat tombol "Kembalikan blok ini"). */
private class HunkHead(val run: Int)

@Composable
private fun HunkBar(done: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
        BasicText(
            text = if (done) "\u2713 dikembalikan" else "\u21B6 Kembalikan blok ini",
            modifier = (if (done) Modifier else Modifier.clickable(onClick = onClick)).padding(horizontal = 8.dp, vertical = 7.dp),
            style = TextStyle(color = if (done) CodeColors.SecondaryNeutral else CodeColors.Warning, fontSize = 11.sp)
        )
    }
}

/** [CODE-HISTORY2] Isi daftar perbedaan. [contrast] = diff kontras tinggi (Tema kode): latar lebih pekat, tanda berwarna tebal. */
@Composable
private fun DiffBody(
    st: HistDiffState?, maxH: Dp, contrast: Boolean,
    onRevert: ((Int) -> Unit)? = null, reverted: Set<Int> = emptySet() // [CODE-HISTORY7]
) {
    if (st == null || st.rows.isEmpty()) {
        Spacer(Modifier.height(4.dp))
        return
    }
    // [CODE-HISTORY7] sisipkan penanda blok sebelum baris berubah pertama tiap blok (hanya bila pengembalian blok tersedia)
    val entries = remember(st, onRevert != null) {
        val res = st.result
        val firstOf = HashMap<DiffLine, Int>()
        if (onRevert != null && res != null && !res.coarse) {
            HunkRevert.runs(res.lines).forEachIndexed { idx, r -> firstOf[res.lines[r.first]] = idx }
        }
        val out = ArrayList<Any>(st.rows.size + firstOf.size)
        for (row in st.rows) {
            val idx = row.line?.let { firstOf[it] }
            if (idx != null) out.add(HunkHead(idx))
            out.add(row)
        }
        out
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxH)) {
        items(entries) { e ->
            if (e is HunkHead) {
                HunkBar(done = e.run in reverted, onClick = { onRevert?.invoke(e.run) })
            } else {
            val row = e as DiffRow
            val ln = row.line
            if (ln == null) {
                BasicText(
                    text = "\u22EF " + row.skipped + " baris sama",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                    style = TextStyle(color = CodeColors.SecondaryNeutral.copy(alpha = 0.6f), fontSize = 11.sp)
                )
            } else {
                val bg = when (ln.op) {
                    DiffOp.ADD -> CodeColors.Clean.copy(alpha = if (contrast) 0.42f else 0.16f)
                    DiffOp.DEL -> CodeColors.Danger.copy(alpha = if (contrast) 0.46f else 0.18f)
                    DiffOp.SAME -> Color.Transparent
                }
                val signColor = when {
                    !contrast -> CodeColors.TextPrimary
                    ln.op == DiffOp.ADD -> CodeColors.Clean
                    ln.op == DiffOp.DEL -> CodeColors.Danger
                    else -> CodeColors.TextPrimary
                }
                val sign = when (ln.op) { DiffOp.ADD -> "+"; DiffOp.DEL -> "\u2212"; DiffOp.SAME -> " " }
                val no = if (ln.op == DiffOp.DEL) ln.oldNo else ln.newNo
                Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp, vertical = 1.dp)) {
                    BasicText(
                        text = no.toString(),
                        modifier = Modifier.width(40.dp),
                        maxLines = 1,
                        style = TextStyle(
                            color = if (contrast && ln.op != DiffOp.SAME) CodeColors.TextPrimary else CodeColors.SecondaryNeutral,
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace
                        )
                    )
                    BasicText(
                        text = sign,
                        modifier = Modifier.width(14.dp),
                        maxLines = 1,
                        style = TextStyle(
                            color = signColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = if (contrast) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                    // [CODE-HISTORY3] kata yang berubah diberi latar pekat; baris panjang membungkus (maks. 4 baris)
                    val markBg = if (ln.op == DiffOp.ADD) CodeColors.Clean.copy(alpha = 0.60f) else CodeColors.Danger.copy(alpha = 0.65f)
                    val shown = remember(ln, st.marks) {
                        val (txt, rel) = WordDiff.window(ln.text, st.marks[ln].orEmpty())
                        buildAnnotatedString {
                            append(txt)
                            for (r in rel) addStyle(SpanStyle(background = markBg, fontWeight = FontWeight.Bold), r.first, r.last + 1)
                        }
                    }
                    BasicText(
                        text = shown,
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = if (contrast && ln.op != DiffOp.SAME) Color.White else CodeColors.TextPrimary,
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
            } // [CODE-HISTORY7] tutup cabang baris diff
        }
    }
}

@Composable
private fun HistoryDiffPane(
    session: CodeSession, tab: EditorTab, store: HistoryStore, file: File, v: HistoryVersion, prev: HistoryVersion?, pinned: Boolean, now: Long, maxH: Dp,
    onTogglePin: () -> Unit, onBack: () -> Unit, onDone: () -> Unit
) {
    // Teks editor diambil di thread utama (Sora bukan thread-safe); membaca snapshot dan diff di thread latar.
    var rev by remember(v) { mutableStateOf(0) } // [CODE-HISTORY7] naik tiap blok dikembalikan: isi editor dibaca ulang
    val cur = remember(v, tab.loading, rev) { tab.currentTextOrNull(HistoryStore.MAX_CHARS) } // [CODE-HISTORY6] ambil ulang setelah tab selesai dimuat
    // [CODE-HISTORY4] Tampilan utama = perubahan versi ini dibanding versi sebelumnya; ketuk chip untuk dibanding isi sekarang.
    // Versi tertua (tanpa pembanding) langsung dibanding isi sekarang.
    var vsNow by remember(v) { mutableStateOf(prev == null) }
    val vsPrev = prev != null && !vsNow
    // [CODE-HISTORY7] pada mode versi sebelumnya diff tidak berubah saat blok dikembalikan, jadi blok yang sudah dikembalikan ditandai
    var revertedRuns by remember(v, vsNow) { mutableStateOf(setOf<Int>()) }
    val state by produceState<HistDiffState?>(null, v, vsNow, if (vsPrev) 0 else rev) {
        value = withContext(Dispatchers.IO) {
            val old = store.read(file, v)
            if (vsPrev && prev != null) {
                val before = store.read(file, prev)
                if (old == null || before == null) {
                    HistDiffState(old, null, emptyList(), before)
                } else {
                    val r = LineDiff.diff(before, old)
                    HistDiffState(old, r, LineDiff.compact(r, 2), before, WordDiff.mark(r.lines))
                }
            } else if (old == null || cur == null) {
                HistDiffState(old, null, emptyList())
            } else {
                val r = LineDiff.diff(old, cur)
                HistDiffState(old, r, LineDiff.compact(r, 2), marks = WordDiff.mark(r.lines))
            }
        }
    }
    val label = HistoryFormat.time(v.time, now)
    BasicText(
        text = (if (pinned) "\u2605 " else "") + label + " \u00B7 " + v.kind.label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        style = TextStyle(color = CodeColors.IconDefault, fontSize = CodeDimens.DialogTitleSize)
    )
    val st = state
    val summary = when {
        st == null -> "Membaca versi\u2026"
        st.old == null -> "Versi ini tidak bisa dibaca (berkas snapshot hilang atau rusak)."
        vsPrev && st.new == null -> "Versi sebelumnya tidak bisa dibaca (berkas snapshot hilang atau rusak)."
        st.result == null -> "Isi sekarang terlalu besar untuk dibandingkan."
        !st.result.changed -> when {
            vsPrev -> if (st.old == st.new) "Sama dengan versi sebelumnya." else "Hanya beda baris kosong di akhir berkas."
            else -> if (st.old == cur) "Sama dengan isi sekarang." else "Hanya beda baris kosong di akhir berkas."
        }
        else -> "\u2212" + st.result.removed + " +" + st.result.added +
            (if (vsPrev) " baris, perubahan versi ini dibanding versi sebelumnya" else " baris dibanding isi sekarang") +
            (if (st.result.coarse) " (berkas besar: diff ringkas)" else "")
    }
    BasicText(
        text = summary,
        modifier = Modifier.padding(horizontal = 16.dp),
        style = TextStyle(color = CodeColors.SecondaryNeutral, fontSize = 12.sp)
    )
    BasicText(
        text = if (vsPrev) "Merah: ada di versi sebelumnya. Hijau: baru di versi ini. Latar pekat: kata yang berubah."
        else "Merah: ada di versi ini. Hijau: ada di isi sekarang. Latar pekat: kata yang berubah.",
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
        style = TextStyle(color = CodeColors.SecondaryNeutral.copy(alpha = 0.7f), fontSize = 11.sp)
    )
    if (prev != null) {
        ChoiceChip(
            "Dibanding isi sekarang", vsNow,
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        ) { vsNow = !vsNow }
    }
    // [CODE-HISTORY7] skrip merusak berkas: pasang isi versi sebelumnya sekaligus ("Kembalikan" di bawah memasang versi INI)
    val prevText = if (vsPrev) st?.new else null
    if (prevText != null && !tab.readOnly && !tab.loading && tab.editor != null && prevText != cur) {
        ChoiceChip(
            "Kembalikan berkas ke versi sebelumnya", false,
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        ) { session.restoreHistoryText(tab, prevText, HistoryFormat.time((prev ?: v).time, now)); onDone() }
    }
    Box(Modifier.fillMaxWidth().height(CodeDimens.Hairline).background(CodeColors.Hairline))
    // [CODE-HISTORY7] "Kembalikan blok ini": baris hijau (isi sisi baru blok) di editor diganti baris merah, SATU langkah undo
    val diffRes = st?.result
    val curNow = cur
    val canRevert = diffRes != null && !diffRes.coarse && curNow != null && !tab.readOnly && !tab.loading && tab.editor != null
    val onRevert: ((Int) -> Unit)? = if (!canRevert || diffRes == null || curNow == null) null else fun(run: Int) {
        val out = HunkRevert.revertAt(curNow, diffRes.lines, run)
        if (out == null) {
            session.toast("Blok ini sudah berubah di editor, tidak bisa dikembalikan otomatis. Pakai Kembalikan untuk seluruh berkas.")
        } else {
            session.applyHunkRevert(tab, out)
            if (vsPrev) revertedRuns = revertedRuns + run
            rev++
        }
    }
    DiffBody(st, maxH, session.diffContrast, onRevert, if (vsPrev) revertedRuns else emptySet())
    val old = st?.old
    val canRestore = old != null && old != cur && !tab.readOnly && !tab.loading && tab.editor != null
    HistoryButtons {
        Win7DialogButton("Kembali", onBack)
        Win7DialogButton(if (pinned) "Lepas sematan" else "Sematkan", onTogglePin)
        if (canRestore && old != null) {
            Win7DialogButton("Kembalikan", { session.restoreHistoryText(tab, old, label); onDone() }, isDefault = true)
        }
    }
}

/** [CODE-HISTORY2] Perbedaan antara dua versi tersimpan: [a] (lama) ke [b] (baru). Merah = hanya di lama, hijau = hanya di baru. */
@Composable
private fun HistoryComparePane(
    session: CodeSession, tab: EditorTab, store: HistoryStore, file: File, a: HistoryVersion, b: HistoryVersion,
    now: Long, maxH: Dp, onBack: () -> Unit, onDone: () -> Unit
) {
    val cur = remember(a, b) { tab.currentTextOrNull(HistoryStore.MAX_CHARS) }
    val state by produceState<HistDiffState?>(null, a, b) {
        value = withContext(Dispatchers.IO) {
            val old = store.read(file, a)
            val new = store.read(file, b)
            if (old == null || new == null) {
                HistDiffState(old, null, emptyList(), new)
            } else {
                val r = LineDiff.diff(old, new)
                HistDiffState(old, r, LineDiff.compact(r, 2), new, WordDiff.mark(r.lines))
            }
        }
    }
    val labelA = HistoryFormat.time(a.time, now)
    val labelB = HistoryFormat.time(b.time, now)
    BasicText(
        text = "Lama: " + (if (a.pinned) "\u2605 " else "") + labelA + " \u00B7 " + a.kind.label + "\n" +
            "Baru: " + (if (b.pinned) "\u2605 " else "") + labelB + " \u00B7 " + b.kind.label,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        style = TextStyle(color = CodeColors.IconDefault, fontSize = 14.sp)
    )
    val st = state
    val summary = when {
        st == null -> "Membaca versi\u2026"
        st.old == null || st.new == null -> "Salah satu versi tidak bisa dibaca (berkas snapshot hilang atau rusak)."
        st.result == null -> "Tidak bisa dibandingkan."
        !st.result.changed -> if (st.old == st.new) "Kedua versi identik." else "Hanya beda baris kosong di akhir berkas."
        else -> "\u2212" + st.result.removed + " +" + st.result.added + " baris dari versi lama ke versi baru" +
            (if (st.result.coarse) " (berkas besar: diff ringkas)" else "")
    }
    BasicText(
        text = summary,
        modifier = Modifier.padding(horizontal = 16.dp),
        style = TextStyle(color = CodeColors.SecondaryNeutral, fontSize = 12.sp)
    )
    BasicText(
        text = "Merah: hanya di versi lama. Hijau: hanya di versi baru. Latar pekat: kata yang berubah.",
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
        style = TextStyle(color = CodeColors.SecondaryNeutral.copy(alpha = 0.7f), fontSize = 11.sp)
    )
    Box(Modifier.fillMaxWidth().height(CodeDimens.Hairline).background(CodeColors.Hairline))
    DiffBody(st, maxH, session.diffContrast)
    val canEdit = !tab.readOnly && !tab.loading && tab.editor != null
    val old = st?.old
    val new = st?.new
    HistoryButtons {
        Win7DialogButton("Kembali", onBack)
        if (canEdit && old != null && old != cur) {
            Win7DialogButton("Pasang lama", { session.restoreHistoryText(tab, old, labelA); onDone() })
        }
        if (canEdit && new != null && new != cur) {
            Win7DialogButton("Pasang baru", { session.restoreHistoryText(tab, new, labelB); onDone() }, isDefault = true)
        }
    }
}

/** [CODE-HISTORY5] Warna label jenis versi: simpan = hijau merek, skrip (sebelum/sesudah) = amber, lainnya = netral. */
private fun kindColor(k: HistoryKind): Color = when (k) {
    HistoryKind.SAVED -> CodeColors.Brand.copy(alpha = 0.85f)
    HistoryKind.SCRIPT, HistoryKind.AFTER_SCRIPT -> CodeColors.Warning
    HistoryKind.BEFORE, HistoryKind.RELOAD -> CodeColors.SecondaryNeutral
}

/** [CODE-HISTORY7] Pasang hasil pengembalian satu blok ke editor sebagai SATU langkah undo (tanpa menyimpan ke disk). */
internal fun CodeSession.applyHunkRevert(tab: EditorTab, text: String) {
    val ed = tab.editor
    if (ed == null || tab.loading || tab.readOnly || tab !in tabs) {
        toast("Editor belum siap atau tab hanya-baca")
        return
    }
    try {
        val line = ed.cursor.leftLine
        val col = ed.cursor.leftColumn
        val content = ed.text
        content.replace(0, content.length, text)
        val l = line.coerceIn(0, ed.text.lineCount - 1)
        ed.setSelection(l, col.coerceAtMost(ed.text.getColumnCount(l)))
        toast("Blok dikembalikan. Simpan untuk menerapkan, Urungkan untuk membatalkan")
    } catch (e: Exception) {
        toast("Gagal mengembalikan blok")
    }
}

/**
 * [CODE-HISTORY] Pasang [text] (versi lama) ke editor sebagai satu langkah undo. Tidak menyimpan ke disk:
 * tab menjadi "diubah" dan Urungkan membatalkan pengembalian. Kursor dijaga di baris yang sama bila masih ada.
 */
internal fun CodeSession.restoreHistoryText(tab: EditorTab, text: String, label: String) {
    val ed = tab.editor
    if (ed == null || tab.loading || tab.readOnly || tab !in tabs) {
        toast("Editor belum siap atau tab hanya-baca")
        return
    }
    try {
        val line = ed.cursor.leftLine
        val col = ed.cursor.leftColumn
        val content = ed.text
        content.replace(0, content.length, text)
        val l = line.coerceIn(0, ed.text.lineCount - 1)
        ed.setSelection(l, col.coerceAtMost(ed.text.getColumnCount(l)))
        toast("Versi $label dikembalikan. Simpan untuk menerapkan, Urungkan untuk membatalkan")
    } catch (e: Exception) {
        toast("Gagal mengembalikan versi")
    }
}
