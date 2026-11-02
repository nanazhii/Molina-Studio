package com.molina.code.ui

import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.molina.code.check.BuiltinCheck
import com.molina.code.check.CheckException
import com.molina.code.check.CheckPlan
import com.molina.code.check.Diagnostic
import com.molina.code.check.ExternalCheck
import com.molina.code.preview.RunPlan
import com.molina.code.run.RunHandle
import com.molina.code.run.RunListener
import com.molina.code.run.RunRequest
import com.molina.code.run.RunResult
import com.molina.code.run.ScriptExecutor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Hasil satu kali periksa. */
internal sealed class CheckOutcome {
    class Ok(val items: List<Diagnostic>) : CheckOutcome()
    class Failed(val message: String) : CheckOutcome()
    /** Dibatalkan (tab ditutup atau sesi dilepas): tidak perlu pesan. */
    object Cancelled : CheckOutcome()
}

/**
 * [CODE-CHECK] Menjalankan satu pemeriksaan pada satu waktu (diagnostik sekali jalan, tanpa proses menetap).
 * Pemeriksa bawaan jalan di thread latar milik controller; pemeriksa Debian lewat [executor] (stdin -> stdout,
 * berkas di disk tidak disentuh). [onDone] dipanggil di main thread tepat sekali; hasil basi dibuang.
 */
internal class CheckController(private val main: Handler) {
    var executor: ScriptExecutor? = null

    /** True selama pemeriksaan berjalan; dipakai menu untuk meredupkan "Periksa kode". */
    var busy by mutableStateOf(false)
        private set

    private val pool: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "molina-check").apply { isDaemon = true }
    }
    private var generation = 0
    private var handle: RunHandle? = null

    /** False bila masih ada pemeriksaan yang berjalan. */
    fun start(plan: CheckPlan, name: String, text: String, hostDir: String?, onDone: (CheckOutcome) -> Unit): Boolean {
        if (busy) return false
        val gen = ++generation
        busy = true
        fun finish(o: CheckOutcome) {
            main.post {
                if (gen != generation) return@post
                busy = false
                handle = null
                onDone(o)
            }
        }
        when (plan) {
            is BuiltinCheck -> try {
                pool.execute {
                    val outcome = try {
                        CheckOutcome.Ok(plan.run(text))
                    } catch (e: CheckException) {
                        CheckOutcome.Failed(e.message ?: "Gagal memeriksa")
                    } catch (e: Exception) {
                        CheckOutcome.Failed("Gagal memeriksa: ${e.javaClass.simpleName}")
                    }
                    finish(outcome)
                }
            } catch (e: RejectedExecutionException) {
                finish(CheckOutcome.Cancelled)
            }
            is ExternalCheck -> startExternal(plan, name, text, hostDir, ::finish)
        }
        return true
    }

    private fun startExternal(plan: ExternalCheck, name: String, text: String, hostDir: String?, finish: (CheckOutcome) -> Unit) {
        val ex = executor
        if (ex == null) {
            finish(CheckOutcome.Failed("Eksekutor Debian belum terpasang"))
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
            timeoutMs = TIMEOUT_MS,
            maxOutputChars = MAX_OUT_CHARS,
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
                finish(outcomeOf(plan, result, synchronized(out) { out.toString() }, synchronized(err) { err.toString() }))
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
        const val TIMEOUT_MS = 30_000L
        private const val MAX_OUT_CHARS = 400_000L
        private const val MAX_ERR_CHARS = 4_000

        /** Ubah hasil proses jadi [CheckOutcome]. Exit code temuan (mis. 1) diserahkan ke parser rencana. */
        internal fun outcomeOf(plan: ExternalCheck, r: RunResult, stdout: String, stderr: String): CheckOutcome {
            r.startError?.let { return CheckOutcome.Failed(it) }
            return when {
                r.cancelled -> CheckOutcome.Cancelled
                r.timedOut -> CheckOutcome.Failed("${plan.label}: waktu habis (${TIMEOUT_MS / 1000} dtk)")
                r.truncated -> CheckOutcome.Failed("${plan.label}: keluaran terlalu besar, dibatalkan")
                r.exitCode == 127 -> CheckOutcome.Failed(plan.installHint)
                else -> try {
                    CheckOutcome.Ok(plan.parse(stdout.replace("\r\n", "\n"), stderr.replace("\r\n", "\n"), r.exitCode))
                } catch (e: CheckException) {
                    CheckOutcome.Failed(e.message ?: "${plan.label}: gagal")
                }
            }
        }
    }
}
