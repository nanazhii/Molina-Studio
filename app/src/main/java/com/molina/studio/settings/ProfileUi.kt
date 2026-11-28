package com.molina.studio.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Dialog Settings > Sistem > "Profil setelan": simpan setelan saat ini sebagai profil bernama, terapkan sekali tap, hapus. */
@Composable
internal fun ProfileDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var profiles by remember { mutableStateOf<List<SettingsProfile>>(emptyList()) }

    LaunchedEffect(Unit) { profiles = withContext(Dispatchers.IO) { ProfileManager.list(ctx) } }

    fun launchJob(job: () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            val (msg, list) = withContext(Dispatchers.IO) { job() to ProfileManager.list(ctx) }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            profiles = list
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Profil setelan") },
        text = {
            Column {
                Text(
                    "Simpan setelan saat ini (termasuk Shield, privasi, Unduhan, Pemutar, dan Terminal) sebagai profil, lalu ganti kapan saja dengan satu ketukan. " +
                        "Penanda, snippet, dan skrip tidak ikut. Nama yang sama menimpa profil lama.",
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    singleLine = true,
                    label = { Text("Nama profil, mis. Nonton") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (profiles.isEmpty()) {
                    Text("Belum ada profil.", fontSize = 12.sp)
                } else {
                    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        profiles.forEach { p ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(p.name, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                TextButton(enabled = !busy, onClick = { launchJob { ProfileManager.apply(ctx, p.id) } }) { Text("Terapkan") }
                                TextButton(enabled = !busy, onClick = { launchJob { ProfileManager.delete(ctx, p.id) } }) { Text("Hapus") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank(),
                onClick = {
                    val n = name
                    name = ""
                    launchJob { ProfileManager.save(ctx, n) }
                }
            ) { Text("Simpan setelan saat ini") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup") } }
    )
}
