package com.molina.studio.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Dialog Settings > Sistem > "Diagnostik sekali klik": bangun ZIP lalu langsung buka chooser berbagi. */
@Composable
internal fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<String?>(null) }
    var lastHash by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Diagnostik sekali klik") },
        text = {
            Column {
                Text(
                    "Membuat satu berkas ZIP berisi ringkasan perangkat dan versi (Android, ABI, libmpv, FFmpeg, yt-dlp, level log, " +
                        "status PRoot dan Debian), laporan bug gabungan, dan laporan crash. URL hanya memuat skema dan host, " +
                        "token disamarkan. ZIP disimpan di folder Export; hanya 3 yang terbaru dipertahankan.",
                    fontSize = 13.sp
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
            ) { Text(if (busy) "Membuat..." else "Buat dan bagikan") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Tutup") } }
    )
}
