package com.molina.explorer

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.isActive
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import com.molina.core.library.ProtectedPaths
import com.molina.core.library.StorageVolumes
import com.molina.core.library.TrashBin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.molina.core.ui.MintFileIcon
import com.molina.core.files.FolderBadge
import com.molina.core.files.FolderBadges
import com.molina.core.ui.FileTreeIconSize
import com.molina.core.ui.MintFolder
// [FOLDER-UNIFORM] impor MintFolderTint dihapus (tidak dipakai lagi)
import com.molina.core.ui.Win7
import com.molina.core.ui.Win7BackOrb
import com.molina.core.ui.Win7Button
import com.molina.core.ui.Win7Close
import com.molina.core.ui.Win7Drive
import com.molina.core.fileops.FileOps
import com.molina.core.fileops.ArchiveRoute
import com.molina.core.fileops.ExplorerList


private enum class W7Kind(val label: String) { MEDIA("Media (video dan audio)"), ALL("Semua file") }

private val W7_AUDIO = com.molina.core.files.FileTypes.AUDIO

private fun w7HasStorage(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED


/** Beranda Debian (/root di rootfs) sebagai akar tambahan agar berkas Debian bisa dikelola dari Penjelajah. */
private fun w7DebianPlaces(env: ExplorerEnv, browse: Boolean): List<W7Place> {
    if (!browse) return emptyList()
    val home = File(env.rootfsDir(), "root")
    return if (home.isDirectory) listOf(W7Place("Debian", home)) else emptyList()
}

private fun w7Drives(): List<W7Place> =
    StorageVolumes.list(Environment.getExternalStorageDirectory()).mapIndexed { i, d -> W7Place(if (i == 0) "Internal" else d.name, d) } // [SSOT] core:library

/** Isi folder lewat [ExplorerList]; pasangan hasil = (daftar, terpotong?) atau null bila folder tak terbaca. */
private fun w7Load(dir: File, query: String, o: ExplorerList.Opts, cancelled: () -> Boolean): Pair<List<W7Entry>, Boolean>? {
    val r = ExplorerList.list(dir, query, o, cancelled) ?: return null
    return Pair(r.items.map { W7Entry(it.file, it.isDir, it.size, it.modified, it.rel) }, r.truncated)
}

private fun w7Size(b: Long) = String.format(W7_ID, "%,d KB", maxOf(1L, (b + 1023) / 1024))

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
    onCdTerminal: ((File) -> Unit)? = null, // [TERMINAL-CD]
    onShare: ((List<File>) -> Boolean)? = null,
    onOpenWith: ((File) -> Boolean)? = null,
    onInsertPaths: ((List<File>) -> Unit)? = null,
    isVisibleInDebian: ((File) -> Boolean)? = null,
    pickFolder: Boolean = false,
    onPickFolder: (File) -> Unit = {},
    onPlay: (suspend (List<File>) -> Boolean)? = null,
    onOpenInCode: ((List<File>) -> Unit)? = null,
    onOpenEditor: (() -> Unit)? = null, // [EDITOR-IN-DROP] masuk Editor layar penuh dari menu drop
    onEditInTerminal: ((File) -> Unit)? = null, // [NANO-EDIT]
    onNewTextInTerminal: ((File, String) -> Boolean)? = null, // [NANO-NEW]
    onSendToScripts: ((File) -> Pair<Boolean, String>)? = null,
    // MOLINA-H13-BRIDGE: (berkas, selesai(daftar asli yang boleh dihapus, pesan)); daftar kosong = jangan hapus apa pun
    onSaveToVault: ((List<File>, (List<File>, String) -> Unit) -> Unit)? = null,
    startDir: File? = null,
    env: ExplorerEnv
) {
    val ctx = LocalContext.current
    var favTick by remember { mutableStateOf(0) } // [FILEOPS-1]
    val favs = remember(favTick) {
        val base = w7Favorites(browse)
        val mine = if (browse) ExplorerFavorites.load(ctx).filter { f -> base.none { it.dir.path == f.path } } else emptyList()
        base + mine.map { W7Place(it.name.ifEmpty { it.path }, it) }
    }
    val vols = remember { w7Drives() + w7DebianPlaces(env, browse) }
    val volDirs = remember(vols) { vols.map { it.dir } }
    var granted by remember { mutableStateOf(w7HasStorage(ctx)) }
    // [FILES-PLAYED] riwayat putar Cinema (terbaru di indeks 0) untuk chip di daftar; ikut tersegarkan lewat version
    val playedRefs = remember(env.played.version) { env.played.refs().map { PlayedOrder.norm(it) } }
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
    var showBin by remember { mutableStateOf(false) }
    var comboOpen by remember { mutableStateOf(false) }
    // [FILEOPS-2] alat berkas Penjelajah: pilih banyak, salin, pindah, tempel, hapus, folder baru, ZIP/ekstrak, ubah nama, favorit, properti
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var clip by remember { mutableStateOf<W7Clip?>(null) }
    var toolsOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) } // [DROP-MENU]
    var sheet by remember { mutableStateOf<W7Sheet?>(null) }
    var multi by remember { mutableStateOf(false) }
    var showSelectHelp by remember { mutableStateOf(false) } // [HELP-PANEL]
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    val guard = remember {
        ProtectedPaths.of(vols.map { it.dir }, File(Environment.getExternalStorageDirectory(), com.molina.core.system.MolinaPaths.APP_FOLDER)) // [SSOT] core:library
    }
    // [EXPLORER-ACTIONS] mesin operasi berkas dipisah dari composable; UI hanya menyajikan state dan memberi hook
    val actions = remember {
        ExplorerActions(
            ctx, scope, volDirs, guard,
            onClearSelection = { selected = null; picked = emptySet(); fileName = "" },
            onReload = { tick++ }
        )
    }
    fun say(text: String, ok: Boolean = false) = actions.say(text, ok)
    fun targets(): List<File> = if (multi) picked.map { File(it) } else listOfNotNull(selected?.file)
    fun runOp(op: (FileOps.Ctl) -> String, onDone: () -> Unit = {}, onFail: (Throwable) -> Unit = {}) = actions.runOp(op, onDone, onFail)

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
        if (deep && actions.message.startsWith("Mencari")) say("")
        entries = r?.first
        if (r != null && r.second) say("Hasil dibatasi " + ExplorerList.MAX_RESULTS + " teratas. Persempit kata kunci.")
    }

    // [FILES-QUEUE] urutan tampil; opsi "terakhir diputar dulu" bersifat stabil (folder tetap di atas, sisanya urutan asal)
    val shown = remember(entries, playedFirst, playedRefs) {
        val es = entries
        if (es == null || !playedFirst) es else PlayedOrder.sort(es, playedRefs) // [PERF-PLAYED] aturannya di PlayedOrder (diuji JVM)
    }

    val roots = vols.map { it.dir.path }
    val canUp = dir.path !in roots && dir.parentFile != null
    // [NAV-HISTORY] riwayat Mundur/Maju antar folder (maks. 50); tombol bulat di kiri atas = Mundur, bila kosong = Naik
    val hist = remember { NavHistory(50) }
    var histTick by remember { mutableStateOf(0) } // NavHistory bukan state Compose: naikkan saat riwayat berubah supaya tombol Mundur ikut tersegarkan
    fun go(to: File) {
        hist.visit(dir.path, to.path)
        histTick++
        dir = to; selected = null; fileName = ""; actions.resetFeedback(); query = ""; if (browse) ExplorerRecent.push(ctx, to)
    } // [PICK-PERSIST] picked bertahan lintas folder
    fun jump(to: File) {
        dir = to; selected = null; fileName = ""; actions.resetFeedback(); query = ""; if (browse) ExplorerRecent.push(ctx, to)
    }
    fun goBack(): Boolean {
        val p = hist.back(dir.path) { File(it).isDirectory }
        histTick++
        if (p == null) return false
        jump(File(p))
        return true
    }
    fun goForward(): Boolean {
        val p = hist.forward(dir.path) { File(it).isDirectory }
        histTick++
        if (p == null) return false
        jump(File(p))
        return true
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
    W7SheetHost(
        sheet, { sheet = it },
        W7SheetEnv(
            ctx = ctx, dir = dir, selected = selected, env = env, actions = actions,
            clipFiles = { clip?.files ?: emptyList() }, openFile = { smartOpen(it) },
            isVisibleInDebian = isVisibleInDebian, onNewTextInTerminal = onNewTextInTerminal,
            onOpenInCode = onOpenInCode, onEditInTerminal = onEditInTerminal,
            onOpenWith = onOpenWith, onOpenTerminal = onOpenTerminal
        )
    )

    val frame = RoundedCornerShape(8.dp)
    // Keterangan hasil operasi melayang di atas daftar dan hilang sendiri (tidak menyita tinggi panel bawah)
    LaunchedEffect(actions.message) {
        if (actions.message.isNotBlank()) {
            delay(if (actions.undo != null) 9000L else (3500L + actions.message.length * 30L).coerceAtMost(9000L))
            actions.dismissMessage()
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
                .border(if (asScreen) 2.dp else 1.dp, if (asScreen) Color(0xFF454A55) else Win7.FrameLine, frame) // [FRAME-BOLD] mode tab Berkas: bingkai 2dp
                .padding(start = 6.dp, end = 6.dp, bottom = 6.dp)
        ) {
            if (!asScreen) Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.Top) {
                Row(Modifier.weight(1f).padding(top = 5.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    MintFolder(16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(if (browse) "Penjelajah Molina" else if (pickFolder) "Buka folder" else "Buka", color = Win7.Ink, fontSize = 13.sp)
                }
                if (showClose) Win7Close(onDismiss)
            }
            Column(Modifier.weight(1f).fillMaxWidth().border(1.dp, Win7.FrameLine).background(Win7.Face)) {
                Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Win7BackOrb(canUp || (histTick >= 0 && hist.canBack)) { if (!goBack()) goUp() }
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
                        env = env, targets = targets(), dir = dir, browse = browse, clip = clip,
                        playedFirst = playedFirst, playedRefs = playedRefs, shown = shown,
                        onPlay = onPlay, onOpenInCode = onOpenInCode, onEditInTerminal = onEditInTerminal, newTextEnabled = onNewTextInTerminal != null, onSendToScripts = onSendToScripts,
                        onOpenWith = onOpenWith, onShare = onShare, onCopyPath = onCopyPath,
                        onSaveToVault = onSaveToVault?.let { fn ->
                            { files: List<File> ->
                                fn(files) { verified, msg ->
                                    say(msg, verified.isNotEmpty())
                                    if (verified.isNotEmpty()) sheet = W7Sheet.Delete(verified, true) // hapus asli: tetap lewat dialog konfirmasi
                                }
                            }
                        },
                        onDismiss = { toolsOpen = false },
                        onClip = { clip = it }, onPlayedFirst = { playedFirst = it },
                        onSheet = { sheet = it }, onFavChanged = { favTick++ },
                        onSay = { t, ok -> say(t, ok) },
                        onRunOp = { op, onDone, onFail -> runOp(op, onDone, onFail) }
                    )
                }
                if (!browse) Row(
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
                        bottomInset = 0.dp, // [DOCK-V3] pil pindah ke dock; daftar tidak lagi perlu ruang cadangan
                        onScrollDir = { }, // [DOCK-V3] pil di dock selalu tampil
                        onSort = { k -> if (key == k) asc = !asc else { key = k; asc = true } },
                        multi = multi, picked = picked, recentRefs = playedRefs,
                        emptyNote = if (query.isNotBlank()) "Tidak ada hasil untuk \"" + query.trim() + "\"" else "Folder ini kosong.",
                        onOpen = { e ->
                            if (multi) picked = if (e.file.path in picked) picked - e.file.path else picked + e.file.path
                            else if (e.isDir) go(e.file)
                            else if (pickFolder) Unit
                            else if (selected?.file == e.file) { if (browse) smartOpen(e.file) else onPick(e.file) }
                            else { selected = e; fileName = e.name; actions.dismissMessage(); if (browse && e.file.isFile) ExplorerRecent.pushFile(ctx, e.file) } // [NANO-EXT]
                        },
                        onEnter = { e -> go(e.file) },
                        onSwipeRight = if (browse) ({ e: W7Entry ->
                            if (!multi) { multi = true; picked = setOf(e.file.path); selected = null; fileName = "" }
                            else picked = if (e.file.path in picked) picked - e.file.path else picked + e.file.path
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
                        "Geser baris" to "Geser ke kanan untuk mencentang atau melepas.",
                        "Pilih cepat" to "Di menu ikon kisi (kanan bawah): Pilih semua mencentang seluruh isi folder ini; Kosongkan pilihan menghapus semua centang.",
                        "Selesai" to "Pilih Selesai memilih di menu ikon kisi atau tekan tombol Kembali untuk keluar dari Mode Pilih."
                    )
                ) { showSelectHelp = false } // [HELP-PANEL]
                if (actions.message.isNotBlank()) W7Toast(
                    actions.message, actions.messageOk,
                    actionLabel = if (actions.undo != null && !actions.busy) "Urungkan" else null,
                    onAction = { actions.undoDelete() },
                    onDismiss = { actions.dismissMessage() },
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
                            quickLabel = if (dkMedia) "Putar" else if (dkText) "Edit" else if (dkZip) "Buka ZIP" else if (dkArc) "Buka arsip" else null, // [DOCK-V3] Properti ada di menu Alat
                            toolsEnabled = !actions.busy,
                            terminalEnabled = onCdTerminal != null || onOpenTerminal != null,
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
                            onTools = { toolsOpen = true },
                            onTerminal = { (onCdTerminal ?: onOpenTerminal)?.invoke(dkTarget) },
                            trailing = { // [DOCK-V3] pil melayang menempati slot bekas Buka
                                W7BrowseFloat(
                        modifier = Modifier,
                        accent = multi, // [FLOAT-GOLD]
                        debian = if (dkFiles.size == 1) isVisibleInDebian?.invoke(dkFiles[0]) else null,
                        modeLabel = if (!multi) null else if (picked.isEmpty()) "Mode Pilih" else picked.size.toString() + " dipilih",
                        onHelp = if (multi) ({ showSelectHelp = true }) else null, // [HELP-PANEL]
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
                                            if (onOpenEditor != null) { // [EDITOR-IN-DROP]
                                                W7MenuItem(W7Ico.CODE, "Editor", true) { menuOpen = false; onOpenEditor() }
                                                W7MenuDivider()
                                            }
                                            if (portrait) W7MenuItem(W7Ico.NAV, "Panel lokasi", true) { menuOpen = false; navOpen = !navOpen } // [DROP-HAMBURGER]
                                            if (portrait) W7MenuDivider() // [DROP-DIVIDER]
                                            W7MenuItem(W7Ico.REFRESH, "Segarkan", true) { menuOpen = false; tick++ }
                                            W7MenuItem(W7Ico.SORT, "Urut\u2026", true) { menuOpen = false; sortOpen = true }
                                            W7MenuDivider() // [DROP-DIVIDER]
                                            W7MenuItem(W7Ico.SELECT, if (multi) "Selesai memilih" else "Pilih", !actions.busy) {
                                                menuOpen = false
                                                multi = !multi; picked = emptySet(); selected = null; fileName = ""
                                            }
                                            if (multi) W7MenuItem(W7Ico.SELECT_ALL, "Pilih semua", !actions.busy) {
                                                menuOpen = false
                                                val here = (entries ?: emptyList()).map { it.file.path }.toSet()
                                                picked = if (here.isNotEmpty() && picked.containsAll(here)) picked - here else picked + here
                                            }
                                            if (picked.isNotEmpty()) W7MenuItem(W7Ico.CLEAR, "Kosongkan pilihan", !actions.busy) { menuOpen = false; picked = emptySet() }
                                        }
                                    }
                                    sortPopup(true)
                                    toolsPopup()
                                }
                            }
                    )
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
                            onValueChange = { fileName = it; actions.dismissMessage() },
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
                    if (actions.busy) Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).height(6.dp).background(Win7.Hairline)) {
                            val p = actions.progress
                            Box(Modifier.fillMaxHeight().fillMaxWidth(p?.coerceIn(0f, 1f) ?: 0.25f).background(Win7.DefaultLine))
                        }
                        Text(
                            "Hentikan", color = Win7.NavHeader, fontSize = 12.sp,
                            modifier = Modifier.clickable { actions.cancel() }.padding(start = 10.dp, top = 4.dp, bottom = 4.dp)
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
    val tone = if (ok) MolinaTheme.Good else MolinaTheme.Bad
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
        favs.forEach { p -> W7NavItem(p.label, dir == p.dir, { if (p.molina) MintFolder(16.dp, FolderBadge.STAR) else MintFolder(16.dp, FolderBadges.of(p.dir.name)) /* [FOLDER-BADGE] sidebar */ }) { onGo(p.dir) } }
        if (recents.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            W7NavHeader("Terakhir", false)
            recents.forEach { r -> W7NavItem(r.name.ifEmpty { "/" }, dir == r, { MintFolder(16.dp) }) { onGo(r) } }
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
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp, // [FLOAT-DOCK]
    onScrollDir: (Boolean) -> Unit = {}, // [FLOAT-INFO] true = gulir ke bawah (sembunyikan pil)
    onSwipeRight: ((W7Entry) -> Unit)? = null,
    onSwipeLeft: ((W7Entry) -> Unit)? = null,
    onSort: (ExplorerList.Key) -> Unit,
    onOpen: (W7Entry) -> Unit,
    onEnter: (W7Entry) -> Unit = {},
    onAsk: () -> Unit
) {
    val onDir by rememberUpdatedState(onScrollDir) // [FLOAT-INFO]
    val scrollConn = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                if (available.y < -6f) onDir(true) else if (available.y > 6f) onDir(false)
                return Offset.Zero
            }
        }
    }
    BoxWithConstraints(modifier.background(Win7.Input).nestedScroll(scrollConn)) {
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
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottomInset)) {
                    items(entries, key = { it.file.path }) { e ->
                        val on = e.file.path in picked
                        W7EntryRow(
                            e, if (multi) on else e == selected, wide, onOpen, if (multi) on else null, if (multi) onEnter else null,
                            played = if (e.isDir) -1 else recentRefs.indexOf(PlayedOrder.norm(e.file.path)),
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
                    .background(
                        Brush.verticalGradient(
                            if (checked != null) listOf(MolinaTheme.Explorer.PickFillTop, MolinaTheme.Explorer.PickFillBottom) // [PICK-RED]
                            else listOf(Color(0xFF16324D), Color(0xFF0F2438))
                        ),
                        shape
                    )
                    .border(1.dp, if (checked != null) MolinaTheme.Explorer.PickLine else Win7.SelLine, shape)
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
            .heightIn(min = if (wide) 30.dp else FileTreeIconSize.RowHeight) // [TREE-ICON] tinggi picker: sama dengan drawer/Code
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (checked != null) {
            Text( // [PICK-X-ONLY] kurung biru, hanya huruf x terpilih yang merah
                androidx.compose.ui.text.buildAnnotatedString {
                    if (checked) {
                        append("[")
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = MolinaTheme.Explorer.PickMark, fontWeight = FontWeight.Bold)) // [PICK-X-BOLD]
                        append("x")
                        pop()
                        append("]")
                    } else append("[  ]")
                },
                color = MolinaTheme.Explorer.PickInk.copy(alpha = if (checked) 1f else 0.6f), fontSize = 12.sp
            )
            Spacer(Modifier.width(6.dp))
        }
        val iconSz = if (wide) 22.dp else FileTreeIconSize.Icon // [TREE-ICON] ikon picker: mode sempit = ukuran baku drawer/Code
        if (e.isDir) MintFolder(iconSz, FolderBadges.of(e.name)) /* [FOLDER-BADGE] daftar: mint seragam, lencana untuk folder penting */ else if (W7Thumb.hasThumb(e.file)) { // [LIST-THUMB] frame video / cover audio lewat W7Thumb (cache LRU, dekode di IO)
            val thumb by produceState<ImageBitmap?>(null, e.file.path) {
                kotlinx.coroutines.delay(250) // debounce: gulir cepat membatalkan sebelum dekode dimulai
                value = W7Thumb.load(e.file)?.asImageBitmap()
            }
            val tb = thumb
            if (tb != null) Image(tb, null, Modifier.size(iconSz).clip(RoundedCornerShape(3.dp)), contentScale = ContentScale.Crop)
            else MintFileIcon(e.file.extension, iconSz)
        } else MintFileIcon(e.file.extension, iconSz)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, color = Win7.Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (played >= 0) W7PlayedChip(played)
            }
            val relText = e.rel
            if (relText != null) Text(relText, color = Win7.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else if (!wide) Text(
                if (e.isDir) w7Date(e.modified) else w7Date(e.modified) + "  \u00B7  " + w7Size(e.size),
                color = Win7.TextDim, fontSize = 12.sp, maxLines = 1
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
            ) { Text("\u203A", color = MolinaTheme.Explorer.PickInk, fontSize = 22.sp) } // [PICK-RED]
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
