package com.molina.studio.cinema

import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.molina.studio.ui.MolinaTheme

// [SPLIT-ZIP] Penampil ZIP Penjelajah (W7ZipDialog dan pendukungnya), dipecah dari Win7OpenDialog.kt.

/** [ZIP-VIEW] Isi ZIP tanpa mengekstrak; tombol Ekstrak memakai alur yang sama dengan menu Alat (termasuk kata sandi). */
internal val W7_ZIP_IMG = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

/** Entri yang sedang dibuka dari ZIP: salinan sementara + cap waktu/ukuran saat dibuka untuk mendeteksi suntingan. */
internal class W7ZipOpened(val file: File, var lm: Long, var len: Long)

/** Permintaan kata sandi tertunda: kind 0 = buka, 1 = buka di Terminal, 2 = simpan ke ZIP, 3 = tambah ke ZIP [ZIP-EDIT]. */
internal class W7ZipAsk(val kind: Int, val name: String, val wrong: Boolean)

internal class W7ZipChild(val full: String, val label: String, val isDir: Boolean, val size: Long)

/** Isi satu level folder di dalam ZIP (folder tanpa entri eksplisit tetap diturunkan dari path berkas). */
internal fun w7ZipChildren(entries: List<FileOps.ZipEntryInfo>, cwd: String): List<W7ZipChild> {
    val dirs = LinkedHashSet<String>()
    val files = ArrayList<W7ZipChild>()
    for (en in entries) {
        val n = en.name.trimEnd('/')
        if (n.isEmpty() || !n.startsWith(cwd)) continue
        val rest = n.substring(cwd.length)
        if (rest.isEmpty()) continue
        val slash = rest.indexOf('/')
        if (slash >= 0) dirs.add(rest.substring(0, slash))
        else if (en.isDir) dirs.add(rest)
        else files.add(W7ZipChild(n, rest, false, en.size))
    }
    return dirs.sorted().map { W7ZipChild(cwd + it + "/", it, true, 0L) } + files.sortedBy { it.label.lowercase() }
}

@Composable
internal fun W7ZipDialog(
    zip: File, onClose: () -> Unit, onOpenFile: (File) -> Unit,
    onOpenTerminal: ((File) -> Boolean)?, clipFiles: () -> List<File>, onExtract: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Folder files aplikasi (terlihat dari Debian bila /sdcard di-bind); cadangan: cache internal
    val cacheRoot = remember { ctx.getExternalFilesDir(null) ?: ctx.cacheDir }
    var listing by remember(zip) { mutableStateOf<FileOps.ZipListing?>(null) }
    var err by remember(zip) { mutableStateOf<String?>(null) }
    var cwd by remember(zip) { mutableStateOf("") }
    var picked by remember(zip) { mutableStateOf<String?>(null) }
    var busy by remember(zip) { mutableStateOf(false) }
    var status by remember(zip) { mutableStateOf("") }
    var statusOk by remember(zip) { mutableStateOf(true) }
    var ask by remember(zip) { mutableStateOf<W7ZipAsk?>(null) }
    var zipPw by remember(zip) { mutableStateOf<String?>(null) }
    var closeArmed by remember(zip) { mutableStateOf(false) }
    val opened = remember(zip) { mutableStateMapOf<String, W7ZipOpened>() }
    var dirty by remember(zip) { mutableStateOf<List<String>>(emptyList()) }
    var delArm by remember(zip) { mutableStateOf<String?>(null) } // [ZIP-EDIT] target hapus yang menunggu konfirmasi
    LaunchedEffect(zip) {
        val r = withContext(Dispatchers.IO) { runCatching { FileOps.listZip(zip) } }
        listing = r.getOrNull()
        err = r.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
    }
    // Deteksi suntingan di salinan sementara (mis. dari Code) tiap detik selama dialog terbuka
    LaunchedEffect(zip) {
        while (true) {
            dirty = opened.entries.filter { it.value.file.lastModified() != it.value.lm || it.value.file.length() != it.value.len }.map { it.key }
            delay(1000)
        }
    }
    fun reload() {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { FileOps.listZip(zip) } }
            r.getOrNull()?.let { listing = it }
        }
    }
    // [ZIP-OPEN] salin satu entri ke folder sementara lalu buka: media -> mpv, teks -> Code, atau Terminal
    fun openEntry(name: String, pw0: String?, toTerminal: Boolean = false) {
        if (busy) return
        val pw = pw0 ?: zipPw
        busy = true
        statusOk = true
        closeArmed = false
        status = "Menyiapkan " + name.substringAfterLast('/') + "\u2026"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { FileOps.extractEntry(zip, name, cacheRoot, pw) { !isActive } } }
            busy = false
            val f = r.getOrNull()
            val ex = r.exceptionOrNull()
            when {
                f != null -> {
                    if (pw != null) zipPw = pw
                    if (opened[name] == null) opened[name] = W7ZipOpened(f, f.lastModified(), f.length())
                    if (toTerminal) {
                        val ok = onOpenTerminal?.invoke(f) ?: false
                        statusOk = ok
                        status = if (ok) "Terminal dibuka di folder salinan." else "Lokasi salinan tidak terlihat dari Debian."
                    } else {
                        status = "Dibuka dari salinan sementara. Setelah mengedit, tekan Simpan untuk menulis ke ZIP."
                        onOpenFile(f)
                    }
                }
                ex is FileOps.NeedPassword -> { status = ""; ask = W7ZipAsk(if (toTerminal) 1 else 0, name, false) }
                ex is FileOps.WrongPassword -> { status = ""; ask = W7ZipAsk(if (toTerminal) 1 else 0, name, true) }
                else -> { statusOk = false; status = ex?.message ?: "Gagal membuka entri" }
            }
        }
    }
    // [ZIP-SAVE] tulis kembali entri yang disunting ke ZIP (cadangan otomatis di folder zipbak)
    fun saveBack(pw0: String?) {
        if (busy) return
        val items = dirty.mapNotNull { n -> opened[n]?.let { Pair(n, it) } }
        if (items.isEmpty()) {
            statusOk = true
            status = "Tidak ada perubahan untuk disimpan."
            return
        }
        val pw = pw0 ?: zipPw
        busy = true
        statusOk = true
        closeArmed = false
        status = "Menyimpan " + items.size + " berkas ke ZIP\u2026"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { for (p in items) FileOps.replaceEntry(zip, p.first, p.second.file, pw, File(cacheRoot, "zipbak")) }
            }
            busy = false
            val ex = r.exceptionOrNull()
            when {
                ex == null -> {
                    if (pw != null) zipPw = pw
                    for (p in items) { p.second.lm = p.second.file.lastModified(); p.second.len = p.second.file.length() }
                    status = "Tersimpan ke ZIP (" + items.size + " berkas). Cadangan ZIP tersimpan di folder zipbak."
                    reload()
                }
                ex is FileOps.NeedPassword -> { status = ""; ask = W7ZipAsk(2, "", false) }
                ex is FileOps.WrongPassword -> { status = ""; ask = W7ZipAsk(2, "", true) }
                else -> { statusOk = false; status = ex?.message ?: "Gagal menyimpan ke ZIP" }
            }
        }
    }
    // [ZIP-EDIT] tempel berkas/folder dari klip Penjelajah ke folder ZIP yang sedang dibuka
    fun addFromClip(pw0: String?) {
        if (busy) return
        val src = clipFiles()
        if (src.isEmpty()) {
            statusOk = false
            status = "Klip kosong. Di Penjelajah, pilih berkas lalu Alat > Salin, kemudian buka ZIP ini."
            return
        }
        val pw = pw0 ?: zipPw
        val dest = cwd
        busy = true
        statusOk = true
        closeArmed = false
        delArm = null
        status = "Menambahkan " + src.size + " item ke ZIP\u2026"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { FileOps.addEntries(zip, src, dest, pw, File(cacheRoot, "zipbak")) } }
            busy = false
            val ex = r.exceptionOrNull()
            when {
                ex == null -> {
                    if (pw != null) zipPw = pw
                    status = "Ditambahkan " + (r.getOrNull() ?: 0) + " item ke /" + dest + ". Cadangan ZIP ada di folder zipbak."
                    reload()
                }
                ex is FileOps.NeedPassword -> { status = ""; ask = W7ZipAsk(3, "", false) }
                ex is FileOps.WrongPassword -> { status = ""; ask = W7ZipAsk(3, "", true) }
                else -> { statusOk = false; status = ex?.message ?: "Gagal menambah ke ZIP" }
            }
        }
    }
    // [ZIP-EDIT] hapus berkas terpilih (atau folder yang sedang dibuka) dari ZIP; ketuk dua kali untuk konfirmasi
    fun deleteTarget() {
        if (busy) return
        val target = picked ?: cwd.takeIf { it.isNotEmpty() } ?: return
        if (delArm != target) {
            delArm = target
            statusOk = false
            status = "Hapus /" + target + " dari ZIP? Tekan Hapus sekali lagi untuk konfirmasi (cadangan dibuat otomatis)."
            return
        }
        delArm = null
        busy = true
        statusOk = true
        closeArmed = false
        status = "Menghapus dari ZIP\u2026"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { FileOps.deleteEntries(zip, listOf(target), File(cacheRoot, "zipbak")) } }
            busy = false
            val ex = r.exceptionOrNull()
            if (ex == null) {
                status = "Dihapus " + (r.getOrNull() ?: 0) + " entri dari ZIP. Cadangan ada di folder zipbak."
                opened.keys.filter { it == target || (target.endsWith("/") && it.startsWith(target)) }.forEach { opened.remove(it) }
                if (target.endsWith("/")) cwd = target.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else it + "/" }
                picked = null
                reload()
            } else { statusOk = false; status = ex.message ?: "Gagal menghapus dari ZIP" }
        }
    }
    fun tryClose() {
        if (dirty.isNotEmpty() && !closeArmed) {
            closeArmed = true
            statusOk = false
            status = "Ada perubahan belum disimpan ke ZIP. Tekan Tutup sekali lagi untuk membuangnya."
        } else onClose()
    }
    // [ZIP-PREVIEW] pratinjau gambar langsung dari ZIP, tanpa ekstrak ke disk
    val pk0 = picked
    val isImg = pk0 != null && pk0.substringAfterLast('.', "").lowercase() in W7_ZIP_IMG
    val preview: ImageBitmap? by produceState<ImageBitmap?>(null, pk0, zipPw) {
        value = null
        if (isImg && pk0 != null) {
            delay(200)
            value = W7Thumb.zipImage(zip, pk0, zipPw)?.asImageBitmap()
        }
    }
    var showHelp by remember { mutableStateOf(false) } // [ZIP-LAYOUT]
    W7Panel("ZIP: " + zip.name, { tryClose() }) {
        val l = listing
        val e = err
        when {
            e != null -> Text(e, color = Color(0xFFFF8A80), fontSize = 12.sp)
            l == null -> Text("Membaca isi ZIP\u2026", color = Win7.TextDim, fontSize = 12.sp)
            else -> {
                Text(
                    l.files.toString() + " berkas, " + l.folders + " folder \u2022 " + FileOps.readable(l.totalSize),
                    color = Win7.Ink, fontSize = 12.sp
                )
                if (l.encrypted) {
                    Spacer(Modifier.height(4.dp))
                    Text("Terenkripsi AES: kata sandi diminta saat membuka entri, menyimpan, atau mengekstrak.", color = Color(0xFFE0A800), fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text("/" + cwd, color = Win7.NavHeader, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                val children = remember(l, cwd) { w7ZipChildren(l.entries, cwd) }
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 220.dp).background(Win7.Input)
                        .border(1.dp, Win7.Field).verticalScroll(rememberScrollState())
                ) {
                    if (cwd.isNotEmpty()) Row(
                        Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            cwd = cwd.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else it + "/" }
                            picked = null
                        }.padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("\u2190  ..", color = Win7.NavHeader, fontSize = 12.sp)
                    }
                    children.forEach { ch ->
                        val on = !ch.isDir && ch.full == picked
                        Row(
                            Modifier.fillMaxWidth()
                                .then(if (on) Modifier.background(Win7.SelFill) else Modifier)
                                .clickable(enabled = !busy) {
                                    delArm = null
                                    if (ch.isDir) { cwd = ch.full; picked = null }
                                    else if (picked == ch.full) openEntry(ch.full, null)
                                    else picked = ch.full
                                }
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (ch.isDir) Win7Folder(16.dp) else W7FileIcon(ch.label.substringAfterLast('.', ""), 18.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                ch.label, color = if (ch.isDir) Win7.NavHeader else Win7.Ink, fontSize = 12.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                            )
                            if (opened[ch.full] != null && ch.full in dirty) Text("diubah", color = MolinaTheme.Gold, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp))
                            if (!ch.isDir) Text(FileOps.readable(ch.size), color = Win7.TextDim, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    if (children.isEmpty()) Text("Folder ini kosong.", color = Win7.TextDim, fontSize = 11.sp, modifier = Modifier.padding(6.dp))
                    if (l.truncated) Text(
                        "\u2026 hanya " + FileOps.ZIP_LIST_MAX + " entri pertama ditampilkan",
                        color = Win7.TextDim, fontSize = 11.sp, modifier = Modifier.padding(6.dp)
                    )
                }
                val pv = preview
                if (pv != null) {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(110.dp).background(Win7.Input).border(1.dp, Win7.Field), Alignment.Center) {
                        Image(pv, null, Modifier.fillMaxSize().padding(2.dp), contentScale = ContentScale.Fit)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { // [ZIP-LAYOUT]
                    Text("Ketuk berkas untuk memilih, ketuk lagi untuk membuka.", color = Win7.TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text(
                        "Bantuan", color = MolinaTheme.Gold, fontSize = 12.sp,
                        modifier = Modifier.clickable { showHelp = true }.padding(start = 8.dp, top = 6.dp, bottom = 6.dp)
                    )
                }
            }
        }
        if (dirty.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Diubah: " + dirty.joinToString(", ") { it.substringAfterLast('/') } + ". Tekan Simpan untuk menulis ke ZIP.", color = MolinaTheme.Gold, fontSize = 11.sp)
        }
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(status, color = if (statusOk) Win7.NavHeader else Color(0xFFFF8A80), fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
        // [ZIP-LAYOUT-BTN] dua baris, tiap tombol berbobot sama: label tidak lagi terpotong
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Win7Button("Buka", Modifier.weight(1f), isDefault = true, enabled = picked != null && !busy, compact = true) { picked?.let { openEntry(it, null) } }
            Win7Button("Terminal", Modifier.weight(1f), enabled = picked != null && !busy && onOpenTerminal != null, compact = true) { picked?.let { openEntry(it, null, true) } }
            Win7Button("Simpan", Modifier.weight(1f), enabled = dirty.isNotEmpty() && !busy, accent = MolinaTheme.Gold, compact = true) { saveBack(null) }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Win7Button("Tempel", Modifier.weight(1f), enabled = listing != null && !busy, compact = true) { addFromClip(null) }
            Win7Button(if (delArm != null) "Yakin?" else "Hapus", Modifier.weight(1f), enabled = listing != null && !busy && (picked != null || cwd.isNotEmpty()), accent = if (delArm != null) Color(0xFFFF8A80) else null, compact = true) { deleteTarget() }
            Win7Button("Ekstrak", Modifier.weight(1f), enabled = listing != null && !busy, compact = true) { onExtract() }
            Win7Button("Tutup", Modifier.weight(1f), compact = true) { tryClose() }
        }
    }
    if (showHelp) W7HelpPanel(
        "Cara memakai ZIP", "Telusuri, buka, dan ubah isi ZIP tanpa mengekstraknya lebih dulu.",
        listOf(
            "Folder" to "Ketuk untuk masuk.",
            "Berkas" to "Ketuk untuk memilih, ketuk lagi atau tekan Buka (media ke mpv, teks ke Code).",
            "Terminal" to "Membuka folder salinan berkas terpilih.",
            "Tempel" to "Menambah berkas yang disalin di Penjelajah ke folder ini.",
            "Hapus" to "Membuang berkas terpilih, atau folder ini bila tak ada yang dipilih.",
            "Ukuran" to "Yang tampil adalah ukuran tanpa kompresi."
        )
    ) { showHelp = false } // [HELP-PANEL]
    val a = ask
    if (a != null) W7PasswordDialog(
        title = if (a.kind == 3) "Tambah ke ZIP" else if (a.kind == 2) "Simpan ke ZIP" else "Buka dari ZIP",
        okLabel = if (a.kind == 3) "Tambah" else if (a.kind == 2) "Simpan" else "Buka", confirmField = false,
        note = if (a.wrong) "Kata sandi salah. Coba lagi." else "ZIP ini dilindungi kata sandi.",
        onCancel = { ask = null }
    ) { pw ->
        ask = null
        when (a.kind) {
            3 -> addFromClip(pw)
            2 -> saveBack(pw)
            1 -> openEntry(a.name, pw, true)
            else -> openEntry(a.name, pw)
        }
    }
}
