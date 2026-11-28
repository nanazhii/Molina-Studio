package com.molina.studio.terminal

import com.molina.studio.ui.MolinaTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

private val CloseRed = MolinaTheme.Bad

/** Dialog konfirmasi tutup sesi; dipakai bilah pil. Sesi terakhir = Molina keluar. */
@Composable
internal fun CloseSessionDialog(index: Int, onDismiss: () -> Unit) {
    val last = TerminalSessions.sessions.size <= 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tutup sesi [${index + 1}]?") },
        text = {
            Text(
                if (last) "Ini sesi terakhir: proses di dalamnya dihentikan dan Molina keluar."
                else "Proses yang berjalan di sesi ini akan dihentikan."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                TerminalSessions.closeSession(index)
            }) { Text("Tutup", color = CloseRed) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}
