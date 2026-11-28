package com.molina.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.molina.studio.ui.MolinaTheme
import com.molina.core.ui.Win7
import com.molina.core.ui.Win7Button
import com.molina.core.fileops.FileOps
import com.molina.core.fileops.ArchiveNames
import com.molina.core.fileops.ArchiveRoute
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.molina.core.ui.MintFileIcon
import com.molina.core.ui.MintFolder

/** [ARCHIVE] Satu baris isi arsip pada satu level folder: [key] = kunci seleksi (folder berakhiran "/"). */
class ArcChild(val key: String, val label: String, val isDir: Boolean, val size: Long)

/** [ARCHIVE] Isi satu level folder [cwd] dari daftar entri; nama dibersihkan dulu (awalan "./" tar dibuang, entri tak aman dilewati). */
fun arcChildren(entries: List<FileOps.ZipEntryInfo>, cwd: String): List<ArcChild> {
    val dirs = LinkedHashSet<String>()
    val files = ArrayList<ArcChild>()
    for (en in entries) {
        val n = ArchiveNames.cleanEntry(en.name) ?: continue
        if (n.isEmpty() || !n.startsWith(cwd)) continue
        val rest = n.substring(cwd.length)
        if (rest.isEmpty()) continue
        val slash = rest.indexOf('/')
        if (slash >= 0) dirs.add(rest.substring(0, slash))
        else if (en.isDir) dirs.add(rest)
        else files.add(ArcChild(n, rest, false, en.size))
    }
    return dirs.sortedBy { it.lowercase(Locale.ROOT) }.map { ArcChild(cwd + it + "/", it, true, 0L) } +
        files.sortedBy { it.label.lowercase(Locale.ROOT) }
}

private fun arcParent(p: String): String =
    p.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else it + "/" }

/**
 * [ARCHIVE-SHEET] Dialog ekstrak untuk semua format [ArchiveRoute] ([ArchiveEngine] atau 7z di Debian), kini berupa lembar bawah
 * yang sama dengan dialog ZIP: judul + ringkasan, chip "Isi arsip", Cari, daftar berikon, tombol "Ekstrak semua".
 * Ketuk folder untuk masuk; ketuk lingkaran di kanan untuk mencentang (ekstrak sebagian). Tujuan dipilih di lembar berikutnya
 * (ArchiveExtractDestSheet) [ARCHIVE-DEST].
 * Arsip berkata sandi memanggil [onNeedPassword] (pemanggil menampilkan dialog sandi lalu membuka dialog ini lagi dengan [password]).
 */
@Composable
fun ArchiveExtractDialog(
    file: File,
    password: String?,
    env: ExplorerEnv,
    onNeedPassword: (wrong: Boolean) -> Unit,
    onClose: () -> Unit,
    initialKeys: Set<String> = emptySet(),
    onExtract: (Set<String>?) -> Unit
) {
    val th = LocalExplorerTheme.current // [EXPLORER-THEME]
    val fmtLabel = remember(file) { ArchiveRoute.label(file.name) } // [ARCHIVE5]
    val canPw = remember(file) { ArchiveRoute.canPassword(file.name) }
    val viaDebian = remember(file) { ArchiveRoute.viaDebian(file.name) }
    var listing by remember(file, password) { mutableStateOf<FileOps.ZipListing?>(null) }
    var err by remember(file, password) { mutableStateOf<String?>(null) }
    var cwd by remember(file) { mutableStateOf("") }
    var query by remember(file) { mutableStateOf("") }
    var searchOpen by remember(file) { mutableStateOf(false) }
    val keys = remember(file) { mutableStateListOf<String>().apply { addAll(initialKeys) } }

    LaunchedEffect(file, password) {
        val r = withContext(Dispatchers.IO) { runCatching { ArchiveOps.list(env, file, password) } }
        val ex = r.exceptionOrNull()
        when {
            ex is FileOps.NeedPassword -> onNeedPassword(false)
            ex is FileOps.WrongPassword -> onNeedPassword(true)
            ex != null -> err = ex.message ?: ex.javaClass.simpleName
            else -> {
                val l0 = r.getOrNull()
                if (l0 != null && l0.encrypted && password == null && canPw) onNeedPassword(false) else listing = l0
            }
        }
    }

    val l = listing
    val e = err
    val rows: List<ArcChild> = remember(l, cwd, query) {
        if (l == null) emptyList()
        else if (query.isBlank()) arcChildren(l.entries, cwd)
        else {
            val q = query.trim().lowercase(Locale.ROOT)
            l.entries.asSequence()
                .mapNotNull { en ->
                    val n = ArchiveNames.cleanEntry(en.name)?.trimEnd('/')
                    if (n.isNullOrEmpty()) null
                    else ArcChild(if (en.isDir) n + "/" else n, n.substringAfterLast('/'), en.isDir, en.size)
                }
                .filter { it.label.lowercase(Locale.ROOT).contains(q) }
                .take(200)
                .toList()
        }
    }
    val keySet = keys.toSet()
    fun sheetBack() {
        if (query.isNotEmpty() || searchOpen) { query = ""; searchOpen = false }
        else if (cwd.isNotEmpty()) cwd = arcParent(cwd)
        else onClose()
    }

    Dialog(onDismissRequest = { sheetBack() }, properties = archiveSheetProperties()) {
        ArchiveSheetGravity()
        Column(
            archiveSheetModifier(th) // [ARCHIVE-SHEET-3]
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
        ) {
            ArchiveSheetHeader(
                th, file.name,
                when {
                    e != null -> "Gagal membaca arsip"
                    l == null -> "Membaca isi arsip\u2026"
                    else -> (fmtLabel ?: file.extension).lowercase(Locale.ROOT) + " \u2022 " + l.files + " berkas, " + l.folders +
                        " folder \u2022 " + FileOps.readable(l.totalSize) + (if (l.encrypted) " \u2022 terenkripsi" else "")
                },
                onBack = { sheetBack() }
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(top = 6.dp, bottom = 2.dp)) {
                    if (query.isNotBlank()) ZipCrumb("Hasil pencarian", true, null)
                    else {
                        val segs = cwd.trimEnd('/').split('/').filter { it.isNotEmpty() }
                        ZipCrumb("Isi arsip", segs.isEmpty(), if (segs.isEmpty()) null else ({ cwd = "" }))
                        segs.forEachIndexed { i, name ->
                            Spacer(Modifier.width(6.dp))
                            val last = i == segs.lastIndex
                            ZipCrumb(name, last, if (last) null else ({ cwd = segs.take(i + 1).joinToString("/") + "/" }))
                        }
                    }
                }
                Text(
                    "Cari", color = th.accent, fontSize = 13.sp,
                    modifier = Modifier.clickable { searchOpen = !searchOpen; if (!searchOpen) query = "" }.padding(start = 12.dp, top = 6.dp, end = 4.dp, bottom = 6.dp)
                )
            }
            if (searchOpen) BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = TextStyle(color = th.ink, fontSize = 14.sp), cursorBrush = SolidColor(th.accent),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).background(th.surface).padding(horizontal = 12.dp, vertical = 8.dp),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text("Cari nama di arsip", color = th.dim, fontSize = 14.sp)
                        inner()
                    }
                }
            )
            if (viaDebian && l != null) Text("Dibuka lewat 7z di Debian (Molina Terminal).", color = th.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            if (l != null && l.truncated) Text("Daftar dipotong; tidak semua entri dimuat.", color = th.warn, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            val listShape = RoundedCornerShape(6.dp)
            val listMod = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp).clip(listShape) // [ARCHIVE-SHEET-2] daftar mengisi sisa tinggi
                .border(1.dp, th.frame.copy(alpha = 0.67f), listShape)
            if (e != null) Text(e, color = MolinaTheme.Bad, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            else if (l == null || rows.isEmpty()) Column(listMod) {
                Text(
                    when {
                        l == null -> "Membaca isi arsip\u2026"
                        query.isNotBlank() -> "Tidak ada yang cocok."
                        else -> "Folder ini kosong."
                    },
                    color = th.dim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp, horizontal = 12.dp)
                )
            }
            else LazyColumn(listMod) {
                itemsIndexed(rows, key = { i, c -> i.toString() + ":" + c.key }) { i, c ->
                    val covered = ArchiveNames.selected(c.key.trimEnd('/'), c.isDir, keySet)
                    Column {
                        if (i > 0) Box(Modifier.padding(start = 58.dp).fillMaxWidth().height(1.dp).background(th.frame.copy(alpha = 0.67f)))
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .background(if (covered) th.accent.copy(alpha = 0.16f) else Color.Transparent)
                                .clickable(enabled = c.isDir) { cwd = c.key; query = ""; searchOpen = false }
                                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(32.dp), Alignment.Center) {
                                if (c.isDir) MintFolder(26.dp) else MintFileIcon(c.label.substringAfterLast('.', ""), 28.dp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.label, color = th.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val loc = if (query.isNotBlank()) "/" + arcParent(c.key).trimEnd('/') + " \u2022 " else ""
                                Text(loc + (if (c.isDir) "Folder" else FileOps.readable(c.size)), color = th.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                if (covered) "\u2713" else "\u25CB", color = if (covered) th.accent else th.dim,
                                fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    if (c.key in keys) keys.remove(c.key) else if (!covered) keys.add(c.key)
                                }.padding(horizontal = 14.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            }
            val canGo = l != null && e == null
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (keys.isNotEmpty()) ArchiveSheetTextButton(th, "Batal") { keys.clear() }
                ArchiveSheetButton(th, if (keys.isEmpty()) "Ekstrak semua" else "Ekstrak (" + keys.size + ")", canGo, Modifier.weight(1f)) {
                    onExtract(if (keys.isEmpty()) null else keys.toSet())
                }
            }
        }
    }
}
