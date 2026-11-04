package com.molina.studio.settings

import android.Manifest
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.molina.core.common.LogRedact
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.log.BugReport
import com.molina.studio.ui.MolinaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun levelColor(l: CheckLevel): Color = when (l) {
    CheckLevel.Ok -> MolinaTheme.Good // hijau status
    CheckLevel.Warn -> MolinaTheme.Warn
    CheckLevel.Bad -> MolinaTheme.Bad
}

private suspend fun runChecks(ctx: Context): List<CheckResult> =
    withContext(Dispatchers.IO) {
        runCatching { HealthCheck.run(ctx) }.getOrDefault(emptyList())
            .also { if (it.isNotEmpty()) runCatching { HealthHistory.add(ctx, it) } }
    }

/** [DIAG-ACTION] Buka layar izin aplikasi di Setelan Android (satu-satunya tempat izin storage bisa diberikan ulang). */
private fun openAppSettings(ctx: Context) {
    try {
        ctx.startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", ctx.packageName, null)
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        Toast.makeText(ctx, "Buka Setelan Android > Aplikasi > Molina > Izin", Toast.LENGTH_LONG).show()
    }
}

/** [DIAG-PERM] Activity pemilik dialog (Compose memberi ContextWrapper), untuk shouldShowRequestPermissionRationale. */
private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** [DIAG-ICON] Lencana kecil per baris pemeriksaan (ala ikon panel Windows 7), berwarna sesuai kondisi. */
@Composable
private fun RowBadge(name: String, color: Color) {
    val label = when (name) {
        "PRoot" -> "PR"
        "Debian" -> "DEB"
        "Debian jalan" -> "RUN"
        "Terminal jalan" -> ">_"
        "Cinema siap" -> "CIN"
        "Log Cinema" -> "LOG"
        "Dekoder" -> "DEC"
        "Jaringan" -> "NET"
        "Penyimpanan" -> "SD"
        "Ruang bebas" -> "GB"
        "libmpv" -> "MPV"
        else -> name.take(2).uppercase()
    }
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier.size(width = 28.dp, height = 18.dp).clip(shape)
            .background(color.copy(alpha = 0.18f), shape).border(1.dp, color.copy(alpha = 0.5f), shape),
        Alignment.Center
    ) { Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
}

private val HISTORY_TIME = SimpleDateFormat("dd MMM HH:mm", Locale("id", "ID"))

/** [DIAG-HISTORY] Lima pemeriksaan terakhir: titik berwarna, waktu, dan ringkasan. */
@Composable
private fun HistoryCard(history: List<HistoryEntry>) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MolinaTheme.CardBg).border(1.dp, MolinaTheme.CardLine, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        if (history.isEmpty()) Text("Belum ada riwayat.", color = MolinaTheme.Muted, fontSize = 11.sp)
        history.forEach { h ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(levelColor(h.level)))
                Spacer(Modifier.width(8.dp))
                Text(HISTORY_TIME.format(Date(h.time)), color = MolinaTheme.Muted, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                Text(h.summary, color = MolinaTheme.OnBackground, fontSize = 11.sp)
            }
        }
    }
}

/** [DIAG-BADGE] Lencana kecil di tautan Diagnostik (halaman Info): mint = sehat, kuning = perlu dicek, merah = bermasalah. */
@Composable
internal fun DiagBadge(level: CheckLevel?, modifier: Modifier = Modifier) {
    if (level == null) return
    val c = levelColor(level)
    val label = when (level) {
        CheckLevel.Ok -> "Sehat"
        CheckLevel.Warn -> "Perlu dicek"
        CheckLevel.Bad -> "Bermasalah"
    }
    Row(
        modifier.padding(top = 10.dp, end = 12.dp).clip(RoundedCornerShape(10.dp))
            .background(c.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(5.dp))
        Text(label, color = c, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** [DIAG-CARD] Kartu hasil pemeriksaan: header berwarna sesuai kondisi terburuk, baris dengan penanda warna di kiri. */
@Composable
private fun HealthCard(checks: List<CheckResult>?, onAction: (CheckAction) -> Unit = {}) {
    val ctx = LocalContext.current // [DIAG-COPY]
    val shape = RoundedCornerShape(10.dp)
    val overall = checks?.takeIf { it.isNotEmpty() }?.let { HealthCheck.worst(it) }
    val head = overall?.let { levelColor(it) } ?: MolinaTheme.Muted
    Column(Modifier.fillMaxWidth().clip(shape).background(MolinaTheme.CardBg).border(1.dp, MolinaTheme.CardLine, shape)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(head)) // [DIAG-BRAND] garis aksen sesuai kondisi
        Row(
            Modifier.fillMaxWidth().background(head.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Molina Control Panel", color = MolinaTheme.Brand, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            val sum = checks?.takeIf { it.isNotEmpty() }?.let { c -> "${c.count { it.level == CheckLevel.Ok }}/${c.size} sehat" } ?: ""
            Text(sum, color = head, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        if (checks == null) {
            Text("Memeriksa...", color = MolinaTheme.Muted, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
        } else {
            checks.forEach { r ->
                val c = levelColor(r.level)
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min).pointerInput(r) {
                        detectTapGestures(onLongPress = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val text = LogRedact.text("${r.level.glyph} ${r.name}: ${r.detail}")
                            cm.setPrimaryClip(ClipData.newPlainText("Molina pemeriksaan", text))
                            Toast.makeText(ctx, "Detail ${r.name} disalin", Toast.LENGTH_SHORT).show()
                        })
                    }
                ) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(c))
                    Text(r.level.glyph, color = c, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp, top = 8.dp))
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RowBadge(r.name, c)
                            Spacer(Modifier.width(8.dp))
                            Text(r.name, color = MolinaTheme.OnBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(r.detail, color = MolinaTheme.Muted, fontSize = 11.sp)
                        val act = r.action
                        if (act != null && r.level != CheckLevel.Ok) {
                            Text(
                                when (act) {
                                    CheckAction.ManageDebian -> "Kelola Debian"
                                    CheckAction.GrantPermission -> "Beri izin"
                                    CheckAction.ViewMpvLog -> "Lihat log mpv"
                                },
                                color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { onAction(act) }.padding(top = 6.dp, bottom = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * [DIAG-HUB] Dialog Settings > Sistem > Info > "Diagnostik dan laporan bug": satu pintu untuk semua bahan laporan
 * masalah. Pemeriksaan kesehatan otomatis saat dibuka, lalu tiga aksi dari satu sumber data (DiagnosticsManager /
 * BugReport): salin info debug, laporan bug (.txt), dan ZIP diagnostik sekali klik. Semuanya memuat ID laporan.
 */
@Composable
internal fun DiagnosticsDialog(onManageDebian: () -> Unit = {}, onViewLog: () -> Unit = {}, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<String?>(null) }
    var lastHash by remember { mutableStateOf<String?>(null) }
    var checks by remember { mutableStateOf<List<CheckResult>?>(null) }
    var history by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var showHistory by remember { mutableStateOf(false) }

    val refresh: suspend () -> Unit = {
        checks = null
        checks = runChecks(ctx)
        history = withContext(Dispatchers.IO) { runCatching { HealthHistory.load(ctx) }.getOrDefault(emptyList()) }
    }
    // [DIAG-PERM] Minta izin storage langsung. Bila Android menolak tanpa dialog (ditolak permanen), baru buka Setelan.
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val canAsk = ctx.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE) == true
        if (!granted && !canAsk) openAppSettings(ctx)
        scope.launch { refresh() }
    }
    val onAction: (CheckAction) -> Unit = { a ->
        when (a) {
            CheckAction.ManageDebian -> onManageDebian()
            CheckAction.GrantPermission -> permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            CheckAction.ViewMpvLog -> onViewLog()
        }
    }
    LaunchedEffect(Unit) { refresh() }

    AlertDialog(
        containerColor = MolinaTheme.Background, // [DIAG-THEME] Home Theater: latar gelap, judul emas merek
        titleContentColor = MolinaTheme.Brand,
        textContentColor = MolinaTheme.OnBackground,
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Diagnostik dan laporan bug") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                HealthCard(checks, onAction)
                Text("Tekan lama satu baris untuk menyalin detailnya.", color = MolinaTheme.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                Text(
                    "URL hanya memuat skema dan host, token disamarkan. Setiap laporan punya ID (MOL-tanggal-jam-kode) " +
                        "yang sama di nama berkas dan isinya. ZIP hanya 3 yang terbaru dipertahankan di folder Export.",
                    fontSize = 12.sp
                )
                TextButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { scope.launch { refresh() } }
                ) { Text("Periksa ulang") }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showHistory = !showHistory }
                ) { Text(if (showHistory) "Sembunyikan riwayat" else "Riwayat pemeriksaan (${history.size})") }
                if (showHistory) HistoryCard(history)
                TextButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        busy = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { DiagnosticsManager.debugInfo(ctx) } }
                            busy = false
                            val text = r.getOrNull()
                            if (text != null) {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Molina info debug", text))
                                Toast.makeText(ctx, "Info debug disalin", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(ctx, "Gagal menyalin info debug: ${r.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                ) { Text("Salin info debug") }
                Text("Ringkasan versi, perangkat, status, dan hasil pemeriksaan ke clipboard.", fontSize = 11.sp)
                TextButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { BugReport.export(ctx) }
                ) { Text("Simpan laporan bug (.txt)") }
                Text("Log gabungan dan per kelompok (Cinema, Terminal, Sistem, Logcat, Crash).", fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tombol utama: satu ZIP berisi ringkasan perangkat dan versi, hasil pemeriksaan, laporan bug gabungan, " +
                        "dan laporan crash, lalu langsung dibagikan.",
                    fontSize = 11.sp
                )
                last?.let { Text("Terakhir: $it", fontSize = 12.sp) }
                lastHash?.let { Text("SHA-256: $it", fontSize = 10.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                colors = ButtonDefaults.textButtonColors(contentColor = MolinaTheme.Good),
                onClick = {
                    busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { DiagnosticsManager.build(ctx) } }
                        busy = false
                        val f = r.getOrNull()
                        if (f != null) {
                            last = f.name
                            lastHash = DiagnosticsManager.zipHash(f)
                            DiagnosticsManager.share(ctx, f)
                        } else {
                            Toast.makeText(ctx, "Gagal membuat diagnostik: ${r.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            ) { Text(if (busy) "Memproses..." else "Buat ZIP dan bagikan") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Tutup") } }
    )
}
