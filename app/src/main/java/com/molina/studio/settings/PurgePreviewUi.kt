package com.molina.studio.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.molina.studio.ui.components.MolinaAlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.code.run.RunHandle
import com.molina.studio.packages.ToolItem
import com.molina.studio.packages.ToolPurge

private val Amber = Color(0xFFE8A35F)
private val Danger = Color(0xFFE8776B)

/**
 * Langkah sebelum HAPUS alat bawaan: simulasi `apt purge --auto-remove -s` menampilkan paket yang akan
 * dihapus (termasuk yang ikut terhapus), plus opsi mencadangkan konfigurasi. [onProceed] menerima pilihan cadangan.
 */
@Composable
internal fun PurgePreviewDialog(tool: ToolItem, onProceed: (Boolean) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var running by remember { mutableStateOf(true) }
    var output by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    var backup by remember { mutableStateOf(tool.backupPaths.isNotEmpty()) }
    var handle by remember { mutableStateOf<RunHandle?>(null) }
    val run = remember { ToolRun("Pratinjau hapus: ${tool.name}", ToolPurge.preview(tool.purgePkgs), 2 * 60_000L, "", false) }

    LaunchedEffect(Unit) {
        handle = startShell(
            ctx, run,
            onChunk = { output = (output + it.replace("\r", "")).takeLast(20_000) },
            onDone = { r ->
                running = false
                if (r.startError != null || r.cancelled || r.timedOut || r.exitCode != 0) failure = describe(r)
            }
        )
    }

    val lines = output.lineSequence().map { it.trim() }.toList()
    val purged = lines.filter { it.startsWith("Purg ") }.map { it.split(" ")[1] }.distinct()
    val others = purged.filter { it !in tool.purgePkgs }
    val errors = lines.filter { it.startsWith("E:") }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

    MolinaAlertDialog(
        onDismissRequest = { if (!running) onClose() },
        title = { Text("Pratinjau hapus: ${tool.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    running -> Text("Menghitung dampak (simulasi, tidak ada yang diubah)\u2026", color = dim, fontSize = 13.sp)
                    failure != null -> Text(
                        "Pratinjau gagal: $failure" + (errors.firstOrNull()?.let { "\n$it" } ?: ""),
                        color = Amber, fontSize = 13.sp
                    )
                    purged.isEmpty() -> Text(
                        "Tidak ada paket apt yang akan dihapus (belum terpasang). Sisa konfigurasi dan cache tetap dibersihkan.",
                        fontSize = 13.sp
                    )
                    else -> {
                        Text("Paket yang akan dihapus (${purged.size}):", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                            Column {
                                purged.forEach { p ->
                                    val extra = p !in tool.purgePkgs
                                    Text(
                                        "\u2022 $p" + if (extra) " (ikut terhapus)" else "",
                                        color = if (extra) Amber else MaterialTheme.colorScheme.onSurface,
                                        fontFamily = FontFamily.Monospace, fontSize = 11.sp
                                    )
                                }
                            }
                        }
                        if (others.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${others.size} paket lain ikut terhapus karena bergantung pada ${tool.name} atau menjadi yatim.",
                                color = Amber, fontSize = 12.sp
                            )
                        }
                    }
                }
                if (tool.backupPaths.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth().clickable { backup = !backup }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = backup, onCheckedChange = { backup = it })
                        Column {
                            Text("Cadangkan konfigurasi dulu", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(
                                tool.backupPaths.joinToString(", ") { it.substringAfterLast('/') } +
                                    " ke /sdcard/${com.molina.studio.terminal.TerminalSessions.WORKSPACE_DIR}/.molina-backup. Kredensial/token tidak disertakan.",
                                color = dim, fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !running && failure == null,
                onClick = { onProceed(backup && tool.backupPaths.isNotEmpty()) }
            ) { Text("Lanjut hapus", color = Danger) }
        },
        dismissButton = { TextButton(onClick = { handle?.cancel(); onClose() }) { Text("Batal") } }
    )
}
