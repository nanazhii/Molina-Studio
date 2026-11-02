package com.molina.studio.settings

import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.molina.core.common.TrashBin
import com.molina.studio.terminal.RootfsManager
import com.molina.studio.terminal.TerminalSessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.Locale

private class StorageInfo(
    val rootfs: Long, val aptCache: Long, val appCache: Long, val workspace: Long, val trash: Long,
    val free: Long, val total: Long
)

internal fun fmtBytes(b: Long): String = when {
    b >= (1L shl 30) -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
    b >= (1L shl 20) -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
    b >= (1L shl 10) -> String.format(Locale.US, "%d KB", b shr 10)
    else -> b.toString() + " B"
}

/** Ukuran total folder; symlink tidak diikuti. Berhenti lebih awal bila [cancelled]. */
internal fun dirSize(root: File, cancelled: () -> Boolean): Long {
    var total = 0L
    val stack = ArrayDeque<File>()
    stack.addLast(root)
    while (stack.isNotEmpty() && !cancelled()) {
        val f = stack.removeLast()
        try {
            if (Files.isSymbolicLink(f.toPath())) continue
            if (f.isDirectory) f.listFiles()?.forEach { stack.addLast(it) } else total += f.length()
        } catch (e: Exception) {
        }
    }
    return total
}

/** Hapus berkas/folder tanpa mengikuti symlink. */
internal fun wipe(f: File) {
    try {
        if (!Files.isSymbolicLink(f.toPath()) && f.isDirectory) f.listFiles()?.forEach { wipe(it) }
        f.delete()
    } catch (e: Exception) {
    }
}

internal fun aptArchives(ctx: android.content.Context) = File(RootfsManager.rootfsDir(ctx), "var/cache/apt/archives")

/** Akar yang punya Recycle Bin (.molina-trash): penyimpanan internal, kartu SD, dan beranda Debian. */
internal fun trashVolumes(ctx: android.content.Context): List<File> {
    val out = mutableListOf(Environment.getExternalStorageDirectory())
    File("/storage").listFiles()?.forEach {
        if (it.isDirectory && it.canRead() && it.name != "emulated" && it.name != "self") out.add(it)
    }
    val home = File(RootfsManager.rootfsDir(ctx), "root")
    if (home.isDirectory) out.add(home)
    return out
}

private fun measure(ctx: android.content.Context, cancelled: () -> Boolean): StorageInfo {
    val ws = File(Environment.getExternalStorageDirectory(), TerminalSessions.WORKSPACE_DIR)
    val appCache = (ctx.cacheDir.listFiles() ?: emptyArray()).filter { it.name != "proot-tmp" && !it.name.startsWith("debian-rootfs.tar.gz") }
        .sumOf { dirSize(it, cancelled) }
    val st = StatFs(ctx.filesDir.absolutePath)
    return StorageInfo(
        dirSize(RootfsManager.rootfsDir(ctx), cancelled), dirSize(aptArchives(ctx), cancelled), appCache,
        if (ws.isDirectory) dirSize(ws, cancelled) else 0L,
        runCatching { TrashBin.usage(trashVolumes(ctx)) }.getOrDefault(0L),
        st.availableBytes, st.totalBytes
    )
}

/** Hapus cache aplikasi kecuali proot-tmp (dipakai sesi Terminal yang sedang berjalan). */
private fun cleanAppCache(ctx: android.content.Context): Long {
    val kids = (ctx.cacheDir.listFiles() ?: emptyArray()).filter { it.name != "proot-tmp" && !it.name.startsWith("debian-rootfs.tar.gz") }
    val before = kids.sumOf { dirSize(it) { false } }
    kids.forEach { wipe(it) }
    return before
}

@Composable
private fun StorageRow(title: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
        Text(
            value, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** Halaman Penyimpanan: ukuran Debian, cache, Recycle Bin, folder kerja, dan pembersihan aman. */
@Composable
internal fun StoragePage(onOpenPackages: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var info by remember { mutableStateOf<StorageInfo?>(null) }
    var msg by remember { mutableStateOf("") }
    var confirmBin by remember { mutableStateOf(false) }
    var showCenter by remember { mutableStateOf(false) }
    LaunchedEffect(tick) {
        info = null
        info = withContext(Dispatchers.IO) { measure(ctx) { !isActive } }
    }
    val i = info
    Category("Ruang penyimpanan")
    if (i == null) {
        StorageRow("Menghitung ukuran...", "Rootfs Debian besar, mohon tunggu beberapa detik.")
    } else {
        StorageRow("Penyimpanan internal tersisa", fmtBytes(i.free) + " dari " + fmtBytes(i.total))
        StorageRow("Debian (rootfs)", fmtBytes(i.rootfs))
        StorageRow("Cache apt (.deb unduhan)", fmtBytes(i.aptCache))
        StorageRow("Cache aplikasi", fmtBytes(i.appCache))
        StorageRow("Folder kerja (" + TerminalSessions.WORKSPACE_DIR + ")", fmtBytes(i.workspace))
        StorageRow("Recycle Bin", fmtBytes(i.trash))
    }
    Category("Bersihkan")
    SettingsItem(
        "Pusat Pembersihan Cerdas",
        "Pilih kategori, lihat estimasi ruang, lalu bersihkan sekaligus: cache, sisa unduhan, laporan lama, file sementara."
    ) { showCenter = true }
    // [CACHE-APT-PAKET] cache apt dibersihkan dari Paket > Debian (apt); di sini hanya tautan
    SettingsItem(
        "Cache apt dan paket yatim",
        "Dibersihkan dari Paket > Debian (apt). Cache apt juga ikut di Pusat Pembersihan Cerdas."
    ) { onOpenPackages() }
    SettingsItem(
        "Bersihkan cache aplikasi",
        "Hapus berkas sementara aplikasi. Sesi Terminal yang berjalan tidak terganggu."
    ) {
        scope.launch {
            val freed = withContext(Dispatchers.IO) { cleanAppCache(ctx) }
            msg = "Cache aplikasi dibersihkan: " + fmtBytes(freed) + " dibebaskan."
            tick++
        }
    }
    SettingsItem(
        "Kosongkan Recycle Bin",
        "Hapus permanen semua item di Recycle Bin (internal, kartu SD, dan Debian). Tidak bisa dibatalkan."
    ) { confirmBin = true }
    SettingsItem("Hitung ulang", "Segarkan ukuran di atas") { tick++ }
    if (confirmBin) {
        AlertDialog(
            onDismissRequest = { confirmBin = false },
            title = { Text("Kosongkan Recycle Bin?") },
            text = { Text("Semua item di Recycle Bin dihapus permanen dan tidak bisa dipulihkan.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmBin = false
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { runCatching { TrashBin.empty(trashVolumes(ctx)) }.getOrDefault(0) }
                        msg = "Recycle Bin dikosongkan: " + n + " entri."
                        tick++
                    }
                }) { Text("Kosongkan") }
            },
            dismissButton = { TextButton(onClick = { confirmBin = false }) { Text("Batal") } }
        )
    }
    if (showCenter) CleanupDialog { showCenter = false; tick++ }
    if (msg.isNotEmpty()) {
        Text(
            msg, modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall
        )
    }
}
