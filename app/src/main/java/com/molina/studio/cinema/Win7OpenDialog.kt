package com.molina.studio.cinema

import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.composed
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.molina.core.common.LogLevel
import com.molina.studio.log.LogBus
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.isActive
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import com.molina.studio.terminal.RootfsManager
import com.molina.studio.terminal.TerminalSessions
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
import com.molina.core.common.TrashBin
import com.molina.core.common.TrashItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class W7Entry(val file: File, val isDir: Boolean, val size: Long, val modified: Long, val rel: String? = null) {
    val name: String get() = file.name
}

private enum class W7Kind(val label: String) { MEDIA("Media (video dan audio)"), ALL("Semua file") }
internal class W7Place(val label: String, val dir: File, val molina: Boolean = false)
internal class W7Clip(val files: List<File>, val cut: Boolean)
internal sealed class W7Sheet {
    class Rename(val file: File) : W7Sheet()
    class Zip(val files: List<File>) : W7Sheet()
    class Compress(val files: List<File>) : W7Sheet() // [ARCHIVE2] dialog Kompres (format, level, nama, sandi ZIP)
    class Password(val zip: File, val wrong: Boolean) : W7Sheet()
    class ZipView(val zip: File) : W7Sheet() // [ZIP-VIEW]
    class Archive(val file: File, val pw: String?) : W7Sheet() // [ARCHIVE] 7z/tar/gz/bz2/xz (dan ZIP lewat "Ekstrak pilihan")
    class ArchivePw(val file: File, val wrong: Boolean) : W7Sheet() // [ARCHIVE]
    class Info(val files: List<File>) : W7Sheet()
    class Delete(val files: List<File>, val permanent: Boolean = false) : W7Sheet()
    object NewDir : W7Sheet()
}

internal fun w7Names(l: List<File>): String = if (l.size == 1) l[0].name else l.size.toString() + " item"
private fun w7StemEnd(name: String, dir: Boolean): Int =
    if (dir) name.length else name.lastIndexOf('.').let { if (it > 0) it else name.length }

internal val W7_ID = Locale("id", "ID")
internal val W7_LISTS = setOf("m3u", "m3u8", "pls", "cue")
private val W7_AUDIO = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "ac3", "dts", "mka")
internal val W7_MEDIA = W7_AUDIO + setOf(
    "mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "ts", "m2ts", "mts", "flv", "wmv",
    "mpg", "mpeg", "ogv", "vob", "rmvb", "m3u", "m3u8", "pls", "cue"
)

private fun w7HasStorage(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

internal fun w7Favorites(browse: Boolean = false): List<W7Place> = listOfNotNull(
    W7Place("Molina", File(Environment.getExternalStorageDirectory(), com.molina.core.common.MolinaPaths.APP_FOLDER), true),
    if (browse) W7Place("Project", File(Environment.getExternalStorageDirectory(), TerminalSessions.WORKSPACE_DIR)) else null,
    W7Place("Video", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)),
    W7Place("Unduhan", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)),
    W7Place("Musik", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)),
    W7Place("Kamera", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM))
).filter { it.dir.isDirectory }

/** Beranda Debian (/root di rootfs) sebagai akar tambahan agar berkas Debian bisa dikelola dari Penjelajah. */
private fun w7DebianPlaces(ctx: Context, browse: Boolean): List<W7Place> {
    if (!browse) return emptyList()
    val home = File(RootfsManager.rootfsDir(ctx), "root")
    return if (home.isDirectory) listOf(W7Place("Debian", home)) else emptyList()
}

private fun w7Drives(): List<W7Place> {
    val out = mutableListOf(W7Place("Internal", Environment.getExternalStorageDirectory()))
    File("/storage").listFiles()?.forEach {
        if (it.isDirectory && it.canRead() && it.name != "emulated" && it.name != "self") out.add(W7Place(it.name, it))
    }
    return out
}

/** Isi folder lewat [ExplorerList]; pasangan hasil = (daftar, terpotong?) atau null bila folder tak terbaca. */
private fun w7Load(dir: File, query: String, o: ExplorerList.Opts, cancelled: () -> Boolean): Pair<List<W7Entry>, Boolean>? {
    val r = ExplorerList.list(dir, query, o, cancelled) ?: return null
    return Pair(r.items.map { W7Entry(it.file, it.isDir, it.size, it.modified, it.rel) }, r.truncated)
}

private fun w7Size(b: Long) = String.format(W7_ID, "%,d KB", maxOf(1L, (b + 1023) / 1024))
internal fun w7Date(ms: Long) = SimpleDateFormat("dd/MM/yyyy HH.mm", W7_ID).format(Date(ms))

private fun w7Crumbs(dir: File, drives: List<W7Place>): List<Pair<String, File>> {
    val base = drives.filter { dir.path == it.dir.path || dir.path.startsWith(it.dir.path + "/") }
        .maxByOrNull { it.dir.path.length } ?: W7Place(dir.name.ifEmpty { "/" }, dir)
    val out = mutableListOf(Pair(base.label, base.dir))
    var cur = base.dir
    dir.path.removePrefix(base.dir.path).split('/').filter { it.isNotEmpty() }.forEach {
        cur = File(cur, it)
        out.add(Pair(it, cur))
    }
    return out
}

/** Pemilih file lokal bergaya Windows 7 (Aero). Mengembalikan File yang dipilih lewat [onPick]. */
@Composable
fun Win7OpenDialog(
    onDismiss: () -> Unit,
    onPick: (File) -> Unit = {},
    browse: Boolean = false,
    showClose: Boolean = true,
    asScreen: Boolean = false,
    screenModifier: Modifier = Modifier,
    onCopyPath: ((File) -> Unit)? = null,
    onOpenTerminal: ((File) -> Unit)? = null,
    onShare: ((List<File>) -> Boolean)? = null,
    onOpenWith: ((File) -> Boolean)? = null,
    onInsertPaths: ((List<File>) -> Unit)? = null,
    isVisibleInDebian: ((File) -> Boolean)? = null,
    pickFolder: Boolean = false,
    onPickFolder: (File) -> Unit = {},
    onPlay: (suspend (List<File>) -> Boolean)? = null,
    onOpenInCode: ((List<File>) -> Unit)? = null,
    onSendToScripts: ((File) -> Pair<Boolean, String>)? = null,
    startDir: File? = null
) {
    val ctx = LocalContext.current
    var favTick by remember { mutableStateOf(0) } // [FILEOPS-1]
    val favs = remember(favTick) {
        val base = w7Favorites(browse)
        val mine = if (browse) ExplorerFavorites.load(ctx).filter { f -> base.none { it.dir.path == f.path } } else emptyList()
        base + mine.map { W7Place(it.name.ifEmpty { it.path }, it) }
    }
    val vols = remember { w7Drives() + w7DebianPlaces(ctx, browse) }
    val volDirs = remember(vols) { vols.map { it.dir } }
    var granted by remember { mutableStateOf(w7HasStorage(ctx)) }
    // [FILES-PLAYED] riwayat putar Cinema (terbaru di indeks 0) untuk chip di daftar; ikut tersegarkan lewat version
    val playedRefs = remember(CinemaRecent.version) { CinemaRecent.load(ctx).map { w7Norm(it.ref) } }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    // Mode jelajah: tanpa startDir, mulai dari folder terakhir yang dibuka (bila masih ada), bukan selalu dari akar
    var dir by remember { mutableStateOf(startDir?.takeIf { it.isDirectory } ?: (if (browse) ExplorerRecent.last(ctx) else null) ?: vols.first().dir) }
    // [NAV-SWIPE] panel kiri: dock di landscape, drawer geser di potret
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    var navOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) } // [W7-ICON2] pencarian mengembang di potret
    BackHandler(enabled = portrait && navOpen) { navOpen = false }
    val recents = remember(dir) {
        if (browse) ExplorerRecent.load(ctx).filter { r -> r != dir && vols.none { it.dir == r } && favs.none { it.dir == r } }.take(5)
        else emptyList()
    }
    var kind by remember { mutableStateOf(if (browse) W7Kind.ALL else W7Kind.MEDIA) }
    // [EXPLORER-SEARCH] pencarian nama + sortir yang diingat
    val savedSort = remember { ExplorerPrefs.load(ctx) }
    var key by remember { mutableStateOf(savedSort.key) }
    var asc by remember { mutableStateOf(savedSort.asc) }
    var foldersFirst by remember { mutableStateOf(savedSort.foldersFirst) }
    var showHidden by remember { mutableStateOf(savedSort.showHidden) }
    var deepSearch by remember { mutableStateOf(savedSort.deep) }
    var playedFirst by remember { mutableStateOf(savedSort.playedFirst) }
    var query by remember { mutableStateOf("") }
    var sortOpen by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var entries by remember { mutableStateOf<List<W7Entry>?>(emptyList()) }
    var selected by remember { mutableStateOf<W7Entry?>(null) }
    var fileName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var undo by remember { mutableStateOf<List<TrashItem>?>(null) }
    var showBin by remember { mutableStateOf(false) }
    var comboOpen by remember { mutableStateOf(false) }
    // [FILEOPS-2] alat berkas Penjelajah: pilih banyak, salin, pindah, tempel, hapus, folder baru, ZIP/ekstrak, ubah nama, favorit, properti
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var clip by remember { mutableStateOf<W7Clip?>(null) }
    var toolsOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) } // [DROP-MENU]
    var busy by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<W7Sheet?>(null) }
    var messageOk by remember { mutableStateOf(false) }
    var multi by remember { mutableStateOf(false) }
    var showSelectHelp by remember { mutableStateOf(false) } // [HELP-PANEL]
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var progress by remember { mutableStateOf<Float?>(null) }
    val cancelFlag = remember { AtomicBoolean(false) }
    val guard = remember {
        vols.map { File(it.dir, TrashBin.DIR).absolutePath }.toSet() + vols.map { it.dir.absolutePath }.toSet() +
            File(Environment.getExternalStorageDirectory(), com.molina.core.common.MolinaPaths.APP_FOLDER).absolutePath
    }
    fun say(text: String, ok: Boolean = false) { message = text; messageOk = ok }
    fun targets(): List<File> = if (multi) picked.map { File(it) } else listOfNotNull(selected?.file)
    fun runOp(op: (FileOps.Ctl) -> String, onDone: () -> Unit = {}, onFail: (Throwable) -> Unit = {}) {
        if (busy) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            say("Izin tulis penyimpanan belum diberikan. Aktifkan izin Penyimpanan di Setelan Android.")
            return
        }
        busy = true
        progress = null
        cancelFlag.set(false)
        undo = null
        say("Memproses\u2026", true)
        scope.launch {
            val ctl = FileOps.Ctl(cancelFlag) { p -> progress = p }
            val r = withContext(Dispatchers.IO) { runCatching { op(ctl) } }
            busy = false
            progress = null
            r.onSuccess { say(it, true); onDone(); selected = null; picked = emptySet(); fileName = ""; tick++ }
                .onFailure {
                    say(it.message ?: ("Operasi gagal (" + it.javaClass.simpleName + ")"))
                    if (it !is FileOps.NeedPassword && it !is FileOps.WrongPassword && it.message != "Dibatalkan") {
                        LogBus.log("penjelajah", LogLevel.Error, "FileOps", w7Trace(it))
                    }
                    tick++
                    onFail(it)
                }
        }
    }

    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.READ_EXTERNAL_STORAGE) }
    LaunchedEffect(Unit) { if (browse) withContext(Dispatchers.IO) { runCatching { TrashBin.autoPurge(volDirs) } } }
    LaunchedEffect(key, asc, foldersFirst, showHidden, deepSearch, playedFirst) {
        ExplorerPrefs.save(ctx, ExplorerPrefs.Data(key, asc, foldersFirst, showHidden, deepSearch, playedFirst))
    }
    LaunchedEffect(dir, kind, key, asc, tick, granted, query, foldersFirst, showHidden, deepSearch) {
        if (!granted) return@LaunchedEffect
        if (query.isNotBlank()) delay(250)
        val opts = ExplorerList.Opts(key, asc, foldersFirst, showHidden, deepSearch, if (kind == W7Kind.MEDIA) W7_MEDIA else null)
        val deep = deepSearch && query.isNotBlank()
        if (deep) say("Mencari sampai subfolder\u2026", true)
        val r = withContext(Dispatchers.IO) { w7Load(dir, query, opts) { !isActive } }
        if (deep && message.startsWith("Mencari")) say("")
        entries = r?.first
        if (r != null && r.second) say("Hasil dibatasi " + ExplorerList.MAX_RESULTS + " teratas. Persempit kata kunci.")
    }

    // [FILES-QUEUE] urutan tampil; opsi "terakhir diputar dulu" bersifat stabil (folder tetap di atas, sisanya urutan asal)
    val shown = remember(entries, playedFirst, playedRefs) {
        val es = entries
        if (es == null || !playedFirst) es
        else es.sortedWith(compareBy<W7Entry>({ if (it.isDir) 0 else 1 }, { if (it.isDir) 0 else playedRefs.indexOf(w7Norm(it.file.path)).let { i -> if (i < 0) 99 else i } }))
    }

    val roots = vols.map { it.dir.path }
    val canUp = dir.path !in roots && dir.parentFile != null
    // [NAV-HISTORY] riwayat Mundur/Maju antar folder (maks. 50); tombol bulat di kiri atas = Mundur, bila kosong = Naik
    val backStack = remember { mutableStateListOf<String>() }
    val fwdStack = remember { mutableStateListOf<String>() }
    fun go(to: File) {
        if (to.path != dir.path) {
            backStack.add(dir.path)
            if (backStack.size > 50) backStack.removeAt(0)
            fwdStack.clear()
        }
        dir = to; selected = null; fileName = ""; message = ""; query = ""; undo = null; if (browse) ExplorerRecent.push(ctx, to)
    } // [PICK-PERSIST] picked bertahan lintas folder
    fun jump(to: File) {
        dir = to; selected = null; fileName = ""; message = ""; query = ""; undo = null; if (browse) ExplorerRecent.push(ctx, to)
    }
    fun goBack(): Boolean {
        while (backStack.isNotEmpty()) {
            val p = File(backStack.removeAt(backStack.size - 1))
            if (p.isDirectory) { fwdStack.add(dir.path); jump(p); return true }
        }
        return false
    }
    fun goForward(): Boolean {
        while (fwdStack.isNotEmpty()) {
            val p = File(fwdStack.removeAt(fwdStack.size - 1))
            if (p.isDirectory) { backStack.add(dir.path); jump(p); return true }
        }
        return false
    }
    fun goUp(): Boolean {
        val parent = dir.parentFile
        if (!canUp || parent == null) return false
        go(parent)
        return true
    }
    /** Ketuk ulang berkas terpilih di Penjelajah: media ke mpv, teks ke Code. */
    fun smartOpen(f: File) {
        val play = onPlay
        val code = onOpenInCode
        when {
            play != null && f.extension.lowercase() in W7_MEDIA -> scope.launch {
                val ok = play(listOf(f))
                say(if (ok) "Memutar di mpv: " + f.name else "Gagal memutar " + f.name, ok)
            }
            f.extension.equals("zip", ignoreCase = true) -> sheet = W7Sheet.ZipView(f)
            f.isFile && ArchiveRoute.supports(f.name) -> sheet = W7Sheet.Archive(f, null) // [ARCHIVE]
            code != null && ExplorerList.looksText(f) -> code(listOf(f))
            onOpenWith != null && f.isFile -> onOpenWith?.invoke(f)
            else -> say("Tidak ada aplikasi Molina untuk membuka jenis berkas ini. Pakai menu Alat untuk menyalin atau mengompres.")
        }
    }
    fun confirm() {
        val typed = fileName.trim()
        if (typed.isEmpty()) return
        val target = selected?.takeIf { it.name == typed }?.file
            ?: File(if (typed.startsWith("/")) typed else dir.path + "/" + typed)
        when {
            target.isDirectory -> go(target)
            target.isFile -> onPick(target)
            else -> say("Berkas tidak ditemukan. Periksa nama berkas lalu coba lagi.")
        }
    }

    if (showBin) W7BinDialog(volDirs, { showBin = false }) { tick++ }
    when (val s = sheet) {
        is W7Sheet.Rename -> W7NameDialog(
            title = if (s.file.isDirectory) "Ubah nama folder" else "Ubah nama berkas",
            initial = s.file.name, selectEnd = w7StemEnd(s.file.name, s.file.isDirectory), okLabel = "OK",
            onCancel = { sheet = null }
        ) { name ->
            sheet = null
            runOp(op = { "Nama diubah: " + FileOps.rename(s.file, name).name })
        }
        W7Sheet.NewDir -> W7NameDialog(
            title = "Folder baru", initial = "Folder baru", selectEnd = "Folder baru".length, okLabel = "Buat",
            onCancel = { sheet = null }
        ) { name ->
            sheet = null
            val d = dir
            runOp(op = { "Folder dibuat: " + FileOps.mkdir(d, name).name })
        }
        is W7Sheet.Delete -> W7ConfirmDialog(s.files, s.permanent, onCancel = { sheet = null }) {
            sheet = null
            if (s.permanent) {
                runOp(op = { ctl -> "Dihapus permanen: " + FileOps.deleteAll(s.files, guard, ctl) + " item" })
            } else {
                val moved = arrayOfNulls<List<TrashItem>>(1)
                runOp(
                    op = { _ ->
                        val r = TrashBin.trash(s.files, volDirs, guard)
                        moved[0] = r
                        "Dipindahkan ke Recycle Bin: " + r.size + " item"
                    },
                    onDone = { undo = moved[0] }
                )
            }
        }
        is W7Sheet.Info -> W7InfoDialog(s.files, if (s.files.size == 1) isVisibleInDebian?.invoke(s.files[0]) else null) { sheet = null }
        is W7Sheet.Compress -> ArchiveCompressDialog( // [ARCHIVE2]
            s.files, dir.name, onClose = { sheet = null }
        ) { opt ->
            sheet = null
            val d = dir
            val items = s.files
            runOp(op = { ctl ->
                val made = ArchiveOps.compress(ctx, items, d, opt, ctl) // [ARCHIVE6] 7z bersandi lewat Debian
                "Arsip dibuat: " + made.name + " (" + CompressNames.sizeText(made.length()) + (if (!opt.password.isNullOrEmpty()) ", bersandi" else "") + ")"
            })
        }
        is W7Sheet.Zip -> W7PasswordDialog(
            title = "Kompres ZIP berkata sandi", okLabel = "Kompres", confirmField = true,
            note = "Enkripsi AES-256. Simpan kata sandinya: ZIP tidak bisa dibuka tanpa itu.",
            onCancel = { sheet = null }
        ) { pw ->
            sheet = null
            val d = dir
            val items = s.files
            runOp(op = { ctl -> "ZIP terenkripsi dibuat: " + FileOps.zipAll(items, d, ctl, pw).name })
        }
        is W7Sheet.Password -> W7PasswordDialog(
            title = "Ekstrak ZIP", okLabel = "Ekstrak", confirmField = false,
            note = if (s.wrong) "Kata sandi salah. Coba lagi." else "ZIP ini dilindungi kata sandi.",
            onCancel = { sheet = null }
        ) { pw ->
            sheet = null
            val d = dir
            val z = s.zip
            runOp(
                op = { ctl -> "Diekstrak ke: " + FileOps.unzip(z, d, ctl, pw).name },
                onFail = { if (it is FileOps.WrongPassword) sheet = W7Sheet.Password(z, true) }
            )
        }
        is W7Sheet.ZipView -> W7ZipDialog(
            s.zip, onClose = { sheet = null }, onOpenFile = { smartOpen(it) },
            clipFiles = { clip?.files ?: emptyList() }, // [ZIP-EDIT]
            onOpenTerminal = if (onOpenTerminal != null) ({ f: File ->
                if (isVisibleInDebian?.invoke(f) != false) { onOpenTerminal.invoke(f.parentFile ?: f); true } else false
            }) else null
        ) {
            sheet = null
            val z = s.zip
            val d = z.parentFile?.takeIf { it.isDirectory } ?: dir
            runOp(
                op = { ctl -> "Diekstrak ke: " + FileOps.unzip(z, d, ctl).name },
                onFail = { if (it is FileOps.NeedPassword) { say(""); sheet = W7Sheet.Password(z, false) } }
            )
        }
        is W7Sheet.Archive -> ArchiveExtractDialog( // [ARCHIVE]
            s.file, s.pw,
            onNeedPassword = { wrong -> sheet = W7Sheet.ArchivePw(s.file, wrong) },
            onClose = { sheet = null }
        ) { exTarget, exOnly ->
            sheet = null
            val a = s.file
            val apw = s.pw
            val d = a.parentFile?.takeIf { it.isDirectory && it.canWrite() } ?: dir
            runOp(
                op = { ctl -> ArchiveOps.extract(ctx, a, d, exTarget, ctl, apw, exOnly).describe() },
                onFail = {
                    if (it is FileOps.NeedPassword) { say(""); sheet = W7Sheet.ArchivePw(a, false) }
                    else if (it is FileOps.WrongPassword) { say(""); sheet = W7Sheet.ArchivePw(a, true) }
                }
            )
        }
        is W7Sheet.ArchivePw -> W7PasswordDialog( // [ARCHIVE]
            title = "Buka arsip", okLabel = "Buka", confirmField = false,
            note = if (s.wrong) "Kata sandi salah. Coba lagi." else "Arsip ini dilindungi kata sandi.",
            onCancel = { sheet = null }
        ) { pw ->
            sheet = W7Sheet.Archive(s.file, pw)
        }
        null -> Unit
    }

    val frame = RoundedCornerShape(8.dp)
    // Keterangan hasil operasi melayang di atas daftar dan hilang sendiri (tidak menyita tinggi panel bawah)
    LaunchedEffect(message) {
        if (message.isNotBlank()) {
            delay(if (undo != null) 9000L else (3500L + message.length * 30L).coerceAtMost(9000L))
            message = ""
        }
    }
    val backAction: () -> Unit = { if (multi) { multi = false; picked = emptySet() } else if (!goUp()) onDismiss() }
    val content: @Composable () -> Unit = {
        // [FILEOPS-2] keyboard tidak boleh muncul sendiri saat Penjelajah dibuka
        val dlgFocus = LocalFocusManager.current
        val dlgView = LocalView.current
        val dlgWindow = (dlgView.parent as? DialogWindowProvider)?.window
        SideEffect {
            dlgWindow?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
        LaunchedEffect(Unit) {
            dlgFocus.clearFocus(force = true)
            delay(250)
            dlgFocus.clearFocus(force = true)
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(dlgView.windowToken, 0)
        }
        Column(
            (if (asScreen) screenModifier.fillMaxSize().padding(4.dp) else Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 24.dp))
                .clip(frame)
                .background(Brush.verticalGradient(listOf(Win7.FrameTop, Win7.FrameBottom)), frame)
                .border(1.dp, Win7.FrameLine, frame)
                .padding(start = 6.dp, end = 6.dp, bottom = 6.dp)
        ) {
            if (!asScreen) Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.Top) {
                Row(Modifier.weight(1f).padding(top = 5.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Win7Folder(16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(if (browse) "Penjelajah Molina" else if (pickFolder) "Buka folder" else "Buka", color = Win7.Ink, fontSize = 13.sp)
                }
                if (showClose) Win7Close(onDismiss)
            }
            Column(Modifier.weight(1f).fillMaxWidth().border(1.dp, Win7.FrameLine).background(Win7.Face)) {
                Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Win7BackOrb(canUp || backStack.isNotEmpty()) { if (!goBack()) goUp() }
                    if (portrait && !browse) W7Cmd("\u2630", true) { navOpen = !navOpen } // [DROP-HAMBURGER] mode jelajah: lihat menu drop-up
                    val showBox = !portrait || searchOpen || query.isNotBlank()
                    val showCrumbs = !portrait || !showBox
                    Spacer(Modifier.width(6.dp))
                    if (showCrumbs) Row(
                        Modifier.weight(1f).height(26.dp).background(Win7.Input)
                            .border(1.dp, Win7.Field).padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val cs = w7Crumbs(dir, vols)
                        val shown = if (cs.size > 2) cs.takeLast(2) else cs
                        if (cs.size > 2) Text("\u2026 \u203A ", color = Win7.TextDim, fontSize = 12.sp)
                        shown.forEachIndexed { i, c ->
                            if (i > 0) Text(" \u203A ", color = Win7.TextDim, fontSize = 12.sp)
                            Text(
                                c.first, color = Win7.Ink, fontSize = 12.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false).clickable { go(c.second) }
                            )
                        }
                    }
                    if (portrait) {
                        if (showBox) {
                            W7SearchBox(query, { query = it; selected = null }, Modifier.weight(1f))
                            W7IconCmd(W7Ico.CLEAR, "Tutup pencarian", true) { query = ""; selected = null; searchOpen = false }
                        } else W7IconCmd(W7Ico.SEARCH, "Cari", true) { searchOpen = true }
                    } else {
                        Spacer(Modifier.width(6.dp))
                        W7SearchBox(query, { query = it; selected = null }, Modifier.width(112.dp))
                    }
                }
                if (query.isNotBlank()) Row(
                    Modifier.fillMaxWidth().clickable { deepSearch = !deepSearch }.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (deepSearch) "[x]" else "[  ]", color = Win7.Ink, fontSize = 12.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("Cari sampai subfolder", color = Win7.TextDim, fontSize = 11.sp)
                }
                // [DROP-MENU] isi popup Urut dan Alat dipakai bersama: bilah ikon (dialog Buka) dan menu drop-up (Penjelajah)
                val sortPopup: @Composable (Boolean) -> Unit = { up ->
                    if (sortOpen) Popup(
                            alignment = if (up) Alignment.BottomEnd else Alignment.TopStart,
                            offset = if (up) IntOffset(0, -with(density) { 44.dp.roundToPx() }) else IntOffset(0, with(density) { 30.dp.roundToPx() }),
                            onDismissRequest = { sortOpen = false },
                            properties = PopupProperties(focusable = true)
                        ) {
                            Column( // [POPUP-SCROLL] tinggi maks 70% layar; isi bisa digulir
                                Modifier.width(240.dp).heightIn(max = w7PopupMaxHeight())
                                    .background(Win7.Input).border(1.dp, Win7.FrameLine)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                W7ChoiceItem(if (key == ExplorerList.Key.NAME) "\u25CF" else "\u25CB", "Nama") { key = ExplorerList.Key.NAME; sortOpen = false }
                                W7ChoiceItem(if (key == ExplorerList.Key.DATE) "\u25CF" else "\u25CB", "Tanggal diubah") { key = ExplorerList.Key.DATE; sortOpen = false }
                                W7ChoiceItem(if (key == ExplorerList.Key.SIZE) "\u25CF" else "\u25CB", "Ukuran") { key = ExplorerList.Key.SIZE; sortOpen = false }
                                W7ChoiceItem(if (key == ExplorerList.Key.TYPE) "\u25CF" else "\u25CB", "Jenis (ekstensi)") { key = ExplorerList.Key.TYPE; sortOpen = false }
                                Box(Modifier.fillMaxWidth().height(1.dp).background(Win7.Hairline))
                                W7ChoiceItem(if (asc) "\u25CF" else "\u25CB", "Menaik (A-Z, lama ke baru)") { asc = true; sortOpen = false }
                                W7ChoiceItem(if (!asc) "\u25CF" else "\u25CB", "Menurun (Z-A, baru ke lama)") { asc = false; sortOpen = false }
                                Box(Modifier.fillMaxWidth().height(1.dp).background(Win7.Hairline))
                                W7ChoiceItem(if (foldersFirst) "[x]" else "[  ]", "Folder di atas") { foldersFirst = !foldersFirst; sortOpen = false }
                                W7ChoiceItem(if (showHidden) "[x]" else "[  ]", "Tampilkan berkas tersembunyi") { showHidden = !showHidden; sortOpen = false }
                            }
                        }
                }
                val toolsPopup: @Composable () -> Unit = {
                    if (toolsOpen) W7ToolsMenu( // [SPLIT-TOOLS] isi menu ada di W7ToolsMenu.kt
                        targets = targets(), dir = dir, browse = browse, clip = clip,
                        playedFirst = playedFirst, playedRefs = playedRefs, shown = shown,
                        onPlay = onPlay, onOpenInCode = onOpenInCode, onSendToScripts = onSendToScripts,
                        onOpenWith = onOpenWith, onShare = onShare, onCopyPath = onCopyPath,
                        onDismiss = { toolsOpen = false },
                        onClip = { clip = it }, onPlayedFirst = { playedFirst = it },
                        onSheet = { sheet = it }, onFavChanged = { favTick++ },
                        onSay = { t, ok -> say(t, ok) },
                        onRunOp = { op, onDone, onFail -> runOp(op, onDone, onFail) }
                    )
                }
                if (browse) {
                    // [DROP-MENU] keterangan folder/item pindah ke atas (bekas bilah ikon)
                    val tvTarget = selected?.file ?: dir
                    val tvFiles = if (multi && picked.isNotEmpty()) picked.map { File(it) } else listOf(tvTarget)
                    W7BrowseInfo(
                        files = tvFiles,
                        isDir = if (selected == null || multi) null else selected!!.isDir,
                        size = selected?.size ?: 0L,
                        modified = selected?.modified ?: 0L,
                        onCopyPath = { onCopyPath?.invoke(tvTarget); say("Path disalin", true) }
                    )
                } else Row(
                    Modifier.fillMaxWidth().height(30.dp)
                        .horizontalScroll(rememberScrollState())
                        .background(Brush.verticalGradient(listOf(Win7.BarTop, Win7.BarBottom))),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    W7IconCmd(W7Ico.REFRESH, "Segarkan", true) { tick++ }
                    Box {
                        W7IconCmd(W7Ico.SORT, "Urut", true, active = sortOpen) { sortOpen = !sortOpen }
                        sortPopup(false)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Win7.Hairline))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.fillMaxSize()) {
                    if (!portrait) {
                        W7NavPane(favs, vols, dir, Modifier.width(92.dp).fillMaxHeight(), onBin = if (browse) ({ showBin = true }) else null, recents = recents) { go(it) }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(Win7.Hairline))
                    }
                    W7FileArea(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        granted = granted, entries = shown, key = key, asc = asc, selected = selected,
                        compact = portrait,
                        onSort = { k -> if (key == k) asc = !asc else { key = k; asc = true } },
                        multi = multi, picked = picked, recentRefs = playedRefs,
                        emptyNote = if (query.isNotBlank()) "Tidak ada hasil untuk \"" + query.trim() + "\"" else "Folder ini kosong.",
                        onOpen = { e ->
                            if (multi) picked = if (e.file.path in picked) picked - e.file.path else picked + e.file.path
                            else if (e.isDir) go(e.file)
                            else if (pickFolder) Unit
                            else if (selected?.file == e.file) { if (browse) smartOpen(e.file) else onPick(e.file) }
                            else { selected = e; fileName = e.name; message = "" }
                        },
                        onEnter = { e -> go(e.file) },
                        onSwipeRight = if (browse) ({ e: W7Entry ->
                            if (!multi) { multi = true; picked = setOf(e.file.path); selected = null; fileName = "" }
                            else picked = if (e.file.path in picked) picked - e.file.path else picked + e.file.path
                        }) else null,
                        onSwipeLeft = if (browse) ({ e: W7Entry ->
                            if (multi) { if (e.file.path !in picked) picked = picked + e.file.path }
                            else { selected = e; fileName = e.name }
                            message = ""
                            toolsOpen = true
                        }) else null,
                        onAsk = { ask.launch(Manifest.permission.READ_EXTERNAL_STORAGE) }
                    )
                }
                if (portrait) {
                    Box(Modifier.align(Alignment.CenterStart).width(16.dp).fillMaxHeight().pointerInput(Unit) {
                        detectHorizontalDragGestures { _, dx -> if (dx > 6f) navOpen = true }
                    })
                    if (navOpen) Box(Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) {
                        detectTapGestures { navOpen = false }
                    })
                    androidx.compose.animation.AnimatedVisibility(
                        visible = navOpen, modifier = Modifier.align(Alignment.CenterStart),
                        enter = androidx.compose.animation.slideInHorizontally { -it },
                        exit = androidx.compose.animation.slideOutHorizontally { -it }
                    ) {
                        W7NavPane(
                            favs, vols, dir,
                            Modifier.width(200.dp).fillMaxHeight().pointerInput(Unit) {
                                detectHorizontalDragGestures { _, dx -> if (dx < -8f) navOpen = false }
                            },
                            onBin = if (browse) ({ navOpen = false; showBin = true }) else null, recents = recents
                        ) { navOpen = false; go(it) }
                    }
                }
                if (showSelectHelp) W7HelpPanel(
                    "Mode Pilih", "Pilih beberapa berkas atau folder sekaligus, lalu jalankan aksi dari menu Alat.",
                    listOf(
                        "Centang" to "Ketuk berkas atau folder untuk mencentang atau melepas centang.",
                        "Buka folder" to "Ketuk tanda \u203A di ujung baris untuk masuk ke folder.",
                        "Lintas folder" to "Centang tetap tersimpan saat Anda berpindah folder.",
                        "Geser baris" to "Geser ke kanan untuk mencentang atau melepas; geser ke kiri untuk membuka menu Alat bagi baris itu.",
                        "Pilih cepat" to "Di menu ikon kisi (kanan bawah): Pilih semua mencentang seluruh isi folder ini; Kosongkan pilihan menghapus semua centang.",
                        "Selesai" to "Pilih Selesai memilih di menu ikon kisi atau tekan tombol Kembali untuk keluar dari Mode Pilih."
                    )
                ) { showSelectHelp = false } // [HELP-PANEL]
                if (message.isNotBlank()) W7Toast(
                    message, messageOk,
                    actionLabel = if (undo != null && !busy) "Urungkan" else null,
                    onAction = {
                        val undoItems = undo
                        if (undoItems != null) {
                            undo = null
                            message = ""
                            runOp(op = { _ -> "Dipulihkan: " + TrashBin.restore(undoItems, volDirs).size + " item" })
                        }
                    },
                    onDismiss = { message = "" },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Win7.Hairline))
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    if (browse) {
                        val dkTarget = selected?.file ?: dir
                        val dkFiles = if (multi && picked.isNotEmpty()) picked.map { File(it) } else listOf(dkTarget)
                        val dkMedia = onPlay != null && dkFiles.isNotEmpty() && dkFiles.all { it.isFile && it.extension.lowercase() in W7_MEDIA }
                        val dkText = !dkMedia && onOpenInCode != null && dkFiles.size == 1 && dkFiles[0].isFile && ExplorerList.looksText(dkFiles[0])
                        val dkZip = !dkMedia && !dkText && dkFiles.size == 1 && dkFiles[0].isFile && dkFiles[0].extension.equals("zip", ignoreCase = true)
                        val dkArc = !dkMedia && !dkText && !dkZip && dkFiles.size == 1 && dkFiles[0].isFile && ArchiveRoute.supports(dkFiles[0].name) // [ARCHIVE]
                        W7BrowseDock(
                            debian = if (dkFiles.size == 1) isVisibleInDebian?.invoke(dkFiles[0]) else null,
                            quickLabel = if (dkMedia) "Putar" else if (dkText) "Edit" else if (dkZip) "Buka ZIP" else if (dkArc) "Buka arsip" else "Properti",
                            insertEnabled = onInsertPaths != null,
                            terminalEnabled = onOpenTerminal != null,
                            onQuick = {
                                if (dkMedia) {
                                    val fn = onPlay
                                    if (fn != null) scope.launch {
                                        val ok = fn(dkFiles)
                                        say(if (ok) "Memutar di mpv: " + w7Names(dkFiles) else "Tidak ada media yang bisa diputar", ok)
                                    }
                                } else if (dkText) onOpenInCode?.invoke(dkFiles)
                                else if (dkZip) sheet = W7Sheet.ZipView(dkFiles[0])
                                else if (dkArc) sheet = W7Sheet.Archive(dkFiles[0], null) // [ARCHIVE]
                                else sheet = W7Sheet.Info(dkFiles)
                            },
                            onInsert = { onInsertPaths?.invoke(dkFiles) },
                            onTerminal = { onOpenTerminal?.invoke(dkTarget) },
                            onOpen = { if (dkTarget.isDirectory) go(dkTarget) else smartOpen(dkTarget) },
                            onHelp = if (multi) ({ showSelectHelp = true }) else null, // [HELP-PANEL]
                            modeLabel = if (!multi) null else if (picked.isEmpty()) "Mode Pilih" else picked.size.toString() + " dipilih",
                            menu = { // [DROP-MENU] satu ikon kisi di kanan bawah (dekat status Debian) membuka menu drop-up
                                Box {
                                    W7DropButton(active = menuOpen || sortOpen || toolsOpen) { menuOpen = !menuOpen }
                                    if (menuOpen) Popup(
                                        alignment = Alignment.BottomEnd,
                                        offset = IntOffset(0, -with(density) { 44.dp.roundToPx() }),
                                        onDismissRequest = { menuOpen = false },
                                        properties = PopupProperties(focusable = true)
                                    ) {
                                        Column( // [DROP-SCROLL] tinggi maks 70% layar; isi bisa digulir bila tidak muat
                                            Modifier.width(230.dp)
                                                .heightIn(max = w7PopupMaxHeight())
                                                .background(Win7.Input).border(1.dp, Win7.FrameLine)
                                                .verticalScroll(rememberScrollState())
                                        ) {
                                            if (portrait) W7MenuItem(W7Ico.NAV, "Panel lokasi", true) { menuOpen = false; navOpen = !navOpen } // [DROP-HAMBURGER]
                                            if (portrait) W7MenuDivider() // [DROP-DIVIDER]
                                            W7MenuItem(W7Ico.REFRESH, "Segarkan", true) { menuOpen = false; tick++ }
                                            W7MenuItem(W7Ico.SORT, "Urut\u2026", true) { menuOpen = false; sortOpen = true }
                                            W7MenuDivider() // [DROP-DIVIDER]
                                            W7MenuItem(W7Ico.SELECT, if (multi) "Selesai memilih" else "Pilih", !busy) {
                                                menuOpen = false
                                                multi = !multi; picked = emptySet(); selected = null; fileName = ""
                                            }
                                            if (multi) W7MenuItem(W7Ico.SELECT_ALL, "Pilih semua", !busy) {
                                                menuOpen = false
                                                val here = (entries ?: emptyList()).map { it.file.path }.toSet()
                                                picked = if (here.isNotEmpty() && picked.containsAll(here)) picked - here else picked + here
                                            }
                                            if (picked.isNotEmpty()) W7MenuItem(W7Ico.CLEAR, "Kosongkan pilihan", !busy) { menuOpen = false; picked = emptySet() }
                                            W7MenuDivider() // [DROP-DIVIDER]
                                            W7MenuItem(W7Ico.MORE, "Alat\u2026", !busy) { menuOpen = false; toolsOpen = true }
                                        }
                                    }
                                    sortPopup(true)
                                    toolsPopup()
                                }
                            }
                        )
                    } else {
                    if (pickFolder) Text(
                        "Folder: " + dir.path, color = Win7.Ink, fontSize = 12.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth()
                    ) else Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Nama file:", color = Win7.Ink, fontSize = 12.sp, modifier = Modifier.width(66.dp))
                        BasicTextField(
                            value = fileName,
                            onValueChange = { fileName = it; message = "" },
                            singleLine = true,
                            textStyle = TextStyle(color = Win7.Ink, fontSize = 13.sp),
                            cursorBrush = SolidColor(Win7.Ink),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { confirm() }),
                            modifier = Modifier.weight(1f).height(26.dp).tapToFocus().background(Win7.Input)
                                .border(1.dp, Win7.Field).padding(horizontal = 6.dp),
                            decorationBox = { inner -> Box(Modifier.fillMaxSize(), Alignment.CenterStart) { inner() } }
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    if (!pickFolder) Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(66.dp))
                        Row(
                            Modifier.weight(1f).height(26.dp)
                                .background(Brush.verticalGradient(listOf(Win7.BarTop, Win7.BarBottom)))
                                .border(1.dp, Win7.Field).clickable { comboOpen = !comboOpen }
                                .padding(start = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(kind.label, color = Win7.Ink, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                            Text("\u25BE", color = Win7.TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
                        }
                    }
                    if (comboOpen) Column(
                        Modifier.padding(start = 66.dp).fillMaxWidth().background(Win7.Input).border(1.dp, Win7.Field)
                    ) {
                        W7Kind.values().forEach { k ->
                            Text(
                                k.label, color = Win7.Ink, fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth()
                                    .background(if (k == kind) Win7.SelFill else Win7.Input)
                                    .clickable { kind = k; comboOpen = false; selected = null }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    }
                    if (busy) Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).height(6.dp).background(Win7.Hairline)) {
                            val p = progress
                            Box(Modifier.fillMaxHeight().fillMaxWidth(p?.coerceIn(0f, 1f) ?: 0.25f).background(Win7.DefaultLine))
                        }
                        Text(
                            "Hentikan", color = Win7.NavHeader, fontSize = 12.sp,
                            modifier = Modifier.clickable { cancelFlag.set(true) }.padding(start = 10.dp, top = 4.dp, bottom = 4.dp)
                        )
                    }
                    if (!browse) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        Win7Button(
                            if (pickFolder) "Pilih folder" else "Buka", isDefault = true,
                            enabled = if (pickFolder) granted else fileName.isNotBlank(),
                            onClick = { if (pickFolder) onPickFolder(dir) else confirm() }
                        )
                        if (!asScreen) {
                            Spacer(Modifier.width(8.dp))
                            Win7Button("Batal", onClick = onDismiss)
                        }
                    }
                }
            }
        }
    }
    if (asScreen) {
        BackHandler(onBack = backAction)
        content()
    } else Dialog(
        onDismissRequest = backAction,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) { content() }
}

/** Keterangan hasil operasi: melayang di atas daftar berkas, maks. 3 baris, hilang sendiri atau saat diketuk. */
@Composable
private fun W7Toast(
    text: String, ok: Boolean, actionLabel: String?, onAction: () -> Unit, onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(6.dp)
    val tone = if (ok) Color(0xFF4CAF50) else Color(0xFFFF8A80)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
            .clip(shape).background(Color(0xF2202830), shape).border(1.dp, tone.copy(alpha = 0.6f), shape)
            .clickable(onClick = onDismiss).padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("\u25CF ", color = tone, fontSize = 11.sp)
        Text(
            text, color = Color(0xFFEFF3F6), fontSize = 12.sp, maxLines = 3,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        if (actionLabel != null) W7Cmd(actionLabel, true, onAction)
    }
}

/** [HELP-PANEL] Tombol bantuan ringkas: lingkaran emas berisi "?" (hemat tempat di bilah alat). */
private val W7_HELP_YELLOW = Color(0xFFFFF176) // kuning muda

@Composable
internal fun W7HelpIcon(onClick: () -> Unit) {
    Box(Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 5.dp)) {
        Box(
            Modifier.size(15.dp).border(1.dp, W7_HELP_YELLOW, RoundedCornerShape(8.dp)),
            Alignment.Center
        ) { Text("?", color = W7_HELP_YELLOW, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
    }
}

/** [HELP-PANEL] Layar bantuan: judul, ringkasan, lalu daftar istilah-penjelasan yang bisa digulir. */
@Composable
internal fun W7HelpPanel(
    title: String, summary: String, items: List<Pair<String, String>>, onDismiss: () -> Unit
) {
    W7Panel(title, onDismiss) {
        Text(summary, color = Win7.TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            items.forEach { (term, desc) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        term, color = MolinaTheme.Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(84.dp)
                    )
                    Text(desc, color = Win7.Ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Win7Button("Tutup", isDefault = true) { onDismiss() }
        }
    }
}

@Composable
private fun W7Cmd(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (enabled) Win7.NavHeader else Win7.TextDim, fontSize = 12.sp,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
private fun W7NavPane(favs: List<W7Place>, vols: List<W7Place>, dir: File, modifier: Modifier, onBin: (() -> Unit)? = null, recents: List<File> = emptyList(), onGo: (File) -> Unit) {
    Column(modifier.background(Win7.NavBg).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        W7NavHeader("Favorit", true)
        favs.forEach { p -> W7NavItem(p.label, dir == p.dir, { if (p.molina) Win7MolinaFolder(16.dp) else Win7Folder(16.dp) }) { onGo(p.dir) } }
        if (recents.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            W7NavHeader("Terakhir", false)
            recents.forEach { r -> W7NavItem(r.name.ifEmpty { "/" }, dir == r, { Win7Folder(16.dp) }) { onGo(r) } }
        }
        Spacer(Modifier.height(8.dp))
        W7NavHeader("Komputer", false)
        vols.forEach { p -> W7NavItem(p.label, dir == p.dir, { Win7Drive(16.dp) }) { onGo(p.dir) } }
        if (onBin != null) {
            Spacer(Modifier.height(8.dp))
            W7NavItem("Sampah", false, { Win7BinIcon(16.dp) }) { onBin() }
        }
    }
}

@Composable
private fun W7NavHeader(label: String, star: Boolean) {
    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (star) Text("\u2605 ", color = Color(0xFFE0A800), fontSize = 12.sp)
        Text(label, color = Win7.NavHeader, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun W7NavItem(label: String, active: Boolean, icon: @Composable () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(2.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp)
            .then(if (active) Modifier.background(Win7.SelFill, shape).border(1.dp, Win7.SelLine, shape) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(5.dp))
        Text(label, color = Win7.Ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun W7FileArea(
    modifier: Modifier,
    granted: Boolean,
    entries: List<W7Entry>?,
    key: ExplorerList.Key,
    asc: Boolean,
    selected: W7Entry?,
    multi: Boolean = false,
    picked: Set<String> = emptySet(),
    recentRefs: List<String> = emptyList(),
    emptyNote: String = "Folder ini kosong.",
    compact: Boolean = false,
    onSwipeRight: ((W7Entry) -> Unit)? = null,
    onSwipeLeft: ((W7Entry) -> Unit)? = null,
    onSort: (ExplorerList.Key) -> Unit,
    onOpen: (W7Entry) -> Unit,
    onEnter: (W7Entry) -> Unit = {},
    onAsk: () -> Unit
) {
    BoxWithConstraints(modifier.background(Win7.Input)) {
        val wide = !compact && maxWidth >= 380.dp
        Column(Modifier.fillMaxSize()) {
            if (wide) Row(
                Modifier.fillMaxWidth().height(24.dp)
                    .background(Brush.verticalGradient(listOf(Win7.BarTop, Win7.BarBottom))),
                verticalAlignment = Alignment.CenterVertically
            ) {
                W7HeadCell("Nama", ExplorerList.Key.NAME, key, asc, Modifier.weight(1f), onSort)
                if (wide) W7HeadCell("Diubah", ExplorerList.Key.DATE, key, asc, Modifier.width(112.dp), onSort)
                W7HeadCell("Ukuran", ExplorerList.Key.SIZE, key, asc, Modifier.width(76.dp), onSort)
            }
            if (wide) Box(Modifier.fillMaxWidth().height(1.dp).background(Win7.Hairline))
            when {
                !granted -> W7Note("Izin penyimpanan diperlukan untuk menjelajah berkas. Bila tombol tidak merespons, aktifkan izin Penyimpanan di Setelan Android.") {
                    Spacer(Modifier.height(10.dp))
                    Win7Button("Izinkan akses", onClick = onAsk)
                }
                entries == null -> W7Note("Folder ini tidak dapat dibuka.") {}
                entries.isEmpty() -> W7Note(emptyNote) {}
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.file.path }) { e ->
                        val on = e.file.path in picked
                        W7EntryRow(
                            e, if (multi) on else e == selected, wide, onOpen, if (multi) on else null, if (multi) onEnter else null,
                            played = if (e.isDir) -1 else recentRefs.indexOf(w7Norm(e.file.path)),
                            onSwipeRight = onSwipeRight, onSwipeLeft = onSwipeLeft
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun W7HeadCell(label: String, me: ExplorerList.Key, key: ExplorerList.Key, asc: Boolean, modifier: Modifier, onSort: (ExplorerList.Key) -> Unit) {
    Row(
        modifier.fillMaxHeight().clickable { onSort(me) }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Win7.NavHeader, fontSize = 12.sp, maxLines = 1)
        if (key == me) Text(if (asc) " \u25B2" else " \u25BC", color = Win7.TextDim, fontSize = 8.sp)
    }
}

@Composable
private fun W7Note(text: String, extra: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text(text, color = Win7.TextDim, fontSize = 13.sp, textAlign = TextAlign.Center)
        extra()
    }
}

@Composable
private fun W7EntryRow(
    e: W7Entry, selected: Boolean, wide: Boolean, onOpen: (W7Entry) -> Unit,
    checked: Boolean? = null, onEnter: ((W7Entry) -> Unit)? = null, played: Int = -1,
    onSwipeRight: ((W7Entry) -> Unit)? = null, onSwipeLeft: ((W7Entry) -> Unit)? = null // [W7-SWIPE1]
) {
    val shape = RoundedCornerShape(2.dp)
    var swipeOff by remember { mutableStateOf(0f) }
    var swipeDir by remember { mutableStateOf(0) }
    val swR by rememberUpdatedState(onSwipeRight)
    val swL by rememberUpdatedState(onSwipeLeft)
    val video = e.file.extension.lowercase() !in W7_AUDIO
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 1.dp)
            .offset { IntOffset(swipeOff.toInt(), 0) }
            .then(if (swipeDir != 0) Modifier.background(Win7.SelLine.copy(alpha = 0.3f), shape) else Modifier)
            .then(
                if (selected) Modifier
                    .background(Brush.verticalGradient(listOf(Color(0xFF16324D), Color(0xFF0F2438))), shape)
                    .border(1.dp, Win7.SelLine, shape)
                else Modifier
            )
            .clickable { onOpen(e) }
            .then(
                if (onSwipeRight != null || onSwipeLeft != null) Modifier.pointerInput(e.file.path) {
                    var total = 0f
                    val thr = 64.dp.toPx()
                    val cap = 28.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f; swipeDir = 0 },
                        onDragEnd = {
                            val d = swipeDir
                            swipeOff = 0f; swipeDir = 0
                            if (d > 0) swR?.invoke(e) else if (d < 0) swL?.invoke(e)
                        },
                        onDragCancel = { swipeOff = 0f; swipeDir = 0 },
                        onHorizontalDrag = { _, dx ->
                            total += dx
                            swipeOff = (total * 0.4f).coerceIn(-cap, cap)
                            swipeDir = if (total > thr && swR != null) 1 else if (total < -thr && swL != null) -1 else 0
                        }
                    )
                } else Modifier
            )
            .heightIn(min = if (wide) 26.dp else 36.dp)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (checked != null) {
            Text(if (checked) "[x]" else "[  ]", color = Win7.NavHeader, fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
        }
        if (e.isDir) Win7Folder(20.dp) else W7FileIcon(e.file.extension, 22.dp)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, color = Win7.Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (played >= 0) W7PlayedChip(played)
            }
            if (e.rel != null) Text(e.rel, color = Win7.TextDim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else if (!wide) Text(
                if (e.isDir) w7Date(e.modified) else w7Date(e.modified) + "  \u00B7  " + w7Size(e.size),
                color = Win7.TextDim, fontSize = 10.sp, maxLines = 1
            )
        }
        if (wide) Text(w7Date(e.modified), color = Win7.TextDim, fontSize = 12.sp, maxLines = 1, modifier = Modifier.width(112.dp))
        if (wide || e.rel != null) Text(
            if (e.isDir) "" else w7Size(e.size), color = Win7.TextDim, fontSize = 12.sp, maxLines = 1,
            textAlign = TextAlign.End, modifier = Modifier.width(if (wide) 76.dp else 68.dp)
        )
        if (checked != null && e.isDir && onEnter != null) {
            Box(
                Modifier.width(40.dp).heightIn(min = 32.dp).clickable { onEnter(e) },
                contentAlignment = Alignment.Center
            ) { Text("\u203A", color = Win7.NavHeader, fontSize = 22.sp) }
        }
    }
}

@Composable
internal fun W7Panel(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(shape)
                .background(Brush.verticalGradient(listOf(Win7.FrameTop, Win7.FrameBottom)), shape)
                .border(1.dp, Win7.FrameLine, shape).padding(14.dp)
        ) {
            Text(title, color = Win7.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun W7SearchBox(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    Row(
        modifier.height(26.dp).background(Win7.Input).border(1.dp, Win7.Field).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Win7.Ink, fontSize = 12.sp),
            cursorBrush = SolidColor(Win7.Ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.weight(1f).tapToFocus(),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), Alignment.CenterStart) {
                    if (value.isEmpty()) Text("Cari nama\u2026", color = Win7.TextDim, fontSize = 12.sp, maxLines = 1)
                    inner()
                }
            }
        )
        if (value.isNotEmpty()) {
            Text("\u00D7", color = Win7.TextDim, fontSize = 14.sp, modifier = Modifier.clickable { onChange("") }.padding(start = 6.dp))
        }
    }
}

/** Jenis error + 8 baris tumpukan pertama, untuk Settings > Log (membantu mendiagnosis kegagalan di perangkat). */
private fun w7Trace(t: Throwable): String =
    t.javaClass.name + ": " + (t.message ?: "-") + "\n" + t.stackTrace.take(8).joinToString("\n") { "  at " + it }

private fun w7Norm(p: String): String =
    if (p.startsWith("/sdcard/")) "/storage/emulated/0/" + p.removePrefix("/sdcard/") else p

/** Chip riwayat putar: 0 = "Terakhir diputar" (aksen Cinema), lainnya = "Diputar" (redup). */
@Composable
private fun W7PlayedChip(rank: Int) {
    val latest = rank == 0
    val tone = if (latest) MolinaTheme.Cinema else Win7.TextDim
    Text(
        if (latest) "Terakhir diputar" else "Diputar", color = tone, fontSize = 9.sp, maxLines = 1,
        modifier = Modifier.padding(start = 6.dp)
            .border(1.dp, tone.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}
