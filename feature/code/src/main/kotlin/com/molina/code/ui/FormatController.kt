package com.molina.code.ui

import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.molina.code.format.BuiltinPlan
import com.molina.code.format.ExternalPlan
import com.molina.code.format.FormatException
import com.molina.code.format.FormatPlan
import com.molina.code.preview.RunPlan
import com.molina.code.run.RunHandle
import com.molina.code.run.RunListener
import com.molina.code.run.RunRequest
import com.molina.code.run.RunResult
import com.molina.code.run.ScriptExecutor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Hasil satu kali format. [text] sudah memakai akhir baris LF. */
internal sealed class FormatOutcome {
    class Ok(val text: String) : FormatOutcome()
    class Failed(val message: String) : FormatOutcome()
    /** Dibatalkan (tab ditutup atau sesi dilepas): tidak perlu pesan. */
    object Cancelled : FormatOutcome()
}

/**
 * [CODE-FORMAT] Menjalankan satu pemformat pada satu waktu. Pemformat bawaan jalan di thread latar
 * milik controller; pemformat Debian lewat [executor] (stdin -> stdout, tanpa menyentuh berkas).
 * Semua callback [onDone] dipanggil di main thread tepat sekali. Hasil basi (generasi lama) dibuang.
 */
internal class FormatController(private val main: Handler) {
    var executor: ScriptExecutor? = null

    /** True selama pemformatan berjalan; dipakai menu untuk meredupkan "Format kode". */
    var busy by mutableStateOf(false)
        private set

    private val pool: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "molina-format").apply { isDaemon = true }
    }
    private var generation = 0
    private var handle: RunHandle? = null

    /** False bila masih ada pemformatan yang berjalan. */
    fun start(
        plan: FormatPlan, name: String, text: String, hostDir: String?,
        timeoutMs: Long = TIMEOUT_MS, onDone: (FormatOutcome) -> Unit,
    ): Boolean {
        if (busy) return false
        val gen = ++generation
        busy = true
        fun finish(o: FormatOutcome) {
            main.post {
                if (gen != generation) return@post
                busy = false
                handle = null
                onDone(o)
            }
        }
        when (plan) {
            is BuiltinPlan -> try {
                pool.execute {
                    val outcome = try {
                        FormatOutcome.Ok(plan.format(text.replace("\r\n", "\n")))
                    } catch (e: FormatException) {
                        FormatOutcome.Failed(e.message ?: "Gagal memformat")
                    } catch (e: StackOverflowError) {
                        FormatOutcome.Failed("Struktur terlalu dalam untuk diformat")
                    } catch (e: Exception) {
                        FormatOutcome.Failed("Gagal memformat: ${e.javaClass.simpleName}")
                    }
                    finish(outcome)
                }
            } catch (e: RejectedExecutionException) {
                finish(FormatOutcome.Cancelled)
            }
            is ExternalPlan -> startExternal(plan, name, text, hostDir, timeoutMs, ::finish)
        }
        return true
    }

    private fun startExternal(plan: ExternalPlan, name: String, text: String, hostDir: String?, timeoutMs: Long, finish: (FormatOutcome) -> Unit) {
        val ex = executor
        if (ex == null) {
            finish(FormatOutcome.Failed("Eksekutor Debian belum terpasang"))
            return
        }
        val out = StringBuilder()
        val err = StringBuilder()
        val input = text.replace("\r\n", "\n")
        val req = RunRequest(
            name = name,
            scriptText = "", // tidak dipakai pada mode argv; isi tidak pernah dicatat
            plan = RunPlan(plan.label, plan.argv.first(), "txt", false),
            hostDir = hostDir,
            timeoutMs = timeoutMs,
            maxOutputChars = (input.length * 2L + 20_000L).coerceAtLeast(100_000L),
            guestArgv = plan.argv,
            stdinText = input,
            missingHint = plan.installHint,
        )
        handle = ex.start(req, object : RunListener {
            override fun onOutput(text: String, isError: Boolean) {
                if (isError) synchronized(err) { if (err.length < MAX_ERR_CHARS) err.append(text) }
                else synchronized(out) { out.append(text) }
            }

            override fun onFinished(result: RunResult) {
                finish(outcomeOf(plan, input, result, synchronized(out) { out.toString() }, synchronized(err) { err.toString() }, timeoutMs))
            }
        })
    }

    fun cancel() {
        handle?.cancel()
    }

    /** Dipanggil saat sesi dilepas: hentikan proses dan buang hasil yang masih datang. */
    fun release() {
        generation++
        handle?.cancel()
        handle = null
        busy = false
        pool.shutdownNow()
    }

    companion object {
        const val TIMEOUT_MS = 45_000L
        private const val MAX_ERR_CHARS = 4_000

        /** Murni: ubah hasil proses jadi [FormatOutcome]. Keluaran kosong untuk masukan non-kosong dianggap gagal. */
        internal fun outcomeOf(plan: ExternalPlan, input: String, r: RunResult, stdout: String, stderr: String, timeoutMs: Long = TIMEOUT_MS): FormatOutcome {
            r.startError?.let { return FormatOutcome.Failed(it) }
            return when {
                r.cancelled -> FormatOutcome.Cancelled
                r.timedOut -> FormatOutcome.Failed("${plan.label}: waktu habis (${timeoutMs / 1000} dtk)")
                r.truncated -> FormatOutcome.Failed("${plan.label}: keluaran terlalu besar, dibatalkan")
                r.exitCode == 127 -> FormatOutcome.Failed(plan.installHint)
                r.exitCode != 0 -> FormatOutcome.Failed("${plan.label}: ${firstError(stderr, stdout)}")
                stdout.isBlank() && input.isNotBlank() -> FormatOutcome.Failed("${plan.label}: keluaran kosong, teks tidak diubah")
                else -> FormatOutcome.Ok(stdout.replace("\r\n", "\n"))
            }
        }

        /** Baris galat pertama yang bermakna, maks. 160 karakter. */
        internal fun firstError(stderr: String, stdout: String): String {
            val line = (stderr.ifBlank { stdout }).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            return (line ?: "gagal (tanpa pesan)").take(160)
        }
    }
}
