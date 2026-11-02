package com.molina.studio.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.core.common.AppVisibility
import com.molina.core.common.RefreshReport
import com.molina.core.common.Refreshers
import com.molina.core.common.StreamResolvers
import com.molina.core.common.YtdlpCommand
import com.molina.studio.packages.ToolPurge
import com.molina.studio.cinema.MemoryRefresh
import com.molina.studio.stream.StreamEnginePrefs
import com.molina.studio.stream.YtdlpInstallState
import com.molina.studio.stream.YtdlpInstaller
import com.molina.studio.stream.YtdlpLocator
import com.molina.studio.stream.YtdlpStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DotIdle = Color(0xFF4CAF50)
private val DotBusy = Color(0xFFFFC107)

/** Ikon ↻ di bilah judul Pengaturan; titik hijau = bersih, kuning = ada proses aktif. */
@Composable
internal fun RefreshIconButton(onClick: () -> Unit) {
    // Polling ringan (/proc) hanya saat layar Pengaturan tampil dan aplikasi di depan.
    val busy by produceState(false) {
        while (true) {
            if (AppVisibility.foreground.value) {
                value = withContext(Dispatchers.IO) { Refreshers.all().any { it.activeCount() > 0 } }
            }
            delay(5_000)
        }
    }
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("\u21BB", fontSize = 26.sp, color = MaterialTheme.colorScheme.primary)
        Box(
            Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(9.dp)
                .clip(CircleShape).background(if (busy) DotBusy else DotIdle)
        )
    }
}

private class RowInfo(val id: String, val label: String, val count: Int, val memKb: Long)

private fun summarize(reports: List<RefreshReport>): String {
    val total = reports.sumOf { it.total }
    val left = reports.sumOf { it.remaining }
    val head = if (total == 0 && left == 0) "Sudah bersih, tidak ada proses aktif."
    else "Bersih: $total proses dihentikan."
    val tail = if (left > 0) " $left proses tidak mau mati." else ""
    val notes = reports.mapNotNull { it.note }.filter { it.isNotBlank() }
    return head + tail + if (notes.isEmpty()) "" else "\n" + notes.joinToString("\n")
}

/** Bottom-sheet gaya dialog "Segarkan Sistem": satu baris per modul + "Segarkan Semua". */
@Composable
internal fun SystemRefreshDialog(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var confirmAll by remember { mutableStateOf(false) }

    val rows by produceState(emptyList<RowInfo>(), tick) {
        value = withContext(Dispatchers.IO) {
            Refreshers.all().map { RowInfo(it.id, it.label, it.activeCount(), it.memoryKb()) }
        }
    }
    val appRamMb by produceState(-1L, tick) {
        value = withContext(Dispatchers.IO) { MemoryRefresh.rssKb() / 1024 }
    }

    fun run(id: String?) {
        scope.launch {
            running = true
            message = null
            val reports = withContext(Dispatchers.IO) {
                if (id == null) Refreshers.refreshAll() else listOf(Refreshers.refresh(id))
            }
            message = summarize(reports)
            running = false
            tick++
        }
    }

    AlertDialog(
        onDismissRequest = { if (!running) onClose() },
        confirmButton = { TextButton(onClick = onClose, enabled = !running) { Text("Tutup") } },
        dismissButton = {
            TextButton(onClick = { confirmAll = true }, enabled = !running) { Text("Segarkan semua") }
        },
        title = { Text("Segarkan Sistem") },
        text = {
            Column {
                Text(
                    "Mematikan proses latar sampai akar. Aplikasi tetap terbuka." +
                        if (appRamMb > 0) " RAM aplikasi: $appRamMb MB." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                rows.forEach { r ->
                    val mem = if (r.memKb > 0) " \u00B7 ~${r.memKb / 1024} MB" else ""
                    RefreshRow(
                        r.label, if (r.count == 0) "Bersih" else "${r.count} aktif$mem",
                        enabled = !running
                    ) { run(r.id) }
                }
                if (rows.none { it.id == "code" }) {
                    RefreshRow("Code", "Segera hadir (Fase 6)", enabled = false) {}
                }
                if (running) {
                    Text(
                        "Menyegarkan\u2026", modifier = Modifier.padding(top = 12.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                message?.let {
                    Text(it, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    )

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            confirmButton = {
                TextButton(onClick = { confirmAll = false; run(null) }) { Text("Segarkan") }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Batal") } },
            title = { Text("Segarkan semua?") },
            text = { Text("Pemutaran dihentikan dan semua sesi Terminal ditutup.") }
        )
    }
}

@Composable
private fun RefreshRow(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        TextButton(onClick = onClick, enabled = enabled) { Text("Segarkan") }
    }
}

/** Halaman "Mesin cadangan (yt-dlp)": sakelar, status, pasang/perbarui. */
@Composable
internal fun StreamEnginePage() {
    val ctx = LocalContext.current
    val install by YtdlpInstaller.state.collectAsState()
    val ytdlpOn by StreamEnginePrefs.ytdlpEnabled.collectAsState()
    var status by remember { mutableStateOf<YtdlpStatus?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var ytRun by remember { mutableStateOf<ToolRun?>(null) }
    LaunchedEffect(install, tick) {
        status = withContext(Dispatchers.IO) { YtdlpLocator.status(ctx) }
    }

    SwitchRow(
        "Aktifkan yt-dlp (cadangan)",
        "Untuk TikTok, Facebook, dan situs lain yang tidak dikenali NewPipe. Satu resolusi memulai " +
            "proot + python (RAM bisa naik ratusan MB sesaat), jadi default mati.",
        ytdlpOn
    ) {
        StreamEnginePrefs.enableYtdlp(it)
        StreamResolvers.release()
    }

    Category("Status")
    val st = status
    SettingsItem(
        "Debian + yt-dlp",
        if (st == null) "Memeriksa\u2026"
        else if (st is YtdlpStatus.Ready) "Siap: ${st.guestBinary}"
        else st.hint().orEmpty()
    ) {}

    Category("Pasang")
    val working = install is YtdlpInstallState.Working
    SettingsItem(
        if (st is YtdlpStatus.Ready) "Perbarui yt-dlp" else "Pasang yt-dlp",
        when (val i = install) {
            YtdlpInstallState.Idle -> "Unduh rilis resmi (HTTPS) dan verifikasi SHA-256."
            is YtdlpInstallState.Working -> i.message
            is YtdlpInstallState.Done -> "Berhasil, versi ${i.version ?: "?"}."
            is YtdlpInstallState.Failed -> "Gagal: ${i.message}"
        }
    ) { if (!working) YtdlpInstaller.install(ctx) }

    if (st is YtdlpStatus.Ready) {
        SettingsItem("Versi yt-dlp", "Jalankan yt-dlp --version di Debian (memulai proot + python sesaat).") {
            ytRun = ToolRun("yt-dlp: Versi", "yt-dlp --version", 2 * 60_000L, "Memeriksa versi yt-dlp.", false)
        }
        SettingsItem("Hapus yt-dlp", "Hapus tuntas: biner, cache, dan konfigurasi. Python 3 tidak ikut dihapus.") {
            ytRun = ToolRun(
                "yt-dlp: Hapus", ToolPurge.script(listOf("yt-dlp"), ToolPurge.YTDLP_PATHS, false), 10 * 60_000L,
                "Menghapus yt-dlp sampai tuntas: semua salinan biner, cache, dan konfigurasi, lalu diverifikasi. " +
                    "Python 3 tidak ikut dihapus. Anda bisa memasangnya lagi kapan saja.",
                true
            )
        }
    }
    ytRun?.let { r ->
        ToolRunDialog(r, onClose = { ytRun = null }) { code, _ ->
            if (code != -1 && r.title.endsWith("Hapus")) {
                tick++
                com.molina.studio.automation.AutoPrefs.clearYtdlp(ctx)
            }
        }
    }

    if (st == YtdlpStatus.NoPython) {
        SettingsItem("Salin perintah python3", YtdlpCommand.INSTALL_PYTHON_CMD) {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("molina", YtdlpCommand.INSTALL_PYTHON_CMD))
            Toast.makeText(ctx, "Perintah disalin. Tempel di Terminal.", Toast.LENGTH_SHORT).show()
        }
    }
}
