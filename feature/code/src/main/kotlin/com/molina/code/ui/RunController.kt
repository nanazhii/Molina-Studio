package com.molina.code.ui

import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.molina.code.preview.OutLine
import com.molina.code.preview.OutputBuffer
import com.molina.code.preview.RunPlan
import com.molina.code.run.RunHandle
import com.molina.code.run.RunListener
import com.molina.code.run.RunRequest
import com.molina.code.run.RunResult
import com.molina.code.run.ScriptExecutor
import java.util.concurrent.atomic.AtomicBoolean

internal enum class RunStatus { Idle, Running, Done, Failed, Stopped, TimedOut }

/**
 * Status satu eksekusi skrip untuk panel Keluaran. Keluaran masuk dari thread latar, dikumpulkan
 * di [OutputBuffer] (berbatas), lalu dipublikasikan ke state Compose paling sering tiap
 * [FLUSH_MS] supaya keluaran deras (mis. `yes`) tidak membanjiri recomposition.
 */
internal class RunController(private val main: Handler) {
    var executor: ScriptExecutor? = null

    var status by mutableStateOf(RunStatus.Idle)
        private set
    var title by mutableStateOf("")
        private set
    var interpreter by mutableStateOf("")
        private set
    var summary by mutableStateOf("")
        private set
    var lines by mutableStateOf<List<OutLine>>(emptyList())
        private set

    /** Naik tiap [lines] berubah; kunci efek auto-scroll. */
    var version by mutableIntStateOf(0)
        private set

    val running: Boolean get() = status == RunStatus.Running

    private val buffer = OutputBuffer()
    private var handle: RunHandle? = null
    private var generation = 0
    private val flushScheduled = AtomicBoolean(false)
    private val flushTask = Runnable {
        flushScheduled.set(false)
        publish()
    }

    /** Mulai skrip. False bila belum ada eksekutor atau masih ada yang berjalan. */
    fun start(name: String, text: String, plan: RunPlan, hostDir: String?): Boolean {
        val ex = executor ?: return false
        if (running) return false
        val gen = ++generation
        synchronized(buffer) { buffer.clear() }
        title = name
        interpreter = plan.label
        summary = ""
        status = RunStatus.Running
        publish()
        handle = ex.start(
            RunRequest(name, text, plan, hostDir, TIMEOUT_MS),
            object : RunListener {
                override fun onOutput(text: String, isError: Boolean) {
                    if (gen != generation) return
                    synchronized(buffer) { buffer.append(text, isError) }
                    if (flushScheduled.compareAndSet(false, true)) main.postDelayed(flushTask, FLUSH_MS)
                }

                override fun onFinished(result: RunResult) {
                    main.post { if (gen == generation) finish(result) }
                }
            }
        )
        return true
    }

    fun stop() {
        if (!running) return
        summary = "Menghentikan\u2026"
        handle?.cancel()
    }

    fun clear() {
        if (running) return
        synchronized(buffer) { buffer.clear() }
        status = RunStatus.Idle
        summary = ""
        publish()
    }

    /** Dipanggil saat sesi dilepas: hentikan skrip dan abaikan callback yang masih datang. */
    fun release() {
        generation++
        main.removeCallbacks(flushTask)
        flushScheduled.set(false)
        handle?.cancel()
        handle = null
    }

    private fun publish() {
        lines = synchronized(buffer) { buffer.snapshot() }
        version++
    }

    private fun finish(r: RunResult) {
        main.removeCallbacks(flushTask)
        flushScheduled.set(false)
        handle = null
        val err = r.startError
        if (err != null) {
            synchronized(buffer) { buffer.append(err + "\n", true) }
        }
        publish()
        val time = formatSeconds(r.millis)
        val note = if (r.truncated) " \u00B7 keluaran dipotong" else ""
        when {
            err != null -> { status = RunStatus.Failed; summary = "Tidak berjalan" }
            r.cancelled -> { status = RunStatus.Stopped; summary = "Dihentikan \u00B7 $time" }
            r.timedOut -> { status = RunStatus.TimedOut; summary = "Waktu habis \u00B7 ${TIMEOUT_MS / 1000} dtk" }
            r.exitCode == 0 -> { status = RunStatus.Done; summary = "Selesai \u00B7 exit 0 \u00B7 $time$note" }
            else -> { status = RunStatus.Failed; summary = "Gagal \u00B7 exit ${r.exitCode} \u00B7 $time$note" }
        }
    }

    companion object {
        private const val FLUSH_MS = 100L
        const val TIMEOUT_MS = 60_000L

        /** 1234 -> "1,23 dtk" (koma desimal Indonesia, tanpa bergantung locale perangkat). */
        fun formatSeconds(ms: Long): String {
            val whole = ms / 1000
            val frac = (ms % 1000) / 10
            return "$whole,${frac.toString().padStart(2, '0')} dtk"
        }
    }
}
