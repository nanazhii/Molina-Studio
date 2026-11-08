package com.molina.studio.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Dialog Settings > Sistem > "Cadangan dan pemulihan": cadangkan sekarang / pulihkan dari Backup (dengan konfirmasi). */
@Composable
internal fun BackupDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(BackupManager.describe(ctx)) }
    val hasBackup = remember(info) { BackupManager.hasBackup(ctx) }

    fun launchJob(job: () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            val msg = withContext(Dispatchers.IO) { job() }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            info = BackupManager.describe(ctx)
            confirm = false
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cadangan dan pemulihan") },
        text = {
            Column {
                Text(
                    "Penanda video, snippet Terminal, skrip Script Execution, dan setelan Molina disimpan dalam satu berkas di folder Backup aplikasi.",
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(info, fontSize = 12.sp)
                if (confirm) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Penanda, snippet, dan skrip akan digabung, setelan saat ini akan ditimpa. Lanjutkan?",
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Column {
                TextButton(enabled = !busy, onClick = { launchJob { BackupManager.backupAll(ctx) } }) {
                    Text("Cadangkan sekarang")
                }
                TextButton(
                    enabled = !busy && hasBackup,
                    onClick = { if (confirm) launchJob { BackupManager.restoreAll(ctx) } else confirm = true }
                ) {
                    Text(if (confirm) "Ya, pulihkan sekarang" else "Pulihkan dari Backup")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup") } }
    )
}
