package com.molina.studio.settings

import android.content.Context
import android.widget.Toast
import com.molina.code.run.RunHandle
import com.molina.core.common.AppLog
import com.molina.core.common.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [MINIMIZE-RUN] Potret keadaan satu proses Paket/apt/yt-dlp yang sedang atau baru selesai berjalan. */
internal data class ToolRunSnapshot(
    val title: String,
    val running: Boolean,
    val minimized: Boolean,
    val output: String,
    val status: String,
    val phase: String,
    val progress: Float?,
    val state: RunState,
    val startedAtMs: Long
)

/**
 * [MINIMIZE-RUN] Pemegang tunggal proses Paket di luar komposisi, agar proses tetap hidup dan bisa dibuka
 * lagi saat pengguna berpindah tab. Semua pemanggilan dari main thread. Hanya satu proses pada satu waktu.
 */
internal object ToolRunHub {
    private val _session = MutableStateFlow<ToolRunSnapshot?>(null)
    val session: StateFlow<ToolRunSnapshot?> = _session.asStateFlow()

    private var handle: RunHandle? = null
    private var tracker = RunProgressTracker()
    private val keyLines = mutableListOf<String>()
    private val pending = StringBuilder()

    /** @return false bila masih ada proses lain yang berjalan (tidak memulai yang baru). */
    fun start(ctx: Context, run: ToolRun, onResult: (Int, String) -> Unit): Boolean {
        if (_session.value?.running == true) {
            Toast.makeText(ctx.applicationContext, "Masih ada proses Paket yang berjalan", Toast.LENGTH_SHORT).show()
            return false
        }
        val app = ctx.applicationContext
        tracker = RunProgressTracker()
        keyLines.clear()
        pending.setLength(0)
        _session.value = ToolRunSnapshot(
            title = run.title, running = true, minimized = false, output = "", status = "Berjalan\u2026",
            phase = tracker.phase, progress = null, state = RunState.Running, startedAtMs = System.currentTimeMillis()
        )
        handle = startShell(
            app, run,
            onChunk = { chunk ->
                collect(run, chunk)
                tracker.feed(chunk)
                update { it.copy(output = (it.output + chunk.replace("\r", "")).takeLast(MAX_UI_CHARS), phase = tracker.phase, progress = tracker.fraction) }
            },
            onDone = { r ->
                var status = describe(r)
                val state = when {
                    r.startError != null || r.timedOut -> RunState.Failed
                    r.cancelled -> RunState.Stopped
                    r.exitCode == 0 -> RunState.Ok
                    else -> RunState.Failed
                }
                collect(run, "\n")
                if (r.startError == null && !r.cancelled && !r.timedOut && r.exitCode == 0) {
                    run.summarize?.invoke(keyLines.toList())?.let {
                        status = status + "\n" + it
                        AppLog.log("system", LogLevel.Info, "tool", "${run.title}: $it")
                    }
                }
                handle = null
                update { it.copy(running = false, status = status, state = state) }
                val code = if (r.startError != null || r.cancelled || r.timedOut) -1 else r.exitCode
                AppLog.log("system", LogLevel.Info, "tool", "${run.title} selesai exit=${r.exitCode} ${r.millis}ms")
                val out = _session.value?.output.orEmpty()
                onResult(code, out)
                if (_session.value?.minimized == true) {
                    val msg = when (state) {
                        RunState.Ok -> "${run.title}: selesai"
                        RunState.Stopped -> "${run.title}: dihentikan"
                        else -> "${run.title}: gagal"
                    }
                    Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                }
            }
        )
        return true
    }

    fun cancel() { handle?.cancel() }
    fun minimize() = update { it.copy(minimized = true) }
    fun restore() = update { it.copy(minimized = false) }

    /** Menutup panel hasil. Tidak menghentikan proses yang masih berjalan (pakai [cancel] untuk itu). */
    fun close() { if (_session.value?.running != true) _session.value = null }

    private fun update(f: (ToolRunSnapshot) -> ToolRunSnapshot) {
        _session.value?.let { _session.value = f(it) } // sinkron: semua pemanggil sudah di main thread
    }

    private fun collect(run: ToolRun, text: String) {
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
}
