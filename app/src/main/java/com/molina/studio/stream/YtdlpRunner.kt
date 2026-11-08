package com.molina.studio.stream

import android.content.Context
import com.molina.core.common.AppLog
import com.molina.core.common.LogLevel
import com.molina.core.common.YtdlpCommand
import com.molina.studio.system.AndroidProcessOps
import com.molina.studio.terminal.MolinaDns
import com.molina.studio.terminal.RootfsManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Menjalankan yt-dlp di Debian PRoot. Aturan:
 * - satu proses pada satu waktu ([gate]); permintaan lain menunggu giliran,
 * - batas waktu dan batas ukuran keluaran,
 * - seluruh pohon proses (proot + python) dimatikan bila selesai tak wajar, batal, atau lewat waktu,
 * - URL tidak pernah masuk log penuh (hanya yang sudah disamarkan oleh pemanggil).
 */
internal object YtdlpRunner {
    class Output(
        val exit: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        val truncated: Boolean,
    )

    private const val TAG = "ytdlp"
    private const val MAX_STDOUT = 16 * 1024 * 1024
    private const val MAX_STDERR = 64 * 1024

    private val gate = Mutex()
    @Volatile private var activePid: Int = -1
    @Volatile private var running = false

    fun isRunning(): Boolean = running

    /** Matikan proses yt-dlp yang sedang berjalan (bila ada). Mengembalikan jumlah proses yang dimatikan. */
    fun cancelActive(): Int {
        val pid = activePid
        if (pid <= 1) return 0
        val r = AndroidProcessOps.reaper().reapTree(pid)
        return r.total
    }

    /**
     * Jalankan [args] dengan [guestBinary]. Pembatalan coroutine mematikan prosesnya.
     * Melempar CancellationException bila dibatalkan; galat I/O lain dikembalikan sebagai exit -1.
     */
    suspend fun run(
        ctx: Context,
        guestBinary: String,
        args: List<String>,
        timeoutMs: Long,
    ): Output = gate.withLock {
        runInterruptible(Dispatchers.IO) { runSync(ctx.applicationContext, guestBinary, args, timeoutMs) }
    }

    private fun runSync(ctx: Context, guestBinary: String, args: List<String>, timeoutMs: Long): Output {
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val tmp = File(ctx.cacheDir, "proot-tmp").apply { mkdirs() }
        val rootfsDir = RootfsManager.rootfsDir(ctx)
        val rootfs = rootfsDir.absolutePath
        // DNS guest ditulis saat sesi Terminal dibuat; di sini resolver bisa jalan tanpa sesi sama sekali.
        try { MolinaDns.writeResolvConf(ctx, rootfsDir) } catch (e: Exception) { }

        val cmd = YtdlpCommand.SH_WRAPPER + "$nativeDir/libproot.so" +
            YtdlpCommand.prootArgs(rootfs, guestBinary, args)
        val pb = ProcessBuilder(cmd).directory(ctx.filesDir)
        pb.environment().apply {
            clear()
            put("PROOT_LOADER", "$nativeDir/libproot_loader.so")
            put("PROOT_TMP_DIR", tmp.absolutePath)
            put("TMPDIR", tmp.absolutePath)
            put("PATH", "/system/bin")
            put("HOME", ctx.filesDir.absolutePath)
            System.getenv("ANDROID_ROOT")?.let { put("ANDROID_ROOT", it) }
            System.getenv("ANDROID_DATA")?.let { put("ANDROID_DATA", it) }
        }

        val started = System.nanoTime()
        val proc = try {
            pb.start()
        } catch (e: Exception) {
            AppLog.log("stream", LogLevel.Warn, TAG, "Gagal memulai proot: ${e.javaClass.simpleName}")
            return Output(-1, "", e.javaClass.simpleName, timedOut = false, truncated = false)
        }
        running = true
        try { proc.outputStream.close() } catch (e: Exception) { }

        val out = Capture(proc.inputStream, MAX_STDOUT, detectPid = true).also { it.start() }
        val err = Capture(proc.errorStream, MAX_STDERR, detectPid = false).also { it.start() }

        var timedOut = false
        var finished = false
        try {
            finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) timedOut = true
        } finally {
            // Selesai normal: proot sudah keluar. Selain itu (waktu habis, dibatalkan): bunuh pohonnya.
            if (!finished) killTree(proc, out.pidSeen())
            running = false
            activePid = -1
        }
        out.join(); err.join()

        val body = YtdlpCommand.splitPidLine(out.text()).second
        val ms = (System.nanoTime() - started) / 1_000_000
        val code = if (timedOut) -2 else try { proc.exitValue() } catch (e: IllegalThreadStateException) { -3 }
        AppLog.log("stream", LogLevel.Info, TAG, "yt-dlp selesai ${ms}ms exit=$code" +
            (if (timedOut) " (lewat waktu)" else "") + (if (out.truncated) " (keluaran dipotong)" else ""))
        return Output(code, body, err.text(), timedOut, out.truncated)
    }

    /** Matikan pohon proses; PID dari baris pertama stdout, atau proses induk bila belum terbaca. */
    private fun killTree(proc: Process, pidFromOutput: Int?) {
        val pid = pidFromOutput ?: activePid
        if (pid > 1) {
            try { AndroidProcessOps.reaper().reapTree(pid) } catch (e: Exception) { }
        }
        proc.destroyForcibly()
    }

    /** Pembaca aliran di thread daemon dengan batas ukuran; sisa aliran tetap dibuang agar proses tidak macet. */
    private class Capture(
        private val input: InputStream,
        private val max: Int,
        private val detectPid: Boolean,
    ) : Thread("molina-ytdlp-io") {
        private val buf = ByteArrayOutputStream()
        @Volatile var truncated = false
        @Volatile private var pid: Int? = null

        init { isDaemon = true }

        override fun run() {
            val chunk = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    synchronized(buf) {
                        val room = max - buf.size()
                        if (room > 0) buf.write(chunk, 0, minOf(n, room))
                        if (n > room) truncated = true
                        if (detectPid && pid == null && buf.size() > 0) {
                            val head = buf.toString(Charsets.UTF_8.name())
                            val nl = head.indexOf('\n')
                            if (nl > 0) {
                                head.substring(0, nl).trim().toIntOrNull()?.let {
                                    pid = it
                                    activePid = it
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) { }
        }

        fun pidSeen(): Int? = pid
        fun text(): String = synchronized(buf) { buf.toString(Charsets.UTF_8.name()) }
    }
}
