package com.molina.studio.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.log.BugReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [DIAG-HUB] Dialog Settings > Sistem > Info > "Diagnostik dan laporan bug": satu pintu untuk semua bahan laporan
 * masalah. Tiga aksi dari satu sumber data (DiagnosticsManager / BugReport): salin info debug ke clipboard,
 * laporan bug (.txt) ke folder Export, dan ZIP diagnostik sekali klik yang langsung dibagikan.
 */
@Composable
internal fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<String?>(null) }
    var lastHash by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Diagnostik dan laporan bug") },
        text = {
            Column {
                Text(
                    "Semua bahan laporan masalah ada di sini. URL hanya memuat skema dan host, token disamarkan. " +
                        "ZIP dan laporan disimpan di folder Export; ZIP hanya 3 yang terbaru dipertahankan.",
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
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
                Text("Ringkasan versi, perangkat, dan status ke clipboard.", fontSize = 11.sp)
                TextButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { BugReport.export(ctx) }
                ) { Text("Simpan laporan bug (.txt)") }
                Text("Log gabungan dan per kelompok (Cinema, Terminal, Sistem, Logcat, Crash).", fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tombol utama: satu ZIP berisi ringkasan perangkat dan versi (Android, ABI, libmpv, FFmpeg, yt-dlp, level log, " +
                        "PRoot, Debian), laporan bug gabungan, dan laporan crash, lalu langsung dibagikan.",
                    fontSize = 11.sp
                )
                last?.let { Text("Terakhir: $it", fontSize = 12.sp) }
                lastHash?.let { Text("SHA-256: $it", fontSize = 10.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
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
