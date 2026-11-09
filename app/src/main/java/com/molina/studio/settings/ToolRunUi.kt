package com.molina.studio.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.molina.studio.ui.components.MolinaAlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.exec.plan.RunPlan
import com.molina.exec.run.RunHandle
import com.molina.exec.run.RunListener
import com.molina.exec.run.RunRequest
import com.molina.exec.run.RunResult
import com.molina.studio.cinema.Win7
import com.molina.studio.script.ProotScriptExecutor
import com.molina.studio.ui.MolinaTheme
import kotlinx.coroutines.delay

/** Satu skrip yang dijalankan di Debian PRoot lewat dialog keluaran. [confirm] = minta persetujuan dulu. */
internal class ToolRun(
    val title: String, val script: String, val timeoutMs: Long, val note: String, val confirm: Boolean,
    /** Opsional: baris keluaran yang cocok disimpan (maks 50) lalu diringkas [summarize] saat sukses. */
    val keyPattern: Regex? = null,
    val summarize: ((List<String>) -> String?)? = null
)

internal const val MAX_UI_CHARS = 60_000

/**
 * Dialog jalankan: (opsional) konfirmasi dengan pratinjau perintah, lalu proses diserahkan ke [ToolRunHub]
 * (tampilannya di [ToolRunHost], bisa diminimalkan). Dipakai Paket (pasang/versi/hapus), pintasan Debian
 * apt, dan yt-dlp. [onResult] menerima kode keluar (-1 bila tidak ada hasil: gagal mulai, dihentikan, atau
 * lewat waktu) dan keluaran. [MINIMIZE-RUN]
 */
@Composable
internal fun ToolRunDialog(run: ToolRun, onClose: () -> Unit, onResult: (Int, String) -> Unit = { _, _ -> }) {
    val ctx = LocalContext.current
    var handedOver by remember { mutableStateOf(false) }
    fun launch() {
        if (handedOver) return
        handedOver = true
        ToolRunHub.start(ctx, run, onResult)
        onClose()
    }
    if (!run.confirm) {
        LaunchedEffect(Unit) { launch() }
    } else if (!handedOver) {
        MolinaAlertDialog(
            onDismissRequest = onClose,
            title = { Text(run.title) },
            text = {
                Column {
                    Text(run.note)
                    Spacer(Modifier.height(10.dp))
                    Text("Perintah yang dijalankan:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                        CommandBlock(run.script) // [CODE-CMDBLOCK]
                    }
                }
            },
            confirmButton = { TextButton(onClick = { launch() }) { Text("Jalankan") } },
            dismissButton = { TextButton(onClick = onClose) { Text("Batal") } }
        )
    }
}

/** [MINIMIZE-RUN] Dialog progres proses Paket. Dipasang sekali di root (MainActivity) agar tampil di tab mana pun. */
@Composable
internal fun ToolRunHost() {
    val snap by ToolRunHub.session.collectAsState()
    val s = snap
    if (s != null && !s.minimized) ToolRunPanel(s)
}

@Composable
private fun ToolRunPanel(s: ToolRunSnapshot) {
    val ctx = LocalContext.current
    var showDetail by remember(s.startedAtMs) { mutableStateOf(false) }
    var now by remember(s.startedAtMs) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.startedAtMs, s.running) { while (s.running) { now = System.currentTimeMillis(); delay(1000) } }
    val elapsed = ((now - s.startedAtMs) / 1000).toInt().coerceAtLeast(0)
    val scroll = rememberScrollState()
    LaunchedEffect(s.output) { if (showDetail) scroll.scrollTo(scroll.maxValue) }
    MolinaAlertDialog(
        onDismissRequest = { if (s.running) ToolRunHub.minimize() else ToolRunHub.close() },
        title = { Text(s.title) },
        text = {
            Column {
                RunProgressPanel(s.phase, s.progress, s.state, s.status, elapsed)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (showDetail) "Sembunyikan rincian \u25B4" else "Tampilkan rincian \u25BE",
                    color = MaterialTheme.colorScheme.primary, fontSize = 12.sp,
                    modifier = Modifier.clickable { showDetail = !showDetail }.padding(vertical = 4.dp)
                )
                if (showDetail) {
                    Box(Modifier.heightIn(max = 280.dp).verticalScroll(scroll)) {
                        Text(s.output, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                    Text(
                        "Salin keluaran", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            copyOutput(ctx, listOf(s.status, s.output).filter { it.isNotBlank() }.joinToString("\n\n"))
                        }.padding(vertical = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            if (s.running) TextButton(onClick = { ToolRunHub.cancel() }) { Text("Berhenti") }
            else TextButton(onClick = { ToolRunHub.close() }) { Text("Tutup") }
        },
        dismissButton = {
            if (s.running) TextButton(onClick = { ToolRunHub.minimize() }) { Text("Minimalkan") }
            else TextButton(onClick = {
                copyOutput(ctx, listOf(s.status, s.output).filter { it.isNotBlank() }.joinToString("\n\n"))
            }) { Text("Salin") }
        }
    )
}

/** [MINIMIZE-RUN] Pil kecil di atas bar bawah saat proses diminimalkan; ketuk untuk membuka lagi. */
@Composable
internal fun ToolRunPill() {
    val snap by ToolRunHub.session.collectAsState()
    val s = snap
    if (s == null || !s.minimized) return
    var now by remember(s.startedAtMs) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.startedAtMs, s.running) { while (s.running) { now = System.currentTimeMillis(); delay(1000) } }
    val (top, bottom) = when (s.state) {
        RunState.Running -> Color(0xFF8FC4EE) to Color(0xFF2D6DB5)
        RunState.Ok -> Color(0xFF7BE87A) to Color(0xFF1E9E1A)
        RunState.Failed -> MolinaTheme.Bad to Color(0xFFC4392B)
        RunState.Stopped -> Color(0xFFFFE680) to Color(0xFFE0A800)
    }
    val line = if (s.running) {
        val pct = s.progress?.let { "${(it * 100).toInt()}% \u00B7 " }.orEmpty()
        pct + s.phase
    } else s.status.lineSequence().firstOrNull().orEmpty()
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF101010))
            .clickable { ToolRunHub.restore() }.padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.title, color = Win7.Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(line, color = Win7.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("Buka", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(4.dp))
        GlossBar(if (s.running) s.progress else 1f, top, bottom, height = 6.dp)
    }
}

internal fun startShell(
    ctx: Context, run: ToolRun, onChunk: (String) -> Unit, onDone: (RunResult) -> Unit,
    lane: ProotScriptExecutor.Lane = ProotScriptExecutor.Lane.CODE // [PAKET-LANE]
): RunHandle {
    val main = Handler(Looper.getMainLooper())
    val req = RunRequest(
        name = "tool",
        scriptText = "set -e\n" + run.script.trim() + "\n",
        plan = RunPlan("sh", "/bin/sh", "sh", false),
        hostDir = null,
        timeoutMs = run.timeoutMs
    )
    return ProotScriptExecutor(ctx.applicationContext, lane).start(req, object : RunListener {
        override fun onOutput(text: String, isError: Boolean) { main.post { onChunk(text) } }
        override fun onFinished(result: RunResult) { main.post { onDone(result) } }
    })
}

internal fun describe(r: RunResult): String {
    val err = r.startError
    return when {
        err != null -> err
        r.cancelled -> "Dihentikan. Bila apt terputus di tengah jalan, jalankan dpkg --configure -a di Terminal."
        r.timedOut -> "Lewat batas waktu."
        r.exitCode == 0 -> "Selesai dalam ${r.millis / 1000} dtk."
        else -> "Gagal (kode ${r.exitCode})."
    }
}

internal fun copyOutput(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("molina-run", text))
    Toast.makeText(ctx, "Keluaran disalin", Toast.LENGTH_SHORT).show()
}
