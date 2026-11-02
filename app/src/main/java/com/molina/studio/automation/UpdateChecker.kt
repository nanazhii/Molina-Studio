package com.molina.studio.automation

import android.content.Context
import com.molina.code.preview.RunPlan
import com.molina.code.run.RunListener
import com.molina.code.run.RunRequest
import com.molina.code.run.RunResult
import com.molina.core.common.AppLog
import com.molina.core.common.LogLevel
import com.molina.studio.code.ProotScriptExecutor
import com.molina.studio.settings.AptHistory
import com.molina.studio.settings.AptJob
import com.molina.studio.stream.YtdlpLocator
import com.molina.studio.stream.YtdlpRunner
import com.molina.studio.stream.YtdlpStatus
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Pemeriksaan pembaruan tanpa dialog: jumlah paket apt yang bisa ditingkatkan (mengisi lencana ubin Paket)
 * dan versi yt-dlp terbaru (mengisi lencana ubin Paket). Tidak memasang apa pun dan tidak menampilkan notifikasi.
 * Dipakai [UpdateCheckWorker] (otomatis) dan tombol "Periksa sekarang".
 */
object UpdateChecker {
    private const val TAG = "auto"
    private val TAG_RE = Regex("""\d{4}\.\d{2}\.\d{2}(\.\d+)?""")
    private val NP_TAG_RE = Regex("""v\d+\.\d+\.\d+""")
    private val COUNT_RE = Regex("""^\d+$""")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Mulai pemeriksaan di latar (tidak terikat layar); abaikan bila sedang berjalan. */
    fun checkNow(ctx: Context) {
        if (_running.value) return
        val app = ctx.applicationContext
        scope.launch {
            try { checkAll(app) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                AppLog.log("system", LogLevel.Warn, TAG, "periksa gagal: ${e.javaClass.simpleName}")
            }
        }
    }

    suspend fun checkAll(ctx: Context) {
        mutex.withLock {
            _running.value = true
            try { doCheck(ctx.applicationContext) } finally { _running.value = false }
        }
    }

    private suspend fun doCheck(ctx: Context) {
        AutoPrefs.init(ctx)
        AptHistory.init(ctx)
        // 0) NewPipeExtractor: dibundel di APK (tidak butuh Debian); hanya membandingkan dengan rilis terbaru.
        val npLatest = fetchLatestNewPipe()
        if (npLatest.isNotEmpty()) AutoPrefs.recordNewPipe(ctx, npLatest)
        AppLog.log("system", LogLevel.Info, TAG, "newpipe dibundel=${com.molina.engine.newpipe.NewPipeInfo.VERSION} terbaru=${npLatest.ifEmpty { "?" }}")

        val status = YtdlpLocator.status(ctx)
        if (status == YtdlpStatus.NoRootfs) {
            AppLog.log("system", LogLevel.Info, TAG, "dilewati: Debian belum dipasang")
            return
        }

        // 1) yt-dlp: versi terpasang (di-cache; --version memulai proot) vs rilis resmi terbaru.
        if (status is YtdlpStatus.Ready) {
            var installed = AutoPrefs.state.value.ytdlpInstalled
            if (installed.isEmpty()) {
                val o = YtdlpRunner.run(ctx, status.guestBinary, listOf("--version"), 60_000L)
                val first = o.stdout.trim().lineSequence().firstOrNull().orEmpty()
                if (o.exit == 0) installed = first
            }
            val latest = fetchLatestYtdlp()
            AutoPrefs.recordYtdlp(ctx, installed, latest)
            AppLog.log("system", LogLevel.Info, TAG, "yt-dlp terpasang=${installed.ifEmpty { "?" }} terbaru=${latest.ifEmpty { "?" }}")
        } else {
            AutoPrefs.clearYtdlp(ctx)
        }

        // 2) apt: perintah sama dengan "Periksa pembaruan paket"; jalur AUTO agar tidak memblokir editor/unduhan.
        val job = AptJob.CheckUpdates
        val (code, out) = runScript(ctx, job.command, job.timeoutMs)
        if (code == 0) {
            out.lineSequence().map { it.trim() }.lastOrNull { COUNT_RE.matches(it) }?.toIntOrNull()?.let {
                AptHistory.setPending(ctx, it)
                AppLog.log("system", LogLevel.Info, TAG, "apt: $it paket dapat ditingkatkan")
            }
        } else {
            AppLog.log("system", LogLevel.Info, TAG, "apt dilewati/gagal (kode $code), lencana tidak diubah")
        }
        AutoPrefs.markChecked(ctx)
    }

    /** Tag rilis terbaru yt-dlp dari GitHub (HTTPS); kosong bila gagal atau formatnya tak dikenal. */
    private fun fetchLatestYtdlp(): String = fetchLatestTag("yt-dlp/yt-dlp", TAG_RE)

    /** Tag rilis terbaru NewPipeExtractor (mis. v0.26.5); kosong bila gagal. */
    private fun fetchLatestNewPipe(): String = fetchLatestTag("TeamNewPipe/NewPipeExtractor", NP_TAG_RE)

    private fun fetchLatestTag(repo: String, re: Regex): String {
        return try {
            val conn = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "MolinaStudio")
            try {
                if (conn.responseCode != 200) "" else {
                    val body = conn.inputStream.use { it.readBytes() }
                    if (body.size > 1_000_000) "" else {
                        val tag = JSONObject(String(body, Charsets.UTF_8)).optString("tag_name").trim()
                        if (re.matches(tag)) tag else ""
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            ""
        }
    }

    /** Jalankan skrip di Debian PRoot (jalur AUTO) dan tunggu; kode -1 bila gagal mulai/dibatalkan/lewat waktu. */
    private suspend fun runScript(ctx: Context, script: String, timeoutMs: Long): Pair<Int, String> =
        suspendCancellableCoroutine { cont ->
            val sb = StringBuilder()
            val req = RunRequest(
                name = "auto",
                scriptText = "set -e\n" + script.trim() + "\n",
                plan = RunPlan("sh", "/bin/sh", "sh", false),
                hostDir = null,
                timeoutMs = timeoutMs,
                maxOutputChars = 200_000L
            )
            val handle = ProotScriptExecutor(ctx, ProotScriptExecutor.Lane.AUTO).start(req, object : RunListener {
                override fun onOutput(text: String, isError: Boolean) {
                    synchronized(sb) { if (sb.length < 200_000) sb.append(text) }
                }

                override fun onFinished(result: RunResult) {
                    val code = if (result.startError != null || result.cancelled || result.timedOut) -1 else result.exitCode
                    val text = synchronized(sb) { sb.toString() }
                    if (cont.isActive) cont.resume(code to text)
                }
            })
            cont.invokeOnCancellation { handle.cancel() }
        }
}
