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
import android.view.Gravity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.molina.core.ui.MintFileIcon
import com.molina.core.ui.MintFolder
import com.molina.core.fileops.FileOps
import com.molina.explorer.W7HelpPanel
import com.molina.explorer.W7Thumb

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
    // [ZIP-SHEET] Desain lembar bawah ala Molina_Studio (ArchiveSheet): judul + ringkasan, chip breadcrumb, Cari,
    // baris dua baris dengan centang, tombol "Ekstrak semua". Logika ZIP (buka/simpan/tempel/hapus) tidak berubah.
    var query by remember(zip) { mutableStateOf("") }
    var searchOpen by remember(zip) { mutableStateOf(false) }
    var menuOpen by remember(zip) { mutableStateOf(false) }
    val sl = listing
    val se = err
    val rows: List<W7ZipChild> = remember(sl, cwd, query) {
        if (sl == null) emptyList()
        else if (query.isBlank()) w7ZipChildren(sl.entries, cwd)
        else {
            val q = query.trim().lowercase()
            sl.entries.asSequence()
                .filter { it.name.trimEnd('/').substringAfterLast('/').lowercase().contains(q) }
                .take(200)
                .map { en ->
                    val n = en.name.trimEnd('/')
                    W7ZipChild(if (en.isDir) n + "/" else n, n.substringAfterLast('/'), en.isDir, en.size)
                }
                .toList()
        }
    }
    val maxSheetH = (LocalConfiguration.current.screenHeightDp * 0.88f).dp
    fun parentOf(p: String): String = p.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else it + "/" }
    fun sheetBack() {
        if (menuOpen) menuOpen = false
        else if (query.isNotEmpty() || searchOpen) { query = ""; searchOpen = false }
        else if (cwd.isNotEmpty()) { cwd = parentOf(cwd); picked = null; delArm = null }
        else tryClose()
    }
    Dialog(onDismissRequest = { sheetBack() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val sheetWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { sheetWindow?.setGravity(Gravity.BOTTOM) }
        val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxSheetH).clip(shape)
                .background(W7ZipSheetColors.Bg, shape)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(zip.name, color = W7ZipSheetColors.Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            se != null -> "Gagal membaca arsip"
                            sl == null -> "Membaca isi arsip\u2026"
                            else -> "zip \u2022 " + sl.files + " berkas, " + sl.folders + " folder \u2022 " + FileOps.readable(sl.totalSize) + (if (sl.encrypted) " \u2022 terenkripsi" else "")
                        },
                        color = W7ZipSheetColors.Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "\u22EE", color = W7ZipSheetColors.Ink, fontSize = 22.sp,
                    modifier = Modifier.clickable { menuOpen = !menuOpen }.padding(start = 14.dp, top = 6.dp, end = 6.dp, bottom = 6.dp)
                )
            }
            if (menuOpen) Column(
                Modifier.fillMaxWidth().padding(top = 6.dp).background(W7ZipSheetColors.Surface)
                    .border(1.dp, W7ZipSheetColors.Frame.copy(alpha = 0.67f))
            ) {
                ZipMenuItem("Terminal di folder salinan", picked != null && !busy && onOpenTerminal != null) { menuOpen = false; picked?.let { openEntry(it, null, true) } }
                ZipMenuItem("Simpan perubahan ke ZIP", dirty.isNotEmpty() && !busy) { menuOpen = false; saveBack(null) }
                ZipMenuItem("Tempel dari klip Penjelajah", sl != null && !busy) { menuOpen = false; addFromClip(null) }
                ZipMenuItem(if (delArm != null) "Yakin hapus? Ketuk lagi" else "Hapus terpilih / folder ini", sl != null && !busy && (picked != null || cwd.isNotEmpty()), danger = true) { menuOpen = false; deleteTarget() }
                ZipMenuItem("Bantuan", true) { menuOpen = false; showHelp = true }
                ZipMenuItem("Tutup", true) { menuOpen = false; tryClose() }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(top = 6.dp, bottom = 2.dp)) {
                    if (query.isNotBlank()) ZipCrumb("Hasil pencarian", true, null)
                    else {
                        val segs = cwd.trimEnd('/').split('/').filter { it.isNotEmpty() }
                        ZipCrumb("Isi arsip", segs.isEmpty(), if (segs.isEmpty()) null else ({ cwd = ""; picked = null; delArm = null }))
                        segs.forEachIndexed { i, name ->
                            Spacer(Modifier.width(6.dp))
                            val last = i == segs.lastIndex
                            ZipCrumb(name, last, if (last) null else ({ cwd = segs.take(i + 1).joinToString("/") + "/"; picked = null; delArm = null }))
                        }
                    }
                }
                Text(
                    "Cari", color = W7ZipSheetColors.Teal, fontSize = 13.sp,
                    modifier = Modifier.clickable { searchOpen = !searchOpen; if (!searchOpen) query = "" }.padding(start = 12.dp, top = 6.dp, end = 4.dp, bottom = 6.dp)
                )
            }
            if (searchOpen) BasicTextField(
                value = query, onValueChange = { query = it; picked = null }, singleLine = true,
                textStyle = TextStyle(color = W7ZipSheetColors.Ink, fontSize = 14.sp), cursorBrush = SolidColor(W7ZipSheetColors.Teal),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).background(W7ZipSheetColors.Surface).padding(horizontal = 12.dp, vertical = 8.dp),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text("Cari nama di arsip", color = W7ZipSheetColors.Dim, fontSize = 14.sp)
                        inner()
                    }
                }
            )
            if (sl != null && sl.encrypted) Text("Terenkripsi AES: kata sandi diminta saat membuka entri, menyimpan, atau mengekstrak.", color = W7ZipSheetColors.Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            if (sl != null && sl.truncated) Text("Hanya " + FileOps.ZIP_LIST_MAX + " entri pertama yang dimuat; daftar tidak lengkap.", color = W7ZipSheetColors.Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            if (se != null) Text(se, color = MolinaTheme.Bad, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            if (dirty.isNotEmpty()) Text("Diubah: " + dirty.joinToString(", ") { it.substringAfterLast('/') } + ". Simpan lewat menu \u22EE.", color = W7ZipSheetColors.Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            if (status.isNotEmpty()) Text(status, color = if (statusOk) W7ZipSheetColors.Teal else MolinaTheme.Bad, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            val listShape = RoundedCornerShape(6.dp)
            Column(
                Modifier.weight(1f, fill = false).fillMaxWidth().padding(top = 8.dp).clip(listShape)
                    .border(1.dp, W7ZipSheetColors.Frame.copy(alpha = 0.67f), listShape).verticalScroll(rememberScrollState())
            ) {
                if (sl == null && se == null) Text("Membaca isi arsip\u2026", color = W7ZipSheetColors.Dim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp, horizontal = 12.dp))
                else if (sl != null && rows.isEmpty()) Text(if (query.isNotBlank()) "Tidak ada yang cocok." else "Folder ini kosong.", color = W7ZipSheetColors.Dim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp, horizontal = 12.dp))
                rows.forEachIndexed { i, ch ->
                    val on = !ch.isDir && ch.full == picked
                    if (i > 0) Box(Modifier.padding(start = 58.dp).fillMaxWidth().height(1.dp).background(W7ZipSheetColors.Frame.copy(alpha = 0.67f)))
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .background(if (on) W7ZipSheetColors.Teal.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable(enabled = !busy) {
                                delArm = null
                                menuOpen = false
                                if (ch.isDir) { cwd = ch.full; picked = null; query = ""; searchOpen = false }
                                else if (picked == ch.full) openEntry(ch.full, null)
                                else picked = ch.full
                            }
                            .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(32.dp), Alignment.Center) {
                            if (ch.isDir) MintFolder(26.dp) else MintFileIcon(ch.label.substringAfterLast('.', ""), 28.dp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ch.label, color = W7ZipSheetColors.Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val loc = if (query.isNotBlank()) "/" + parentOf(ch.full).trimEnd('/') + " \u2022 " else ""
                            val sz = if (ch.isDir) "Folder" else FileOps.readable(ch.size)
                            Text(loc + sz + (if (ch.full in dirty) " \u2022 diubah" else ""), color = W7ZipSheetColors.Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (on) Text("\u2713", color = W7ZipSheetColors.Teal, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            val pv = preview
            if (pv != null) Box(
                Modifier.fillMaxWidth().padding(top = 8.dp).height(110.dp).background(W7ZipSheetColors.Surface)
                    .border(1.dp, W7ZipSheetColors.Frame.copy(alpha = 0.67f)), Alignment.Center
            ) {
                Image(pv, null, Modifier.fillMaxSize().padding(2.dp), contentScale = ContentScale.Fit)
            }
            val pk = picked
            if (pk != null) Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).background(W7ZipSheetColors.Surface).padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("1 dipilih: " + pk.substringAfterLast('/'), color = W7ZipSheetColors.Frame, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    "Buka", color = if (busy) W7ZipSheetColors.Dim else W7ZipSheetColors.Teal, fontSize = 14.sp,
                    modifier = Modifier.clickable(enabled = !busy) { openEntry(pk, null) }.padding(horizontal = 14.dp, vertical = 10.dp)
                )
                Text(
                    "Batal", color = W7ZipSheetColors.Ink, fontSize = 14.sp,
                    modifier = Modifier.clickable { picked = null; delArm = null }.padding(start = 14.dp, top = 10.dp, end = 6.dp, bottom = 10.dp)
                )
            } else {
                val canAll = sl != null && !busy
                Box(
                    Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (canAll) W7ZipSheetColors.Teal else W7ZipSheetColors.Teal.copy(alpha = 0.35f))
                        .clickable(enabled = canAll) { onExtract() }.padding(horizontal = 16.dp, vertical = 12.dp),
                    Alignment.Center
                ) {
                    Text("Ekstrak semua", color = W7ZipSheetColors.OnTeal, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
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

/** [ZIP-SHEET] Token lembar arsip, diport dari Molina_Studio (molina_bg, on_surface, terminal_accent, dst). */
private object W7ZipSheetColors {
    val Bg = Color(0xFF000000)
    val Surface = Color(0xFF0D0D0D)
    val Ink = Color(0xFFE6EAEE)
    val Dim = Color(0xFFA9B3BD)
    val Frame = Color(0xFF9AA0A8)
    val Teal = Color(0xFF00BFA5)
    val OnTeal = Color(0xFF00201B)
    val Amber = Color(0xFF87CF3E)
}

/** [ZIP-SHEET] Chip breadcrumb: bingkai tipis; folder aktif berlatar teal tipis. */
@Composable
private fun ZipCrumb(label: String, active: Boolean, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(16.dp)
    val teal = W7ZipSheetColors.Teal
    Box(
        Modifier.height(32.dp).widthIn(max = 180.dp).clip(shape)
            .background(if (active) teal.copy(alpha = 0.15f) else Color.Transparent, shape)
            .border(1.dp, if (active) teal else W7ZipSheetColors.Frame.copy(alpha = 0.67f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp),
        Alignment.Center
    ) {
        Text(label, color = if (active) teal else W7ZipSheetColors.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** [ZIP-SHEET] Satu baris menu \u22EE. */
@Composable
private fun ZipMenuItem(label: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        color = if (!enabled) W7ZipSheetColors.Dim.copy(alpha = 0.5f) else if (danger) MolinaTheme.Bad else W7ZipSheetColors.Ink,
        fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp)
    )
}
