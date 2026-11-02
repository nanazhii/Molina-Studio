package com.molina.studio.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.code.preview.RunPlan
import com.molina.code.run.RunHandle
import com.molina.code.run.RunListener
import com.molina.code.run.RunRequest
import com.molina.code.run.RunResult
import com.molina.core.common.AppLog
import com.molina.core.common.LogLevel
import com.molina.studio.code.ProotScriptExecutor
import kotlinx.coroutines.delay

/** Satu skrip yang dijalankan di Debian PRoot lewat dialog keluaran. [confirm] = minta persetujuan dulu. */
internal class ToolRun(
    val title: String, val script: String, val timeoutMs: Long, val note: String, val confirm: Boolean,
    /** Opsional: baris keluaran yang cocok disimpan (maks 50) lalu diringkas [summarize] saat sukses. */
    val keyPattern: Regex? = null,
    val summarize: ((List<String>) -> String?)? = null
)

private const val MAX_UI_CHARS = 60_000

/**
 * Dialog jalankan: (opsional) konfirmasi dengan pratinjau perintah, lalu keluaran langsung. Dipakai Paket
 * (pasang/versi/hapus), pintasan Debian apt, dan yt-dlp. [onResult] menerima kode keluar (-1 bila tidak ada
 * hasil: gagal mulai, dihentikan, atau lewat waktu) dan keluaran.
 */
@Composable
internal fun ToolRunDialog(run: ToolRun, onClose: () -> Unit, onResult: (Int, String) -> Unit = { _, _ -> }) {
    val ctx = LocalContext.current
    var started by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf<RunHandle?>(null) }
    val tracker = remember { RunProgressTracker() }
    var phase by remember { mutableStateOf("Menyiapkan\u2026") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var runState by remember { mutableStateOf(RunState.Running) }
    var showDetail by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0) }
    LaunchedEffect(running) { while (running) { delay(1000); elapsed++ } }
    val scroll = rememberScrollState()
    LaunchedEffect(output) { scroll.scrollTo(scroll.maxValue) }
    val keyLines = remember { mutableListOf<String>() }
    val pending = remember { StringBuilder() }

    fun collect(text: String) {
        val kp = run.keyPattern ?: return
        pending.append(text.replace("\r", "\n"))
        var i = pending.indexOf("\n")
        while (i >= 0) {
            val line = pending.substring(0, i).trim()
            pending.delete(0, i + 1)
            if (line.isNotEmpty() && keyLines.size < 50 && kp.containsMatchIn(line)) keyLines.add(line)
            i = pending.indexOf("\n")
        }
        if (pending.length > 4000) pending.clear()
    }

    fun start() {
        started = true
        running = true
        status = "Berjalan\u2026"
        handle = startShell(
            ctx, run,
            onChunk = {
                output = (output + it.replace("\r", "")).takeLast(MAX_UI_CHARS)
                collect(it)
                tracker.feed(it)
                phase = tracker.phase
                progress = tracker.fraction
            },
            onDone = { r ->
                running = false
                status = describe(r)
                runState = when {
                    r.startError != null || r.timedOut -> RunState.Failed
                    r.cancelled -> RunState.Stopped
                    r.exitCode == 0 -> RunState.Ok
                    else -> RunState.Failed
                }
                collect("\n")
                if (r.startError == null && !r.cancelled && !r.timedOut && r.exitCode == 0) {
                    run.summarize?.invoke(keyLines.toList())?.let {
                        status = status + "\n" + it
                        AppLog.log("system", LogLevel.Info, "tool", "${run.title}: $it")
                    }
                }
                val code = if (r.startError != null || r.cancelled || r.timedOut) -1 else r.exitCode
                AppLog.log("system", LogLevel.Info, "tool", "${run.title} selesai exit=${r.exitCode} ${r.millis}ms")
                onResult(code, output)
            }
        )
    }
    LaunchedEffect(Unit) { if (!run.confirm) start() }

    if (run.confirm && !started) {
        AlertDialog(
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
            confirmButton = { TextButton(onClick = { start() }) { Text("Jalankan") } },
            dismissButton = { TextButton(onClick = onClose) { Text("Batal") } }
        )
    } else {
        AlertDialog(
            onDismissRequest = { if (!running) onClose() },
            title = { Text(run.title) },
            text = {
                Column {
                    RunProgressPanel(phase, progress, runState, status, elapsed)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (showDetail) "Sembunyikan rincian \u25B4" else "Tampilkan rincian \u25BE",
                        color = MaterialTheme.colorScheme.primary, fontSize = 12.sp,
                        modifier = Modifier.clickable { showDetail = !showDetail }.padding(vertical = 4.dp)
                    )
                    if (showDetail) {
                        Box(Modifier.heightIn(max = 280.dp).verticalScroll(scroll)) {
                            Text(output, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    }
                }
            },
            confirmButton = {
                if (running) TextButton(onClick = { handle?.cancel() }) { Text("Berhenti") }
                else TextButton(onClick = onClose) { Text("Tutup") }
            },
            dismissButton = {
                TextButton(onClick = {
                    copyOutput(ctx, listOf(status, output).filter { it.isNotBlank() }.joinToString("\n\n"))
                }) { Text("Salin") }
            }
        )
    }
}

internal fun startShell(ctx: Context, run: ToolRun, onChunk: (String) -> Unit, onDone: (RunResult) -> Unit): RunHandle {
    val main = Handler(Looper.getMainLooper())
    val req = RunRequest(
        name = "tool",
        scriptText = "set -e\n" + run.script.trim() + "\n",
        plan = RunPlan("sh", "/bin/sh", "sh", false),
        hostDir = null,
        timeoutMs = run.timeoutMs
    )
    return ProotScriptExecutor(ctx.applicationContext).start(req, object : RunListener {
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
