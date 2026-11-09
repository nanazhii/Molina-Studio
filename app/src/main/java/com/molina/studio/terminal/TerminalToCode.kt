package com.molina.studio.terminal

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.molina.studio.cinema.Win7
import com.molina.studio.cinema.Win7Button
import com.molina.studio.code.CodeInbox
import com.molina.studio.settings.Win7GlassWindow

/** Dialog "Sisip ke Code": pilih jumlah keluaran Terminal yang disisipkan di posisi kursor tab Code aktif. */
@Composable
internal fun SendToCodeDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    fun send(lines: Int?) {
        val msg = CodeInbox.deliver(TerminalActions.outputText(lines))
        if (msg != null) Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        onDismiss()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Win7GlassWindow(
                title = "Sisip ke Code",
                icon = { Text("< >", color = Color.White, fontSize = 11.sp) }
            ) {
                Text(
                    "Kirim keluaran Terminal ke posisi kursor di tab Code aktif.",
                    color = Win7.TextDim, fontSize = 12.sp
                )
                Win7Button("50 baris terakhir", Modifier.fillMaxWidth()) { send(50) }
                Win7Button("200 baris terakhir", Modifier.fillMaxWidth()) { send(200) }
                Win7Button("Seluruh transkrip", Modifier.fillMaxWidth()) { send(null) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Win7Button("Batal", onClick = onDismiss)
                }
            }
        }
    }
}
