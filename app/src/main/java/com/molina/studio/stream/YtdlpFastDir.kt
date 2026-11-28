package com.molina.studio.stream

import android.content.Context
import com.molina.core.api.AppLog
import com.molina.core.api.LogLevel
import com.molina.core.download.YtdlpFast
import com.molina.core.download.YtdlpInstall
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * [YTDL-FAST] yt-dlp ter-ekstrak untuk resolver aplikasi. Zipapp resmi di /usr/local/bin tidak disentuh (Terminal,
 * molina_dl.py, pembaruan tetap sama). Direktori hanya dipakai bila penanda cocok dengan zipapp saat ini; selain itu
 * (belum ada, basi setelah pembaruan, rusak) resolver memakai zipapp dan ekstraksi disusun ulang di latar, tanpa
 * mengunci [YtdlpRunner]. .pyc ditulis sendiri oleh proses yt-dlp pertama (PYTHONDONTWRITEBYTECODE dimatikan untuk jalur ini).
 */
internal object YtdlpFastDir {
    private const val TAG = "ytdlp-fast"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var busy = false

    /** [YTDL-WARM] Status penyiapan untuk UI. Preparing = ekstraksi/penghangatan .pyc sedang berjalan; Ready = direktori bisa dipakai. */
    enum class Phase { Idle, Preparing, Ready }
    private val _phase = MutableStateFlow(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    @Volatile private var warming = false
    @Volatile private var warmJob: Job? = null
    private const val STARTUP_DELAY_MS = 6_000L

    private val zipappGuest = "/" + YtdlpInstall.TARGET_GUEST_PATH

    private fun usable(ctx: Context): Boolean {
        val src = YtdlpLocator.hostFile(ctx, zipappGuest)
        val marker = File(YtdlpLocator.hostFile(ctx, YtdlpFast.GUEST_DIR), YtdlpFast.MARKER)
        val text = try { marker.takeIf { it.isFile }?.readText() } catch (e: java.io.IOException) { null }
        return src.isFile && YtdlpFast.usable(text, src.length(), src.lastModified())
    }

    /** Jalankan yt-dlp lewat direktori ter-ekstrak bila siap; jika tidak (atau rusak) lewat [bin] seperti biasa. */
    suspend fun run(ctx: Context, bin: String, args: List<String>, timeoutMs: Long): YtdlpRunner.Output {
        if (bin != zipappGuest) return YtdlpRunner.run(ctx, bin, args, timeoutMs)
        if (!usable(ctx)) {
            ensureAsync(ctx)
            return YtdlpRunner.run(ctx, bin, args, timeoutMs)
        }
        preemptWarm() // [YTDL-WARM] permintaan pengguna selalu didahulukan dari penghangatan
        val o = YtdlpRunner.run(ctx, YtdlpFast.GUEST_DIR, args, timeoutMs, writeBytecode = true)
        if (!warmed(ctx)) ensureAsync(ctx) // [YTDL-WARM] lanjutkan penghangatan (compileall bersifat inkremental)
        if (o.exit != 0 && !o.timedOut && YtdlpFast.looksBroken(o.stderr)) {
            AppLog.log("stream", LogLevel.Warn, TAG, "direktori ter-ekstrak rusak; kembali ke zipapp dan disusun ulang")
            invalidate(ctx)
            ensureAsync(ctx)
            return YtdlpRunner.run(ctx, bin, args, timeoutMs)
        }
        return o
    }

    private fun invalidate(ctx: Context) {
        File(YtdlpLocator.hostFile(ctx, YtdlpFast.GUEST_DIR), YtdlpFast.MARKER).delete()
    }

    private fun warmed(ctx: Context): Boolean {
        val src = YtdlpLocator.hostFile(ctx, zipappGuest)
        val marker = File(YtdlpLocator.hostFile(ctx, YtdlpFast.GUEST_DIR), YtdlpFast.WARM_MARKER)
        val text = try { marker.takeIf { it.isFile }?.readText() } catch (e: java.io.IOException) { null }
        return src.isFile && YtdlpFast.usable(text, src.length(), src.lastModified())
    }

    private fun preemptWarm() { if (warming) warmJob?.cancel() }

    private inline fun <T> lowPriority(block: () -> T): T {
        val tid = android.os.Process.myTid()
        val old = android.os.Process.getThreadPriority(tid)
        android.os.Process.setThreadPriority(tid, android.os.Process.THREAD_PRIORITY_BACKGROUND)
        try { return block() } finally { android.os.Process.setThreadPriority(tid, old) }
    }

    /**
     * [YTDL-WARM] Dipanggil sekali dari MolinaApp. Begitu yt-dlp aktif (saat start atau saat dinyalakan di Setelan),
     * mesin disiapkan di latar setelah jeda singkat agar startup aplikasi tidak terbebani.
     */
    fun startWarmup(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            StreamEnginePrefs.ytdlpEnabled.collect { on ->
                if (!on) return@collect
                delay(STARTUP_DELAY_MS)
                if (!StreamEnginePrefs.ytdlpEnabled.value) return@collect
                if (YtdlpLocator.status(app) is YtdlpStatus.Ready) ensureAsync(app)
            }
        }
    }

    /** Ekstrak (bila belum siap) lalu hangatkan .pyc di latar; aman dipanggil berulang. */
    fun ensureAsync(ctx: Context) {
        val app = ctx.applicationContext
        if (busy) return
        val needBuild = !usable(app)
        if (!needBuild && warmed(app)) { _phase.value = Phase.Ready; return }
        busy = true
        _phase.value = Phase.Preparing
        warmJob = scope.launch {
            try {
                if (needBuild) {
                    val t0 = System.nanoTime()
                    val ok = lowPriority { rebuild(app) }
                    AppLog.log("stream", LogLevel.Info, TAG,
                        if (ok) "direktori ter-ekstrak siap ${(System.nanoTime() - t0) / 1_000_000}ms" else "ekstraksi dilewati")
                    if (!ok) return@launch
                }
                warm(app)
            } catch (e: CancellationException) {
                AppLog.log("stream", LogLevel.Info, TAG, "penghangatan dijeda karena ada resolusi; dilanjutkan nanti")
                throw e
            } catch (e: Exception) {
                AppLog.log("stream", LogLevel.Warn, TAG, "penyiapan gagal: ${e.javaClass.simpleName}")
            } finally {
                warming = false
                busy = false
                _phase.value = if (usable(app)) Phase.Ready else Phase.Idle
            }
        }
    }

    /** [YTDL-WARM] Tulis .pyc seluruh paket yt_dlp sekali; penanda ditulis hanya bila selesai utuh dan zipapp tak berubah. */
    private suspend fun warm(app: Context) {
        val src = YtdlpLocator.hostFile(app, zipappGuest)
        val size = src.length()
        val mtime = src.lastModified()
        val t0 = System.nanoTime()
        warming = true
        val o = YtdlpRunner.run(app, "-m", YtdlpFast.compileArgs(YtdlpFast.GUEST_DIR), YtdlpFast.WARM_TIMEOUT_MS, writeBytecode = true)
        warming = false
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (o.exit == 0 && !o.timedOut && src.length() == size && src.lastModified() == mtime) {
            File(YtdlpLocator.hostFile(app, YtdlpFast.GUEST_DIR), YtdlpFast.WARM_MARKER).writeText(YtdlpFast.marker(size, mtime))
            AppLog.log("stream", LogLevel.Info, TAG, "bytecode dihangatkan ${ms}ms")
        } else {
            AppLog.log("stream", LogLevel.Warn, TAG, "penghangatan belum selesai exit=${o.exit} lewatWaktu=${o.timedOut} ${ms}ms")
        }
    }

    private fun rebuild(ctx: Context): Boolean {
        val src = YtdlpLocator.hostFile(ctx, zipappGuest)
        if (!src.isFile) return false
        val head = try {
            src.inputStream().use { i -> val b = ByteArray(256); b.copyOf(maxOf(i.read(b), 0)) } // bukan readNBytes: baru ada di API 33
        } catch (e: java.io.IOException) { return false }
        if (!YtdlpInstall.looksLikeZipapp(head)) return false // pip/apt/biner lain: tidak ada yang diekstrak
        val size = src.length()
        val mtime = src.lastModified()
        val dir = YtdlpLocator.hostFile(ctx, YtdlpFast.GUEST_DIR)
        invalidate(ctx)
        if (dir.exists() && !dir.deleteRecursively()) return false
        if (!dir.mkdirs()) return false
        val root = dir.canonicalPath + File.separator
        var files = 0
        var total = 0L
        try {
            ZipFile(src).use { zip ->
                for (e in zip.entries()) {
                    val name = YtdlpFast.safeName(e.name) ?: continue
                    val out = File(dir, name)
                    if (!out.canonicalPath.startsWith(root)) continue // pengaman zip-slip
                    if (e.isDirectory) { out.mkdirs(); continue }
                    if (++files > YtdlpFast.MAX_FILES) error("terlalu banyak berkas")
                    out.parentFile?.mkdirs()
                    zip.getInputStream(e).use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > YtdlpFast.MAX_TOTAL_BYTES) error("ekstraksi melebihi batas")
                                o.write(buf, 0, n)
                            }
                        }
                    }
                    out.setReadable(true, false)
                }
            }
            if (!File(dir, "__main__.py").isFile) error("__main__.py tidak ada")
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
        // Penanda terakhir: dibuat hanya setelah semuanya utuh; zipapp berubah selama ekstraksi = dibuang.
        if (src.length() != size || src.lastModified() != mtime) { dir.deleteRecursively(); return false }
        File(dir, YtdlpFast.MARKER).writeText(YtdlpFast.marker(size, mtime))
        return true
    }
}
