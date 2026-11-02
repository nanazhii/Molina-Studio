package com.molina.studio.cinema

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

/** [ARCHIVE] Satu baris isi arsip pada satu level folder: [key] = kunci seleksi (folder berakhiran "/"). */
internal class ArcChild(val key: String, val label: String, val isDir: Boolean, val size: Long)

/** [ARCHIVE] Isi satu level folder [cwd] dari daftar entri; nama dibersihkan dulu (awalan "./" tar dibuang, entri tak aman dilewati). */
internal fun arcChildren(entries: List<FileOps.ZipEntryInfo>, cwd: String): List<ArcChild> {
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

private fun arcSize(b: Long): String = when {
    b <= 0L -> "\u2014"
    b < 1024L -> "$b B"
    b < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", b / 1024.0)
    b < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
    else -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
}

@Composable
private fun ArcOption(on: Boolean, label: String, note: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(if (on) "(\u2022)" else "(  )", color = Win7.NavHeader, fontSize = 12.sp, modifier = Modifier.width(30.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = Win7.Ink, fontSize = 13.sp)
            if (note.isNotEmpty()) Text(note, color = Win7.TextDim, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * [ARCHIVE] Dialog ekstrak untuk semua format yang didukung [ArchiveRoute] ([ArchiveEngine] atau 7z di Debian): pilih tujuan (di sini / folder bernama arsip)
 * dan, bila mau, centang entri untuk ekstrak sebagian. Daftar dibaca di thread IO; arsip berkata sandi memanggil
 * [onNeedPassword] (pemanggil menampilkan dialog sandi lalu membuka dialog ini lagi dengan [password]).
 */
@Composable
internal fun ArchiveExtractDialog(
    file: File,
    password: String?,
    onNeedPassword: (wrong: Boolean) -> Unit,
    onClose: () -> Unit,
    onExtract: (ExtractTarget, Set<String>?) -> Unit
) {
    val ctx = LocalContext.current
    val fmtLabel = remember(file) { ArchiveRoute.label(file.name) } // [ARCHIVE5]
    val canPw = remember(file) { ArchiveRoute.canPassword(file.name) }
    val viaDebian = remember(file) { ArchiveRoute.viaDebian(file.name) }
    var listing by remember(file, password) { mutableStateOf<FileOps.ZipListing?>(null) }
    var err by remember(file, password) { mutableStateOf<String?>(null) }
    var target by remember(file) { mutableStateOf(ExtractTarget.NAMED_FOLDER) }
    var partial by remember(file) { mutableStateOf(false) }
    var cwd by remember(file) { mutableStateOf("") }
    val keys = remember(file) { mutableStateListOf<String>() }

    LaunchedEffect(file, password) {
        val r = withContext(Dispatchers.IO) { runCatching { ArchiveOps.list(ctx, file, password) } }
        val ex = r.exceptionOrNull()
        when {
            ex is FileOps.NeedPassword -> onNeedPassword(false)
            ex is FileOps.WrongPassword -> onNeedPassword(true)
            ex != null -> err = ex.message ?: ex.javaClass.simpleName
            else -> {
                val l = r.getOrNull()
                if (l != null && l.encrypted && password == null && canPw) onNeedPassword(false) else listing = l
            }
        }
    }

    val l = listing
    val title = "Ekstrak " + (fmtLabel ?: "arsip")
    W7Panel(title, onClose) {
        Text(file.name, color = Win7.Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        when {
            err != null -> {
                Text(err ?: "", color = Color(0xFFFF8A80), fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Win7Button("Tutup", onClick = onClose) }
            }
            l == null -> {
                Text("Membaca isi arsip\u2026", color = Win7.TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Win7Button("Batal", onClick = onClose) }
            }
            else -> {
                Text(
                    l.files.toString() + " berkas, " + l.folders + " folder, " + arcSize(l.totalSize) +
                        (if (l.truncated) " (daftar dipotong)" else "") + (if (l.encrypted) ", berkata sandi" else ""),
                    color = Win7.TextDim, fontSize = 11.sp
                )
                if (viaDebian) Text("Dibuka lewat 7z di Debian (Molina Terminal)", color = Win7.TextDim, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                ArcOption(target == ExtractTarget.NAMED_FOLDER, "Ke folder \"" + ArchiveRoute.folderName(file.name) + "\"", "Buat folder baru bernama arsip") {
                    target = ExtractTarget.NAMED_FOLDER
                }
                ArcOption(target == ExtractTarget.HERE, "Di sini", "Langsung ke folder ini; berkas yang sudah ada tidak ditimpa") {
                    target = ExtractTarget.HERE
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Win7.Hairline))
                Row(
                    Modifier.fillMaxWidth().clickable { partial = !partial }.padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (partial) "[x]" else "[  ]", color = Win7.Ink, fontSize = 12.sp, modifier = Modifier.width(30.dp))
                    Text("Pilih entri yang diekstrak", color = Win7.Ink, fontSize = 13.sp)
                }
                if (partial) {
                    val rows = remember(l, cwd) { arcChildren(l.entries, cwd) }
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "/" + cwd, color = Win7.TextDim, fontSize = 11.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        if (cwd.isNotEmpty()) {
                            Win7Button("Naik", compact = true) {
                                cwd = cwd.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                            }
                        }
                    }
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 220.dp)
                            .border(1.dp, Win7.Field).background(Win7.Input)
                    ) {
                        if (rows.isEmpty()) Text("Folder kosong", color = Win7.TextDim, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                        LazyColumn(Modifier.fillMaxWidth()) {
                            items(rows, key = { it.key }) { c ->
                                val covered = ArchiveNames.selected(c.key.trimEnd('/'), c.isDir, keys.toSet())
                                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (covered) "[x]" else "[  ]", color = Win7.Ink, fontSize = 12.sp,
                                        modifier = Modifier.width(34.dp).clickable {
                                            if (c.key in keys) keys.remove(c.key) else if (!covered) keys.add(c.key)
                                        }.padding(vertical = 8.dp)
                                    )
                                    Text(
                                        (if (c.isDir) "\u25B8 " else "") + c.label, color = Win7.Ink, fontSize = 13.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).clickable(enabled = c.isDir) { cwd = c.key }.padding(vertical = 8.dp)
                                    )
                                    if (!c.isDir) Text(arcSize(c.size), color = Win7.TextDim, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    Text(
                        "Centang folder untuk menyertakan seluruh isinya. Ketuk nama folder untuk membukanya.",
                        color = Win7.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    val ok = !partial || keys.isNotEmpty()
                    Win7Button(
                        if (partial && keys.isNotEmpty()) "Ekstrak (" + keys.size + ")" else "Ekstrak",
                        isDefault = true, enabled = ok
                    ) { onExtract(target, if (partial) keys.toSet() else null) }
                    Spacer(Modifier.width(8.dp))
                    Win7Button("Batal", onClick = onClose)
                }
            }
        }
    }
}
