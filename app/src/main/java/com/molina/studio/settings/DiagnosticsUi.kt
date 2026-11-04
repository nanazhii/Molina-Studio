package com.molina.studio.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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

private fun levelColor(l: CheckLevel): Color = when (l) {
    CheckLevel.Ok -> MolinaTheme.Good // mint #69F0AE
    CheckLevel.Warn -> MolinaTheme.Warn
    CheckLevel.Bad -> MolinaTheme.Bad
}

private suspend fun runChecks(ctx: Context): List<CheckResult> =
    withContext(Dispatchers.IO) { runCatching { HealthCheck.run(ctx) }.getOrDefault(emptyList()) }

/** [DIAG-CARD] Kartu hasil pemeriksaan: header berwarna sesuai kondisi terburuk, baris dengan penanda warna di kiri. */
@Composable
private fun HealthCard(checks: List<CheckResult>?) {
    val shape = RoundedCornerShape(10.dp)
    val overall = checks?.takeIf { it.isNotEmpty() }?.let { HealthCheck.worst(it) }
    val head = overall?.let { levelColor(it) } ?: MolinaTheme.Muted
    Column(Modifier.fillMaxWidth().clip(shape).background(MolinaTheme.CardBg).border(1.dp, MolinaTheme.CardLine, shape)) {
        Row(
            Modifier.fillMaxWidth().background(head.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Pemeriksaan sistem", color = head, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            val sum = checks?.takeIf { it.isNotEmpty() }?.let { c -> "${c.count { it.level == CheckLevel.Ok }}/${c.size} sehat" } ?: ""
            Text(sum, color = MolinaTheme.Muted, fontSize = 11.sp)
        }
        if (checks == null) {
            Text("Memeriksa...", color = MolinaTheme.Muted, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
        } else {
            checks.forEach { r ->
                val c = levelColor(r.level)
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(c))
                    Text(r.level.glyph, color = c, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp, top = 8.dp))
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(r.name, color = MolinaTheme.OnBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(r.detail, color = MolinaTheme.Muted, fontSize = 11.sp)
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
internal fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<String?>(null) }
    var lastHash by remember { mutableStateOf<String?>(null) }
    var checks by remember { mutableStateOf<List<CheckResult>?>(null) }

    LaunchedEffect(Unit) { checks = runChecks(ctx) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Diagnostik dan laporan bug") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                HealthCard(checks)
                Spacer(Modifier.height(8.dp))
                Text(
                    "URL hanya memuat skema dan host, token disamarkan. Setiap laporan punya ID (MOL-tanggal-jam-kode) " +
                        "yang sama di nama berkas dan isinya. ZIP hanya 3 yang terbaru dipertahankan di folder Export.",
                    fontSize = 12.sp
                )
                TextButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { scope.launch { checks = null; checks = runChecks(ctx) } }
                ) { Text("Periksa ulang") }
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
