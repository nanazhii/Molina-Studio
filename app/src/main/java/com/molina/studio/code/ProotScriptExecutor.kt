package com.molina.studio.code

import android.content.Context
import android.os.Environment
import com.molina.code.lsp.LeadingPidFilter
import com.molina.code.lsp.StreamExecutor
import com.molina.code.lsp.StreamHandle
import com.molina.code.lsp.StreamListener
import com.molina.code.lsp.StreamRequest
import com.molina.code.lsp.StreamResult
import com.molina.code.preview.GuestPaths
import com.molina.code.run.RunHandle
import com.molina.code.run.RunListener
import com.molina.code.run.RunRequest
import com.molina.code.run.RunResult
import com.molina.code.run.ScriptExecutor
import com.molina.core.common.AppLog
import com.molina.core.common.LogLevel
import com.molina.core.common.YtdlpCommand
import com.molina.studio.system.AndroidProcessOps
import com.molina.studio.terminal.MolinaDns
import com.molina.studio.terminal.RootfsManager
import com.molina.studio.terminal.StorageBinds
import com.molina.studio.terminal.TerminalPrefs
import com.molina.studio.terminal.TerminalSessions
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Menjalankan skrip dari editor Code di Debian PRoot milik Molina Terminal. Aturan:
 * - satu skrip pada satu waktu per jalur (Lane): Code dan Settings memakai CODE, halaman Unduhan memakai DOWNLOAD,
 *   sehingga unduhan panjang tidak memblokir editor dan Penyegaran sistem tidak membatalkannya,
 * - isi editor ditulis ke berkas sementara di rootfs (/tmp/molina-run) dan dihapus sesudahnya,
 * - batas waktu dan batas keluaran; seluruh pohon proses dimatikan bila dibatalkan atau lewat waktu,
 * - isi skrip tidak pernah masuk log (hanya kode keluar dan lamanya).
 */
internal class ProotScriptExecutor(context: Context, private val lane: Lane = Lane.CODE) : ScriptExecutor, StreamExecutor {
    enum class Lane { CODE, DOWNLOAD, AUTO, FORMAT, CHECK, LSP }

    private val app = context.applicationContext

    override fun start(request: RunRequest, listener: RunListener): RunHandle {
        val handle = Handle()
        val busy = busyFlags.getValue(lane)
        if (!busy.compareAndSet(false, true)) {
            listener.onFinished(failed("Masih ada skrip yang berjalan."))
            return handle
        }
        val worker = Thread({
            try {
                listener.onFinished(runSync(request, listener, handle))
            } catch (e: Exception) {
                listener.onFinished(failed("Gagal menjalankan: ${e.javaClass.simpleName}"))
            } finally {
                active.remove(lane)
                busy.set(false)
            }
        }, "molina-script")
        worker.isDaemon = true
        handle.thread = worker
        active[lane] = handle
        worker.start()
        return handle
    }

    private class Handle : RunHandle {
        @Volatile var thread: Thread? = null
        val cancelled = AtomicBoolean(false)
        override fun cancel() {
            if (cancelled.compareAndSet(false, true)) thread?.interrupt()
        }
    }

    private fun failed(msg: String) = RunResult(-1, false, false, false, 0L, msg)

    /** [CODE-FORMAT] True bila [cmd] ada di rootfs. Symlink dihitung ada (banyak yang absolut di dalam rootfs). */
    private fun guestHasCommand(rootfsDir: File, cmd: String): Boolean {
        if (cmd.startsWith("/")) return File(rootfsDir, cmd.removePrefix("/")).let { it.exists() || java.nio.file.Files.isSymbolicLink(it.toPath()) }
        return GUEST_BIN_DIRS.any { dir ->
            File(rootfsDir, "$dir/$cmd").let { it.exists() || java.nio.file.Files.isSymbolicLink(it.toPath()) }
        }
    }

    private fun runSync(req: RunRequest, out: RunListener, handle: Handle): RunResult {
        ScriptCommand.validate(req.args + (req.guestArgv ?: emptyList()), req.env)?.let { return failed(it) }
        val rootfsDir = RootfsManager.rootfsDir(app)
        if (!File(rootfsDir, "usr/bin/env").exists()) {
            return failed("Debian belum terpasang. Buka Settings > Terminal untuk menyiapkannya.")
        }
        // [CODE-FORMAT] Mode argv (formatter): tanpa berkas sementara; argv[0] harus ada di Debian.
        req.guestArgv?.let { argv ->
            if (argv.isEmpty()) return failed("Perintah kosong.")
            if (!guestHasCommand(rootfsDir, argv[0])) return failed(req.missingHint ?: "${argv[0]} belum ada di Debian.")
            return exec(req, out, handle, rootfsDir, null)
        }
        if (req.plan.needsPython && !File(rootfsDir, "usr/bin/python3").exists()) {
            return failed("python3 belum ada di Debian. ${YtdlpCommand.INSTALL_PYTHON_HINT}")
        }

        // Berkas sementara di dalam rootfs, jadi terlihat di guest tanpa bind tambahan.
        val stageDir = File(rootfsDir, "tmp/molina-run").apply { mkdirs() }
        val staged = File(stageDir, "run-${System.nanoTime()}.${req.plan.extension}")
        try {
            staged.writeText(req.scriptText.replace("\r\n", "\n"), Charsets.UTF_8)
        } catch (e: Exception) {
            return failed("Gagal menulis berkas sementara: ${e.javaClass.simpleName}")
        }
        try {
            return exec(req, out, handle, rootfsDir, "${ScriptCommand.GUEST_TMP_DIR}/${staged.name}")
        } finally {
            staged.delete()
        }
    }

    /**
     * [CODE-LSP2] Satu-satunya tempat menyusun proses proot (dipakai exec() dan runStream()): bind storage sesuai
     * lingkup Terminal, cwd dari [hostDir] (null/tak terlihat = /root), dan lingkungan Android yang bersih.
     * [buildArgs] menerima (cwd guest, bind) dan mengembalikan argv libproot.so tanpa argv[0].
     */
    private fun newProotProcess(
        rootfsDir: File,
        hostDir: String?,
        env: Map<String, String>,
        buildArgs: (String, List<String>) -> List<String>,
    ): ProcessBuilder {
        val nativeDir = app.applicationInfo.nativeLibraryDir
        val tmp = File(app.cacheDir, "proot-tmp").apply { mkdirs() }
        try { MolinaDns.writeResolvConf(app, rootfsDir) } catch (e: Exception) { }

        // Lingkup storage sama dengan Terminal: default hanya folder kerja Molina yang di-bind.
        val storage = Environment.getExternalStorageDirectory().absolutePath
        val workspace = TerminalSessions.WORKSPACE_DIR
        val full = TerminalPrefs.fullStorageAccess
        val binds: List<String> = if (full) {
            File(rootfsDir, "storage/emulated/0").mkdirs()
            listOf("--bind=$storage:/sdcard", "--bind=$storage:/storage/emulated/0")
        } else {
            val ws = File(storage, workspace).apply { mkdirs() }
            File(rootfsDir, "sdcard/$workspace").mkdirs()
            File(rootfsDir, "storage/emulated/0/$workspace").mkdirs()
            listOf(
                "--bind=${ws.absolutePath}:/sdcard/$workspace",
                "--bind=${ws.absolutePath}:/storage/emulated/0/$workspace",
            ) + StorageBinds.app(app, rootfsDir)
        }
        val cwd = hostDir?.let { GuestPaths.of(it, storage, workspace, full, listOf(com.molina.core.common.MolinaPaths.APP_FOLDER)) } ?: "/root"

        val cmd = YtdlpCommand.SH_WRAPPER + "$nativeDir/libproot.so" + buildArgs(cwd, binds)
        val pb = ProcessBuilder(cmd).directory(app.filesDir)
        pb.environment().apply {
            clear()
            put("PROOT_LOADER", "$nativeDir/libproot_loader.so")
            put("PROOT_TMP_DIR", tmp.absolutePath)
            put("TMPDIR", tmp.absolutePath)
            put("PATH", "/system/bin")
            put("HOME", app.filesDir.absolutePath)
            System.getenv("ANDROID_ROOT")?.let { put("ANDROID_ROOT", it) }
            System.getenv("ANDROID_DATA")?.let { put("ANDROID_DATA", it) }
        }
        return pb
    }

    private fun exec(
        req: RunRequest,
        out: RunListener,
        handle: Handle,
        rootfsDir: File,
        guestScript: String?,
    ): RunResult {
        val guestArgv = req.guestArgv
        val pb = newProotProcess(rootfsDir, req.hostDir, req.env) { cwd, binds ->
            if (guestArgv != null) {
                ScriptCommand.prootArgv(rootfsDir.absolutePath, cwd, binds, guestArgv, req.env) // [CODE-FORMAT]
            } else {
                ScriptCommand.prootArgs(rootfsDir.absolutePath, cwd, binds, req.plan.interpreter, guestScript!!, req.args, req.env)
            }
        }

        val started = System.nanoTime()
        val proc = try {
            pb.start()
        } catch (e: Exception) {
            return failed("Gagal memulai proot: ${e.javaClass.simpleName}")
        }
        val stdin = req.stdinText
        if (stdin == null) {
            try { proc.outputStream.close() } catch (e: Exception) { }
        } else {
            // [CODE-FORMAT] Tulis di thread sendiri: stdout/stderr dikuras paralel sehingga pipa tidak buntu.
            val writer = Thread({
                try {
                    proc.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)) }
                } catch (e: Exception) {
                    // proses mati lebih dulu (mis. galat sintaks); hasilnya dibaca dari keluaran
                }
            }, "molina-script-in")
            writer.isDaemon = true
            writer.start()
        }

        val pid = AtomicInteger(-1)
        val sent = AtomicLong(0L)
        val truncated = AtomicBoolean(false)
        val outThread = pump(proc.inputStream, false, true, out, pid, sent, truncated, req.maxOutputChars)
        val errThread = pump(proc.errorStream, true, false, out, pid, sent, truncated, req.maxOutputChars)

        var finished = false
        var timedOut = false
        // cancel() yang datang sebelum thread ini sempat menunggu tidak bisa menginterupsi: cek dulu.
        var interrupted = handle.cancelled.get()
        try {
            if (!interrupted) {
                finished = proc.waitFor(req.timeoutMs, TimeUnit.MILLISECONDS)
                timedOut = !finished
            }
        } catch (e: InterruptedException) {
            interrupted = true
        } finally {
            if (!finished) {
                // Dibatalkan atau lewat waktu: matikan seluruh pohon (proot + interpreter + anak-anaknya).
                val root = pid.get()
                if (root > 1) {
                    try { AndroidProcessOps.reaper().reapTree(root) } catch (e: Exception) { }
                }
                proc.destroyForcibly()
            }
        }
        // Setelah proses mati, aliran ditutup dan pembaca selesai sendiri; batasi menunggu seperlunya.
        try {
            outThread.join(1500)
            errThread.join(1500)
        } catch (e: InterruptedException) {
            // cancel() terlambat datang; abaikan, proses sudah selesai
        }
        Thread.interrupted() // bersihkan flag interupsi yang mungkin tersisa

        val ms = (System.nanoTime() - started) / 1_000_000
        val code = when {
            !finished -> -1
            else -> try { proc.exitValue() } catch (e: IllegalThreadStateException) { -1 }
        }
        AppLog.log("code", LogLevel.Info, "script", "selesai ${ms}ms exit=$code" +
            (if (timedOut) " (lewat waktu)" else "") + (if (handle.cancelled.get()) " (dibatalkan)" else ""))
        return RunResult(code, timedOut, handle.cancelled.get() || interrupted, truncated.get(), ms)
    }

    // ------------------------------------------------------------------ [CODE-LSP2] stream dua arah

    /** Proses menetap dengan stdin/stdout dua arah (language server). Satu per Lane; tanpa batas waktu. */
    private class StreamProc : StreamHandle {
        @Volatile var thread: Thread? = null
        @Volatile var process: Process? = null
        val pid = AtomicInteger(-1)
        val cancelled = AtomicBoolean(false)
        private val queue = LinkedBlockingQueue<ByteArray>()
        private val queued = AtomicLong(0L)
        private val inputClosed = AtomicBoolean(false)

        override fun write(data: ByteArray): Boolean {
            if (inputClosed.get() || cancelled.get()) return false
            if (queued.addAndGet(data.size.toLong()) > MAX_QUEUED_BYTES) {
                queued.addAndGet(-data.size.toLong())
                return false // proses tidak membaca stdin: jangan menumpuk memori
            }
            queue.put(data)
            return true
        }

        override fun closeInput() {
            if (inputClosed.compareAndSet(false, true)) queue.put(EOF)
        }

        override fun cancel() {
            if (cancelled.compareAndSet(false, true)) {
                inputClosed.set(true)
                queue.clear(); queue.put(EOF)
                thread?.interrupt()
            }
        }

        /** Dikuras thread penulis; null = EOF. */
        fun take(): ByteArray? {
            val b = queue.take()
            if (b === EOF) return null
            queued.addAndGet(-b.size.toLong())
            return b
        }

        companion object {
            val EOF = ByteArray(0)
            const val MAX_QUEUED_BYTES = 16L shl 20
        }
    }

    override fun startStream(request: StreamRequest, listener: StreamListener): StreamHandle {
        val handle = StreamProc()
        val busy = busyFlags.getValue(lane)
        if (!busy.compareAndSet(false, true)) {
            listener.onExit(StreamResult(-1, false, 0L, "Masih ada proses yang berjalan."))
            return handle
        }
        val worker = Thread({
            try {
                listener.onExit(runStream(request, listener, handle))
            } catch (e: Exception) {
                listener.onExit(StreamResult(-1, handle.cancelled.get(), 0L, "Gagal menjalankan: ${e.javaClass.simpleName}"))
            } finally {
                activeStreams.remove(lane)
                busy.set(false)
            }
        }, "molina-stream")
        worker.isDaemon = true
        handle.thread = worker
        activeStreams[lane] = handle
        worker.start()
        return handle
    }

    private fun runStream(req: StreamRequest, out: StreamListener, handle: StreamProc): StreamResult {
        val argv = req.guestArgv
        if (argv.isEmpty()) return StreamResult(-1, false, 0L, "Perintah kosong.")
        ScriptCommand.validate(argv, req.env)?.let { return StreamResult(-1, false, 0L, it) }
        val rootfsDir = RootfsManager.rootfsDir(app)
        if (!File(rootfsDir, "usr/bin/env").exists()) {
            return StreamResult(-1, false, 0L, "Debian belum terpasang. Buka Settings > Terminal untuk menyiapkannya.")
        }
        if (!guestHasCommand(rootfsDir, argv[0])) {
            return StreamResult(-1, false, 0L, req.missingHint ?: "${argv[0]} belum ada di Debian.")
        }
        if (handle.cancelled.get()) return StreamResult(-1, true, 0L)
        val pb = newProotProcess(rootfsDir, req.hostDir, req.env) { cwd, binds ->
            ScriptCommand.prootArgv(rootfsDir.absolutePath, cwd, binds, argv, req.env)
        }
        val started = System.nanoTime()
        val proc = try {
            pb.start()
        } catch (e: Exception) {
            return StreamResult(-1, false, 0L, "Gagal memulai proot: ${e.javaClass.simpleName}")
        }
        handle.process = proc

        // Penulis: satu thread menguras antrean ke stdin; write() pemanggil tidak pernah memblokir.
        val writer = Thread({
            try {
                val os = proc.outputStream
                while (true) {
                    val chunk = handle.take() ?: break
                    os.write(chunk)
                    os.flush()
                }
            } catch (e: Exception) {
                // proses mati lebih dulu; pemanggil tahu dari onExit
            } finally {
                try { proc.outputStream.close() } catch (e: Exception) { }
            }
        }, "molina-stream-in")
        writer.isDaemon = true
        writer.start()

        // stdout: byte mentah (framing LSP memakai Content-Length dalam byte), baris PID pembungkus dibuang.
        val outThread = Thread({
            try {
                val filter = LeadingPidFilter()
                val buf = ByteArray(8192)
                val input = proc.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    filter.feed(buf, n, { handle.pid.set(it) }, { d, len -> out.onStdout(d, len) })
                }
            } catch (e: Exception) {
                // aliran ditutup saat proses dimatikan
            }
        }, "molina-stream-out")
        outThread.isDaemon = true
        outThread.start()

        val errThread = Thread({
            try {
                val reader = InputStreamReader(proc.errorStream, Charsets.UTF_8)
                val buf = CharArray(2048)
                var sent = 0
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    val room = req.maxStderrChars - sent
                    if (room <= 0) continue // tetap dikuras supaya proses tidak macet
                    val take = minOf(n, room)
                    sent += take
                    out.onStderr(String(buf, 0, take))
                }
            } catch (e: Exception) {
                // aliran ditutup saat proses dimatikan
            }
        }, "molina-stream-err")
        errThread.isDaemon = true
        errThread.start()

        var finished = false
        var interrupted = handle.cancelled.get()
        try {
            if (!interrupted) {
                proc.waitFor()
                finished = true
            }
        } catch (e: InterruptedException) {
            interrupted = true
        } finally {
            if (!finished) {
                val root = handle.pid.get()
                if (root > 1) {
                    try { AndroidProcessOps.reaper().reapTree(root) } catch (e: Exception) { }
                }
                proc.destroyForcibly()
            }
        }
        handle.closeInput() // hentikan penulis tanpa menandai dibatalkan
        try {
            outThread.join(1500)
            errThread.join(1500)
            writer.join(500)
        } catch (e: InterruptedException) {
            // abaikan; proses sudah selesai
        }
        Thread.interrupted() // bersihkan flag interupsi yang mungkin tersisa

        val ms = (System.nanoTime() - started) / 1_000_000
        val code = if (!finished) -1 else {
            try { proc.exitValue() } catch (e: IllegalThreadStateException) { -1 }
        }
        // Hanya nama, kode keluar, dan lama: isi dokumen dan stderr server tidak pernah masuk log.
        AppLog.log("code", LogLevel.Info, "stream", "${req.name} selesai ${ms}ms exit=$code" +
            (if (interrupted || handle.cancelled.get()) " (dihentikan)" else ""))
        return StreamResult(code, interrupted || handle.cancelled.get(), ms)
    }

    /**
     * Baca [input] sebagai UTF-8 dan teruskan ke [out]. Pada stdout, baris pertama adalah PID dari
     * pembungkus sh (lihat YtdlpCommand.SH_WRAPPER): dicatat, tidak ditampilkan. Kelebihan keluaran
     * (> [limit]) dibuang tetapi aliran tetap dikuras agar proses tidak macet.
     */
    private fun pump(
        input: InputStream,
        isError: Boolean,
        readPid: Boolean,
        out: RunListener,
        pid: AtomicInteger,
        sent: AtomicLong,
        truncated: AtomicBoolean,
        limit: Long,
    ): Thread {
        val t = Thread({
            try {
                val reader = InputStreamReader(input, Charsets.UTF_8)
                val buf = CharArray(4096)
                val head = StringBuilder()
                var pidDone = !readPid
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    var text = String(buf, 0, n)
                    if (!pidDone) {
                        head.append(text)
                        val nl = head.indexOf("\n")
                        if (nl < 0) continue
                        pidDone = true
                        val first = head.substring(0, nl).trim()
                        val rest = head.substring(nl + 1)
                        val p = first.toIntOrNull()
                        if (p != null) {
                            pid.set(p)
                            text = rest
                        } else {
                            text = head.toString() // bukan PID: tampilkan apa adanya
                        }
                        head.setLength(0)
                        if (text.isEmpty()) continue
                    }
                    val room = limit - sent.get()
                    if (room <= 0) {
                        truncated.set(true)
                        continue
                    }
                    if (text.length > room) {
                        text = text.substring(0, room.toInt())
                        truncated.set(true)
                    }
                    sent.addAndGet(text.length.toLong())
                    out.onOutput(text, isError)
                }
            } catch (e: Exception) {
                // aliran ditutup saat proses dimatikan
            }
        }, if (isError) "molina-script-err" else "molina-script-out")
        t.isDaemon = true
        t.start()
        return t
    }

    companion object {
        private val GUEST_BIN_DIRS = listOf("usr/local/sbin", "usr/local/bin", "usr/sbin", "usr/bin", "sbin", "bin")
        private val busyFlags: Map<Lane, AtomicBoolean> = Lane.values().associateWith { AtomicBoolean(false) }
        private val active = ConcurrentHashMap<Lane, Handle>()
        private val activeStreams = ConcurrentHashMap<Lane, StreamProc>() // [CODE-LSP2]

        /** True bila ada skrip Code yang sedang berjalan (untuk Penyegaran sistem). Jalur unduhan tidak dihitung. */
        fun isRunning(): Boolean = isRunning(Lane.CODE)

        fun isRunning(lane: Lane): Boolean = busyFlags.getValue(lane).get()

        /** Batalkan skrip Code yang berjalan; mengembalikan 1 bila ada yang dibatalkan. */
        fun cancelActive(): Int = cancelActive(Lane.CODE)

        fun cancelActive(lane: Lane): Int {
            val s = activeStreams[lane] // [CODE-LSP2]
            if (s != null) { s.cancel(); return 1 }
            val h = active[lane] ?: return 0
            h.cancel()
            return 1
        }
    }
}
