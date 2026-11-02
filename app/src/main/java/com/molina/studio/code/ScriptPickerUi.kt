package com.molina.studio.code

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.code.preview.CommandBlockSpec
import com.molina.code.preview.ScriptLaunch
import com.molina.studio.settings.CommandBlock
import com.molina.studio.terminal.TerminalSessions
import com.molina.studio.ui.MolinaTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dialog konfirmasi jalankan skrip. Dipakai bersama oleh Settings, pemilih skrip di Terminal, dan pintasan
 * layar utama, jadi tidak ada jalur yang menjalankan skrip tanpa konfirmasi.
 */
@Composable
internal fun ScriptRunDialog(item: ScriptItem, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val canNew = TerminalSessions.sessions.isNotEmpty() && TerminalSessions.canAdd()
    fun go(newSession: Boolean) {
        onDismiss()
        val err = ScriptRunner.run(ctx, item, newSession)
        if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_SHORT).show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Jalankan di Molina Terminal?") },
        text = {
            Column {
                Text(
                    "\"${item.title}\" (${ScriptLaunch.countLines(item.body)} baris) ditulis ke Debian, lalu diketik " +
                        "ke terminal dengan chmod +x dan Enter, persis seperti Anda mengetiknya sendiri.\n\n" +
                        "Periksa isinya bila bukan skrip buatan Anda. Pilih SESI BARU bila sesi aktif sedang " +
                        "menjalankan sesuatu."
                )
                if (item.args.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Argumen: ${item.args}", fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text("Awal skrip (tidak menjamin sisanya aman):", fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                CommandBlock(CommandBlockSpec.clip(item.body, withCount = true))
            }
        },
        confirmButton = {
            Row {
                if (canNew) TextButton(onClick = { go(true) }) { Text("SESI BARU") }
                TextButton(onClick = { go(false) }) { Text("SESI AKTIF") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("BATAL") } }
    )
}

/**
 * Ketukan DALAM aplikasi (Settings, Pemilih): skrip bertanda "percaya" langsung jalan di sesi aktif, selain itu
 * dialog konfirmasi. Jalur luar (pintasan layar utama) TIDAK memakai ini dan selalu meminta konfirmasi.
 * Mengembalikan true bila sudah dijalankan langsung.
 */
internal fun runIfTrusted(ctx: android.content.Context, item: ScriptItem): Boolean {
    if (!item.trusted) return false
    val err = ScriptRunner.run(ctx, item, false)
    if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_SHORT).show()
    return true
}

/** Menampilkan konfirmasi untuk skrip yang diminta dari luar (pintasan layar utama). Pasang sekali di MainActivity. */
@Composable
internal fun ScriptRunHost() {
    val ctx = LocalContext.current
    val id = ScriptNav.confirmRunId ?: return
    val all by ScriptStore.items.collectAsState()
    val item = all.firstOrNull { it.id == id }
    if (item == null) {
        LaunchedEffect(id) {
            Toast.makeText(ctx, "Skrip tidak ditemukan (mungkin sudah dihapus)", Toast.LENGTH_SHORT).show()
            ScriptNav.confirmRunId = null
        }
        return
    }
    ScriptRunDialog(item) { ScriptNav.confirmRunId = null }
}

/** Bottom-sheet gaya daftar: skrip terakhir dijalankan di atas, ketuk baris untuk konfirmasi lalu jalankan. */
@Composable
internal fun ScriptPickerDialog(onDismiss: () -> Unit) {
    val all by ScriptStore.items.collectAsState()
    var query by remember { mutableStateOf("") }
    var toRun by remember { mutableStateOf<ScriptItem?>(null) }
    val sorted = remember(all, query) {
        val q = query.trim()
        all.filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) || it.note.contains(q, ignoreCase = true) }
            .sortedWith(compareByDescending<ScriptItem> { it.favorite }.thenByDescending { it.lastRunAt }.thenByDescending { it.updatedAt })
    }
    val ctx = LocalContext.current
    toRun?.let { item ->
        ScriptRunDialog(item) { toRun = null; onDismiss() }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pilih skrip") },
        text = {
            Column {
                if (all.size > 6) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it.take(60) },
                        singleLine = true, placeholder = { Text("Cari skrip") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (sorted.isEmpty()) {
                    Text(
                        if (all.isEmpty()) "Belum ada skrip. Tambahkan di Settings > Script execution." else "Tidak ada yang cocok.",
                        fontSize = 13.sp
                    )
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(sorted, key = { it.id }) { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { if (runIfTrusted(ctx, s)) { onDismiss() } else { toRun = s } }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text((if (s.favorite) "\u2605 " else "") + s.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(lastRunText(s), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("\u25B6", color = MolinaTheme.Gold, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp).size(24.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("TUTUP") } }
    )
}

private fun lastRunText(s: ScriptItem): String {
    if (s.lastRunAt <= 0L) return "Belum pernah dijalankan"
    return "Dijalankan " + SimpleDateFormat("d MMM HH:mm", Locale("id", "ID")).format(Date(s.lastRunAt))
}
