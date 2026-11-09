package com.molina.studio.terminal

import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.cinema.ExplorerRecent
import com.molina.studio.settings.MolinaExplorer
import com.molina.studio.ui.MolinaTheme
import com.molina.studio.ui.SwipeDirection
import com.molina.studio.ui.onHorizontalSwipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Brand = MolinaTheme.Brand
private val DrawerWidth = 280.dp

/** Isi folder: folder dulu, lalu berkas, urut nama. Memblokir: panggil dari thread IO. */
private fun listDir(dir: File): List<File> =
    dir.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()

private fun toast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

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
 * Ketuk folder = buka/tutup, ketuk berkas = edit di nano, tekan lama = menu (cd, salin path, jadikan folder utama).
 * Dibuka lewat tombol ≡ atau geser dari tepi kiri terminal; ditutup dengan geser ke kiri, ketuk latar, atau Back.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalFileDrawer(open: Boolean, onClose: () -> Unit) {
    if (!open) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val swallow = remember { MutableInteractionSource() }
    val storageRoot = remember { Environment.getExternalStorageDirectory() }
    var root by remember { mutableStateOf(ExplorerRecent.last(ctx) ?: storageRoot) }
    val children = remember { mutableStateMapOf<String, List<File>>() }
    val expanded = remember { mutableStateListOf<String>() }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var newFile by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)

    fun load(dir: File) {
        scope.launch { children[dir.path] = withContext(Dispatchers.IO) { listDir(dir) } }
    }
    LaunchedEffect(root) { if (children[root.path] == null) load(root) }

    val rows = ArrayList<Pair<File, Int>>()
    fun walk(dir: File, depth: Int) {
        children[dir.path]?.forEach { f ->
            rows.add(f to depth)
            if (f.isDirectory && f.path in expanded) walk(f, depth + 1)
        }
    }
    walk(root, 0)
    val canUp = root.parentFile != null && root.path != storageRoot.path
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
        )
        Column(
            Modifier.fillMaxHeight().width(DrawerWidth)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(interactionSource = swallow, indication = null, onClick = {})
                .onHorizontalSwipe(22.dp, SwipeDirection.Left) { onClose() }
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).alpha(if (canUp) 1f else 0.4f).clickable(enabled = canUp) {
                        root.parentFile?.let { root = it; expanded.clear(); load(it) }
                    },
                    contentAlignment = Alignment.Center
                ) { Text("\u2191", color = Brand, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
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
                Box(Modifier.size(44.dp).clickable { newFile = true }, contentAlignment = Alignment.Center) { // [NANO-NEW]
                    Icon(Icons.Filled.Edit, contentDescription = "Berkas baru (nano)", tint = Brand, modifier = Modifier.size(22.dp))
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(dim.copy(alpha = 0.3f)))
            if (rows.isEmpty() && children[root.path] != null) {
                Text("Folder kosong atau tidak bisa dibaca.", color = dim, fontSize = 12.sp, modifier = Modifier.padding(20.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.first.path }) { (f, depth) ->
                    val isDir = f.isDirectory
                    val isOpen = f.path in expanded
                    Box {
                        Row(
                            Modifier.fillMaxWidth().height(36.dp)
                                .combinedClickable(
                                    onClick = {
                                        if (isDir) {
                                            if (isOpen) expanded.remove(f.path)
                                            else { expanded.add(f.path); if (children[f.path] == null) load(f) }
                                        } else if (MolinaExplorer.editInTerminal(ctx, f)) onClose()
                                    },
                                    onLongClick = { menuFor = f.path }
                                )
                                .padding(start = (8 + depth * 14).dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (isDir) { if (isOpen) "\u25BE" else "\u25B8" } else "\u00B7",
                                color = Brand, fontSize = 12.sp, modifier = Modifier.width(16.dp)
                            )
                            Text(
                                f.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (isDir) Brand else MaterialTheme.colorScheme.onSurface,
                                fontFamily = FontFamily.Monospace, fontSize = 13.sp
                            )
                        }
                        DropdownMenu(expanded = menuFor == f.path, onDismissRequest = { menuFor = null }) {
                            if (isDir) {
                                DropdownMenuItem(text = { Text("cd ke folder ini") }, onClick = { menuFor = null; if (cdHere(ctx, f)) onClose() })
                                DropdownMenuItem(text = { Text("Jadikan folder utama") }, onClick = { menuFor = null; root = f; expanded.clear(); load(f) })
                            } else {
                                DropdownMenuItem(text = { Text("Edit di nano") }, onClick = { menuFor = null; if (MolinaExplorer.editInTerminal(ctx, f)) onClose() })
                            }
                            DropdownMenuItem(text = { Text("Salin path") }, onClick = { menuFor = null; MolinaExplorer.copyPath(ctx, f) })
                        }
                    }
                }
            }
        }
    }
    if (newFile) NewNanoFileDialog(onDismiss = { newFile = false }, onOpened = { newFile = false; onClose() }) // [NANO-NEW]
}
