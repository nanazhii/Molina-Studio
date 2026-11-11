package com.molina.studio.terminal

import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.core.common.MolinaPaths
import com.molina.core.common.TrashBin
import com.molina.studio.cinema.ExplorerRecent
import com.molina.studio.cinema.FileOps
import com.molina.studio.cinema.W7ConfirmDialog
import com.molina.studio.cinema.W7FileIcon
import com.molina.studio.cinema.W7InfoDialog
import com.molina.studio.cinema.W7NameDialog
import com.molina.studio.cinema.Win7BackOrb
import com.molina.studio.cinema.Win7Folder
import com.molina.studio.settings.MolinaExplorer
import com.molina.studio.ui.MolinaTheme
import com.molina.studio.ui.SwipeDirection
import com.molina.studio.ui.onHorizontalSwipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Brand = MolinaTheme.Brand
// [DRAWER-HAMBURGER] lebar drawer: porsi layar, dijepit ke rentang ini (header butuh sekitar 200dp)
private val DrawerMinWidth = 248.dp // [DRAWER-FRAME] 200 -> 224 -> 248 [DRAWER-GREEN]
private val DrawerMaxWidth = 288.dp // 240 -> 264 -> 288
private const val DrawerFraction = 0.40f
private val DrawerFrameColor = Color(0xFF00E676).copy(alpha = 0.6f) // [DRAWER-GREEN] hijau transparan (60%)

/** [FILE-DRAWER2] Keadaan tree dipegang di sini (bukan remember) agar folder utama dan folder terbuka bertahan lintas pembukaan drawer. */
private object FileTreeStore {
    var root by mutableStateOf<File?>(null)
    val children = mutableStateMapOf<String, List<File>>()
    val expanded = mutableStateListOf<String>()
}

private sealed class Sheet {
    class Rename(val f: File) : Sheet()
    class Delete(val f: File) : Sheet()
    class Info(val f: File) : Sheet()
    class NewDir(val parent: File) : Sheet() // [FILE-DRAWER3]
}

/** Isi folder: folder dulu, lalu berkas, urut nama. Memblokir: panggil dari thread IO. */
private fun listDir(dir: File): List<File> =
    dir.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()

private fun toast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

private fun stemEnd(f: File): Int =
    if (f.isDirectory) f.name.length else f.name.lastIndexOf('.').let { if (it > 0) it else f.name.length }

/** Volume penyimpanan untuk Recycle Bin (internal + kartu SD), sama dengan Penjelajah. */
private fun volumeDirs(): List<File> {
    val out = mutableListOf(Environment.getExternalStorageDirectory())
    File("/storage").listFiles()?.forEach {
        if (it.isDirectory && it.canRead() && it.name != "emulated" && it.name != "self") out.add(it)
    }
    return out
}

/** [FILE-DRAWER3] Label chip volume: internal = "Internal", kartu SD = nama folder volume. */
private fun volumeLabel(v: File): String =
    if (v.path == Environment.getExternalStorageDirectory().path) "Internal" else v.name

/** Jalur yang dilindungi dari hapus: akar volume, folder Recycle Bin, dan folder Molina. */
private fun trashGuard(vols: List<File>): Set<String> =
    vols.map { File(it, TrashBin.DIR).absolutePath }.toSet() + vols.map { it.absolutePath }.toSet() +
        File(Environment.getExternalStorageDirectory(), MolinaPaths.APP_FOLDER).absolutePath

/** cd ke [dir] di sesi aktif. Menolak bila tak ada sesi, program layar penuh berjalan, atau path tak aman. */
private fun cdHere(ctx: Context, dir: File): Boolean {
    val em = TerminalSessions.currentSession?.emulator
    val path = MolinaExplorer.debianPath(dir)
    return when {
        em == null -> { toast(ctx, "Belum ada sesi terminal aktif."); false }
        em.isAlternateBufferActive -> { toast(ctx, "Terminal sedang menjalankan program layar penuh. Keluar dulu."); false }
        !MolinaExplorer.visibleInDebian(dir) -> { toast(ctx, "Folder ini tidak terlihat di Debian."); false }
        path.any { it.code < 32 || it.code == 127 } -> { toast(ctx, "Nama folder mengandung karakter kontrol."); false }
        else -> { TerminalActions.send("cd " + MolinaExplorer.quote(path) + "\r"); true }
    }
}

/**
 * [FILE-DRAWER] Drawer berkas Terminal (menggantikan drawer sesi): tree folder ala editor Code.
 * Ketuk folder = buka/tutup, ketuk berkas = edit di nano, tekan lama = menu (cd, ganti nama, salin path, properti, hapus).
 * Dibuka lewat tombol ≡ (hamburger) di baris tombol ekstra; ditutup dengan geser ke kiri, ketuk latar, atau Back.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalFileDrawer(open: Boolean, onClose: () -> Unit) {
    if (!open) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val swallow = remember { MutableInteractionSource() }
    val drawerWidth = maxOf(DrawerMinWidth, minOf(DrawerMaxWidth, (LocalConfiguration.current.screenWidthDp * DrawerFraction).dp))
    val storageRoot = remember { Environment.getExternalStorageDirectory() }
    val vols = remember { volumeDirs() } // [FILE-DRAWER3] internal + kartu SD, dibaca setiap drawer dibuka
    val st = FileTreeStore
    val initialRoot = remember { ExplorerRecent.last(ctx) ?: storageRoot }
    val root: File = st.root ?: initialRoot
    var menuFor by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var newFile by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)

    fun load(dir: File) {
        scope.launch { st.children[dir.path] = withContext(Dispatchers.IO) { listDir(dir) } }
    }
    fun setRoot(dir: File) {
        st.root = dir
        st.expanded.clear()
        load(dir)
    }
    fun afterChange(parent: File?, oldPath: String) {
        st.expanded.removeAll { it == oldPath || it.startsWith(oldPath + "/") }
        st.children.keys.filter { it == oldPath || it.startsWith(oldPath + "/") }.forEach { st.children.remove(it) }
        parent?.let { load(it) }
    }
    // Segarkan folder utama dan folder yang masih terbuka setiap drawer dibuka; buang yang sudah tidak ada.
    LaunchedEffect(root.path) {
        for (p in listOf(root.path) + st.expanded.toList()) {
            val d = File(p)
            if (d.isDirectory) st.children[p] = withContext(Dispatchers.IO) { listDir(d) }
            else { st.children.remove(p); st.expanded.remove(p) }
        }
    }

    val rows = ArrayList<Pair<File, Int>>()
    fun walk(dir: File, depth: Int) {
        st.children[dir.path]?.forEach { f ->
            rows.add(f to depth)
            if (f.isDirectory && f.path in st.expanded) walk(f, depth + 1)
        }
    }
    walk(root, 0)
    val canUp = root.parentFile != null && vols.none { it.path == root.path } // [FILE-DRAWER3] berhenti di akar volume
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
        )
        Column(
            Modifier.fillMaxHeight().width(drawerWidth)
                .background(MaterialTheme.colorScheme.surface)
                .drawWithContent { // [DRAWER-FRAME] bingkai hijau transparan di atas, bawah, dan kanan; tepi kiri tanpa garis
                    drawContent()
                    val w = 1.dp.toPx()
                    val c = DrawerFrameColor
                    drawRect(c, Offset.Zero, Size(size.width, w))
                    drawRect(c, Offset(0f, size.height - w), Size(size.width, w))
                    drawRect(c, Offset(size.width - w, 0f), Size(w, size.height))
                }
                .clickable(interactionSource = swallow, indication = null, onClick = {})
                .onHorizontalSwipe(22.dp, SwipeDirection.Left) { onClose() }
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clickable(enabled = canUp) { // [ORB-UNIFY] bulatan kembali sama dengan Pengaturan
                        root.parentFile?.let { setRoot(it) }
                    },
                    contentAlignment = Alignment.Center
                ) { Win7BackOrb(canUp) }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(
                        root.name.ifEmpty { "/" }, color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        root.path, color = dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (vols.size > 1) { // [FILE-DRAWER3] chip pilihan volume
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    vols.forEach { v ->
                        val on = root.path == v.path || root.path.startsWith(v.path + "/")
                        Box(
                            Modifier.padding(end = 6.dp)
                                .background(if (on) Brand.copy(alpha = 0.25f) else dim.copy(alpha = 0.12f))
                                .clickable { if (root.path != v.path) setRoot(v) }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) { Text(volumeLabel(v), color = if (on) Brand else dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(dim.copy(alpha = 0.3f)))
            if (rows.isEmpty() && st.children[root.path] != null) {
                Text("Folder kosong atau tidak bisa dibaca.", color = dim, fontSize = 12.sp, modifier = Modifier.padding(20.dp))
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) { // [DRAWER-BOTTOM]
                items(rows, key = { it.first.path }) { (f, depth) ->
                    val isDir = f.isDirectory
                    val isOpen = f.path in st.expanded
                    Box {
                        Row(
                            Modifier.fillMaxWidth().height(36.dp)
                                .alpha(if (f.name.startsWith(".")) 0.6f else 1f)
                                .combinedClickable(
                                    onClick = {
                                        if (isDir) {
                                            if (isOpen) st.expanded.remove(f.path)
                                            else { st.expanded.add(f.path); if (st.children[f.path] == null) load(f) }
                                        } else if (MolinaExplorer.editInTerminal(ctx, f)) onClose()
                                    },
                                    onLongClick = { menuFor = f.path }
                                )
                                .padding(start = (6 + depth * 14).dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (isDir) { if (isOpen) "\u25BE" else "\u25B8" } else "",
                                color = Brand, fontSize = 12.sp, modifier = Modifier.width(14.dp)
                            )
                            if (isDir) Win7Folder(20.dp) else W7FileIcon(f.extension, 20.dp) // ikon Molina, sama dengan Penjelajah
                            Spacer(Modifier.width(8.dp))
                            Text(
                                f.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (isDir) Brand else MaterialTheme.colorScheme.onSurface,
                                fontFamily = FontFamily.Monospace, fontSize = 13.sp
                            )
                        }
                        DropdownMenu(expanded = menuFor == f.path, onDismissRequest = { menuFor = null }) {
                            if (isDir) {
                                DropdownMenuItem(text = { Text("cd ke folder ini") }, onClick = { menuFor = null; if (cdHere(ctx, f)) onClose() })
                                DropdownMenuItem(text = { Text("Jadikan folder utama") }, onClick = { menuFor = null; setRoot(f) })
                                DropdownMenuItem(text = { Text("Folder baru di sini") }, onClick = { menuFor = null; sheet = Sheet.NewDir(f) }) // [FILE-DRAWER3]
                            } else {
                                DropdownMenuItem(text = { Text("Edit di nano") }, onClick = { menuFor = null; if (MolinaExplorer.editInTerminal(ctx, f)) onClose() })
                            }
                            DropdownMenuItem(text = { Text("Ganti nama") }, onClick = { menuFor = null; sheet = Sheet.Rename(f) })
                            DropdownMenuItem(text = { Text("Salin path") }, onClick = { menuFor = null; MolinaExplorer.copyPath(ctx, f) })
                            DropdownMenuItem(text = { Text("Properti") }, onClick = { menuFor = null; sheet = Sheet.Info(f) })
                            DropdownMenuItem(text = { Text("Hapus", color = MolinaTheme.Bad) }, onClick = { menuFor = null; sheet = Sheet.Delete(f) })
                        }
                    }
                }
            }
            // [DRAWER-MERGE] Satu tombol "Berkas baru"; dialognya menawarkan Buat (kosong) atau Buka di nano.
            Box(Modifier.fillMaxWidth().height(1.dp).background(dim.copy(alpha = 0.3f)))
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp)
                    .height(44.dp).clickable { newFile = true }, // [NANO-NEW]
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
            ) {
                Box(contentAlignment = Alignment.Center) {
                    W7FileIcon("", 22.dp)
                    Text("+", color = Brand, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomEnd))
                }
                Spacer(Modifier.width(6.dp))
                Text("Berkas baru", color = Brand, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    when (val s = sheet) {
        null -> Unit
        is Sheet.Rename -> W7NameDialog(
            title = if (s.f.isDirectory) "Ubah nama folder" else "Ubah nama berkas",
            initial = s.f.name, selectEnd = stemEnd(s.f), okLabel = "OK",
            onCancel = { sheet = null }
        ) { name ->
            sheet = null
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { FileOps.rename(s.f, name) } }
                r.onSuccess { nf -> afterChange(s.f.parentFile, s.f.path); toast(ctx, "Nama diubah: " + nf.name) }
                    .onFailure { toast(ctx, it.message ?: "Gagal mengubah nama") }
            }
        }
        is Sheet.Delete -> W7ConfirmDialog(listOf(s.f), false, onCancel = { sheet = null }) {
            sheet = null
            scope.launch {
                val vols = volumeDirs()
                val r = withContext(Dispatchers.IO) { runCatching { TrashBin.trash(listOf(s.f), vols, trashGuard(vols)) } }
                r.onSuccess { afterChange(s.f.parentFile, s.f.path); toast(ctx, "Dipindahkan ke Recycle Bin: " + it.size + " item") }
                    .onFailure { toast(ctx, it.message ?: "Gagal menghapus") }
            }
        }
        is Sheet.Info -> W7InfoDialog(listOf(s.f), MolinaExplorer.visibleInDebian(s.f)) { sheet = null }
        is Sheet.NewDir -> W7NameDialog( // [FILE-DRAWER3]
            title = "Folder baru", initial = "Folder baru", selectEnd = "Folder baru".length, okLabel = "Buat",
            hint = "Di: " + s.parent.path,
            onCancel = { sheet = null }
        ) { name ->
            sheet = null
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { FileOps.mkdir(s.parent, name) } }
                r.onSuccess { nf ->
                    if (s.parent.path != root.path && s.parent.path !in st.expanded) st.expanded.add(s.parent.path)
                    load(s.parent)
                    toast(ctx, "Folder dibuat: " + nf.name)
                }.onFailure { toast(ctx, it.message ?: "Gagal membuat folder") }
            }
        }
    }
    if (newFile) NewNanoFileDialog( // [NANO-NEW] [FILE-DRAWER3] [DRAWER-MERGE] buat di folder aktif drawer: Buat (kosong) atau Buka di nano
        onDismiss = { newFile = false }, onOpened = { newFile = false; onClose() }, baseDir = root,
        onCreated = { nf -> newFile = false; load(root); toast(ctx, "Berkas dibuat: " + nf.name) }
    )
}
