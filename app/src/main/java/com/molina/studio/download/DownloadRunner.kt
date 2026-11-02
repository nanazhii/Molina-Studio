package com.molina.studio.download

import android.content.Context
import com.molina.code.preview.RunPlan
import com.molina.code.run.RunHandle
import com.molina.code.run.RunListener
import com.molina.code.run.RunRequest
import com.molina.code.run.RunResult
import com.molina.core.common.AppLog
import com.molina.core.common.DownloadCommand
import com.molina.core.common.DownloadFeed
import com.molina.core.common.DownloadHistory
import com.molina.core.common.DownloadItemStatus
import com.molina.core.common.DownloadMaintenance
import com.molina.core.common.DownloadRequest
import com.molina.core.common.DownloadUrlGuard
import com.molina.core.common.DownloadRunState
import com.molina.core.common.DownloadSessionState
import com.molina.core.common.LogLevel
import com.molina.core.common.LogRedact
import com.molina.core.common.MaintenanceKind
import com.molina.studio.cinema.SeenStore
import com.molina.studio.code.ProotScriptExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Menjalankan `molina_dl.py --json` di Debian PRoot dan mengubah keluarannya menjadi [state] untuk UI.
 * - Skrip yang dipakai selalu salinan bawaan di assets (protokol v1 dijamin cocok), bukan salinan di Scripts/
 *   yang mungkin sudah diedit pengguna.
 * - Satu sesi pada satu waktu. Berjalan di jalur DOWNLOAD executor: tidak memblokir editor Code dan tidak ikut
 *   dibatalkan Penyegaran sistem.
 * - Keluaran tidak dibatasi (progres menghasilkan banyak baris) tetapi tidak disimpan: hanya state hasil lipatan
 *   [DownloadFeed]. URL tidak pernah masuk log; log hanya memuat jumlah dan hasil.
 */
internal object DownloadRunner {
    private val _state = MutableStateFlow(DownloadRunState())
    val state: StateFlow<DownloadRunState> = _state.asStateFlow()

    private val lock = Any()
    private var feed: DownloadFeed? = null
    private var handle: RunHandle? = null

    /** Permintaan sesi terakhir: dasar tombol Ulangi (mode/kualitas sama). Dikosongkan oleh [clear]. */
    @Volatile private var lastReq: DownloadRequest? = null

    /** Jenis sesi perawatan yang sedang/terakhir jalan; null untuk sesi unduhan. Diisi sebelum state terbit. */
    @Volatile private var maint: MaintenanceKind? = null

    fun maintenanceKind(): MaintenanceKind? = maint

    /** Detail teknis (pesan mentah yt-dlp, stderr) hanya ke Log; layar pengguna memakai teks ramah. */
    private fun logFailures(s: DownloadSessionState, tail: List<String>) {
        s.items.filter { it.status == DownloadItemStatus.FAILED && !it.errorRaw.isNullOrBlank() }.forEach {
            AppLog.log("sistem", LogLevel.Warn, "Unduhan", "item ${it.index} gagal [${it.errorCode}]: ${LogRedact.text(it.errorRaw.orEmpty())}")
        }
        s.fatalRaw?.takeIf { it.isNotBlank() }?.let {
            AppLog.log("sistem", LogLevel.Warn, "Unduhan", "galat global: ${LogRedact.text(it)}")
        }
        if (tail.isNotEmpty() && (s.fatalError != null || s.failed > 0)) {
            AppLog.log("sistem", LogLevel.Debug, "Unduhan", "stderr: " + tail.takeLast(5).joinToString(" | "))
        }
    }

    fun isRunning(): Boolean = _state.value.running

    /** @return pesan galat siap tampil, atau null bila sesi unduhan dimulai. */
    fun start(ctx: Context, request: DownloadRequest): String? {
        val req = request.copy(allowLan = com.molina.core.common.NetworkPolicy.allowLan) // [DL-NETGUARD]
        DownloadCommand.validate(req)?.let { return it }
        DownloadUrlGuard.check(req.urls)?.let { return it } // [DL-SSRF-GUARD]
        val app = ctx.applicationContext
        // [WIFI-ONLY] pengguna memilih unduh hanya di jaringan tidak berkuota
        if (DownloadPrefs.wifiOnly(app) && NetworkGuard.isMetered(app)) return NetworkGuard.BLOCK_MSG
        val script = try {
            app.assets.open(DownloadCommand.SCRIPT_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            return "Skrip unduhan bawaan tidak ditemukan di aplikasi."
        }
        val f = DownloadFeed()
        synchronized(lock) {
            if (_state.value.running) return "Masih ada unduhan yang berjalan."
            feed = f
            lastReq = req
            maint = null
            handle = null
            _state.value = f.state
        }
        val t0 = System.nanoTime()
        AppLog.log("sistem", LogLevel.Info, "Unduhan", "mulai: ${DownloadCommand.clean(req.urls).size} URL, mode ${req.mode.arg}")
        val rr = RunRequest(
            name = "molina_dl",
            scriptText = script,
            plan = RunPlan("python3", "/usr/bin/python3", "py", true),
            hostDir = null,
            timeoutMs = DownloadCommand.TIMEOUT_MS,
            args = DownloadCommand.args(req),
            maxOutputChars = Long.MAX_VALUE,
        )
        val h = ProotScriptExecutor(app, ProotScriptExecutor.Lane.DOWNLOAD).start(rr, object : RunListener {
            override fun onOutput(text: String, isError: Boolean) {
                synchronized(lock) {
                    if (feed === f && f.push(text, isError)) _state.value = f.state
                }
            }

            override fun onFinished(result: RunResult) {
                val done = synchronized(lock) {
                    if (feed !== f) return
                    f.finish(result.cancelled, result.exitCode, result.startError)
                    // Catat ke riwayat SEBELUM state selesai terbit agar UI tidak sempat menyembunyikan entri yang salah.
                    DownloadHistory.build(System.currentTimeMillis(), req, f.state.session)?.let { DownloadHistoryStore.add(it) }
                    // [SEEN-BADGES] tandai video yang berhasil diunduh (lencana "Diunduh" di hasil pencarian)
                    val requested = DownloadCommand.clean(req.urls).take(DownloadCommand.MAX_URLS)
                    SeenStore.markDownloaded(
                        app,
                        f.state.session.items.filter { st -> st.status == DownloadItemStatus.DONE }
                            .map { st -> st.url.ifEmpty { requested.getOrNull(st.index).orEmpty() } },
                    )
                    _state.value = f.state
                    handle = null
                    f.state
                }
                val s = done.session
                logFailures(s, done.stderrTail)
                val secs = (System.nanoTime() - t0) / 1_000_000_000L
                val level = if (result.startError != null || s.fatalError != null) LogLevel.Warn else LogLevel.Info
                AppLog.log(
                    "sistem", level, "Unduhan",
                    "selesai: ${s.ok} berhasil, ${s.failed} gagal" + (if (s.cancelled) " (dibatalkan)" else "") +
                        " dalam ${secs}dtk, kode ${result.exitCode}" + (if (result.timedOut) " (lewat waktu)" else ""),
                )
            }
        })
        // onFinished bisa datang sebelum start() kembali (mis. jalur sibuk): jangan simpan handle usang.
        synchronized(lock) { if (feed === f && _state.value.running) handle = h }
        if (_state.value.running) DownloadService.ensureRunning(app)
        return null
    }

    /**
     * Sesi perawatan: `--doctor` atau `--update-ytdlp` (tanpa URL). Jalur, skrip bawaan, dan state sama dengan unduhan,
     * tetapi tidak masuk riwayat dan tidak bisa diulang. Satu sesi pada satu waktu.
     * @return pesan galat siap tampil, atau null bila sesi perawatan dimulai.
     */
    fun startMaintenance(ctx: Context, kind: MaintenanceKind): String? {
        val app = ctx.applicationContext
        val script = try {
            app.assets.open(DownloadCommand.SCRIPT_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            return "Skrip unduhan bawaan tidak ditemukan di aplikasi."
        }
        val f = DownloadFeed()
        synchronized(lock) {
            if (_state.value.running) return "Masih ada unduhan yang berjalan."
            feed = f
            lastReq = null
            maint = kind
            handle = null
            _state.value = f.state
        }
        val t0 = System.nanoTime()
        AppLog.log("sistem", LogLevel.Info, "Unduhan", "perawatan: ${kind.label}")
        val rr = RunRequest(
            name = "molina_dl",
            scriptText = script,
            plan = RunPlan("python3", "/usr/bin/python3", "py", true),
            hostDir = null,
            timeoutMs = DownloadMaintenance.TIMEOUT_MS,
            args = DownloadMaintenance.args(kind),
            maxOutputChars = Long.MAX_VALUE,
        )
        val h = ProotScriptExecutor(app, ProotScriptExecutor.Lane.DOWNLOAD).start(rr, object : RunListener {
            override fun onOutput(text: String, isError: Boolean) {
                synchronized(lock) {
                    if (feed === f && f.push(text, isError)) _state.value = f.state
                }
            }

            override fun onFinished(result: RunResult) {
                val done = synchronized(lock) {
                    if (feed !== f) return
                    f.finish(result.cancelled, result.exitCode, result.startError)
                    _state.value = f.state
                    handle = null
                    f.state
                }
                val s = done.session
                logFailures(s, done.stderrTail)
                val secs = (System.nanoTime() - t0) / 1_000_000_000L
                val bad = result.startError != null || s.fatalError != null
                AppLog.log(
                    "sistem", if (bad) LogLevel.Warn else LogLevel.Info, "Unduhan",
                    "perawatan ${kind.label} selesai dalam ${secs}dtk, kode ${result.exitCode}" + (if (result.timedOut) " (lewat waktu)" else ""),
                )
            }
        })
        synchronized(lock) { if (feed === f && _state.value.running) handle = h }
        if (_state.value.running) DownloadService.ensureRunning(app)
        return null
    }

    /** Batalkan sesi yang berjalan. Berkas .part tetap ada sehingga unduhan bisa dilanjutkan dengan URL yang sama. */
    fun cancel() {
        val h = synchronized(lock) { handle }
        h?.cancel()
    }

    /** URL yang bisa diulang dari sesi terakhir (gagal, tak selesai, atau tak sempat mulai). Kosong saat berjalan/dikosongkan. */
    fun retryUrls(): List<String> {
        val req = lastReq ?: return emptyList()
        val st = _state.value
        if (st.running) return emptyList()
        return DownloadHistory.build(0L, req, st.session)?.retryUrls().orEmpty()
    }

    /**
     * Ulangi sesi terakhir dengan mode/kualitas yang sama. [only] = satu URL saja; null = semua yang belum berhasil.
     * @return pesan galat siap tampil, atau null bila sesi baru dimulai.
     */
    fun retry(ctx: Context, only: String? = null): String? {
        val req = lastReq ?: return "Tidak ada unduhan untuk diulang."
        val urls = if (only != null) listOf(only) else retryUrls()
        if (urls.isEmpty()) return "Tidak ada unduhan untuk diulang."
        return start(ctx, req.copy(urls = urls))
    }

    /** Kosongkan hasil sesi yang sudah selesai (tidak berpengaruh saat masih berjalan). */
    fun clear() {
        synchronized(lock) {
            if (!_state.value.running) {
                feed = null
                lastReq = null
                maint = null
                _state.value = DownloadRunState()
            }
        }
    }
}
