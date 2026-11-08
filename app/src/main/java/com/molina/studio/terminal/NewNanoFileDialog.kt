package com.molina.studio.terminal

import android.os.Environment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.cinema.ExplorerRecent
import com.molina.studio.settings.MolinaExplorer

/**
 * [NANO-NEW] Dialog "Berkas baru (nano)" dari drawer Terminal. Folder bawaan = folder terakhir yang dibuka di Files
 * (bila tak ada: akar penyimpanan internal). Nama ditanyakan di awal supaya nano tidak pernah membuka buffer tanpa nama.
 */
@Composable
internal fun NewNanoFileDialog(onDismiss: () -> Unit, onOpened: () -> Unit) {
    val ctx = LocalContext.current
    val dir = remember { ExplorerRecent.last(ctx) ?: Environment.getExternalStorageDirectory() }
    var name by remember { mutableStateOf("catatan.txt") }
    fun go() {
        if (name.isNotBlank() && MolinaExplorer.newInTerminal(ctx, dir, name)) onOpened()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Berkas baru (nano)") },
        text = {
            Column {
                Text("Folder: " + MolinaExplorer.debianPath(dir), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Nama berkas") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { go() })
                )
            }
        },
        confirmButton = { TextButton(onClick = { go() }, enabled = name.isNotBlank()) { Text("Buka") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}
