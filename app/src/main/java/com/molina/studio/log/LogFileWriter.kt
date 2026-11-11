package com.molina.studio.log

import android.content.Context
import com.molina.core.common.ExitHooks
import com.molina.core.common.LogLevel
import com.molina.core.common.LogRedact
import com.molina.studio.AppPaths
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [LOGCAT-FILE] Penyimpan logcat bergulir ke <appRoot>/Logs/Logcat, format persis `logcat -v threadtime`.
 * - logcat.log (+ logcat.1..3.log): rotasi 1 MB x 4, seperti `logcat -r 1024 -n 4`.
 * - Penulisan di thread sendiri; flush tiap 500 ms dan langsung untuk prioritas E/F (log tidak hilang saat crash).
 * - Post-mortem: bila sesi sebelumnya tidak keluar lewat AppExit (crash Java/native, mis. SIGSEGV libmpv),
 *   ekor logcat milik UID aplikasi (maks 1500 baris) disimpan ke postmortem-<waktu>.log (5 terbaru).
 * - Izin storage belum ada / folder belum bisa ditulis: baris ditahan (maks 5000) dan dicoba ulang.
 * URL dan token disamarkan (LogRedact) karena folder ini terbaca aplikasi lain.
 */
object LogFileWriter {
    private const val DIR = "Logs/Logcat"
    private const val BASE = "logcat"
    private const val MARKER = "logcat.session"
    private const val MAX_BYTES = 1_000_000L
    private const val KEEP = 4
    private const val FLUSH_MS = 500L
    private const val RETRY_MS = 3_000L
    private const val MAX_PENDING = 5000
    private const val POSTMORTEM_LINES = 1500
    private const val POSTMORTEM_KEEP = 5

    @Volatile private var ctx: Context? = null
    @Volatile private var prev: String? = null
    private val lock = Any()
    private val flushLock = Any()
    private val pending = ArrayDeque<String>()
    private val scheduled = AtomicBoolean(false)
    private var warned = false
    private val io = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "molina-log-file").apply { isDaemon = true }
    }

    /** Aman dipanggil berulang; panggil sekali saat aplikasi mulai, sebelum LogcatReporter dinyalakan. */
    fun attach(context: Context) {
        if (ctx != null) return
        val app = context.applicationContext
        ctx = app
        ExitHooks.register("logcat-file") { cleanExit() }
        io.execute { startSession(app) }
    }

    /** Baris logcat mentah (sudah format threadtime). Aman dari thread mana pun. */
    fun append(line: String, urgent: Boolean) {
        if (line.isEmpty()) return
        val safe = LogRedact.text(line)
        synchronized(lock) {
            pending.addLast(safe)
            while (pending.size > MAX_PENDING) pending.removeFirst()
        }
        if (urgent) io.execute { flush() } else schedule(FLUSH_MS)
    }

    private fun schedule(delayMs: Long) {
        if (scheduled.compareAndSet(false, true)) {
            io.schedule(Runnable { scheduled.set(false); flush() }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun cleanExit() {
        val c = ctx ?: return
        flush()
        runCatching { File(c.filesDir, MARKER).delete() }
    }

    private fun startSession(c: Context) {
        val marker = File(c.filesDir, MARKER)
        val unclean = marker.exists()
        runCatching { marker.writeText(android.os.Process.myPid().toString()) }
        if (unclean) prev = dumpPrevious()
        flush()
    }

    private fun dumpPrevious(): String? = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", POSTMORTEM_LINES.toString())
            .redirectErrorStream(true).start()
        try {
            val text = p.inputStream.bufferedReader().readText()
            if (text.isBlank()) null
            else "# Molina logcat post-mortem: sesi sebelumnya berakhir tanpa keluar normal (crash/dibunuh sistem).\n" +
                "# Ekor logcat milik UID aplikasi, maks $POSTMORTEM_LINES baris; baris terakhir paling dekat dengan crash.\n" +
                LogRedact.text(text)
        } finally { try { p.destroy() } catch (e: Exception) { } }
    } catch (e: Exception) {
        null
    }

    private fun flush() { synchronized(flushLock) {
        val c = ctx ?: return@synchronized
        val pm = prev
        val batch = synchronized(lock) { val b = ArrayList(pending); pending.clear(); b }
        if (pm == null && batch.isEmpty()) return@synchronized
        try {
            val dir = File(AppPaths.get(c).appRoot, DIR)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("folder tidak bisa dibuat: ${dir.path}")
            if (pm != null) { writePostMortem(dir, pm); prev = null }
            if (batch.isNotEmpty()) {
                val f = File(dir, "$BASE.log")
                rotateIfNeeded(dir, f)
                f.appendText(batch.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            }
            warned = false
        } catch (e: Exception) {
            synchronized(lock) {
                batch.asReversed().forEach { pending.addFirst(it) }
                while (pending.size > MAX_PENDING) pending.removeFirst()
            }
            if (!warned) {
                warned = true
                LogBus.log("logcat", LogLevel.Warn, "file", "Berkas log belum bisa ditulis (${e.message}); dicoba ulang")
            }
            schedule(RETRY_MS)
        }
    } }

    private fun rotateIfNeeded(dir: File, f: File) {
        if (f.length() < MAX_BYTES) return
        File(dir, "$BASE.${KEEP - 1}.log").delete()
        for (i in KEEP - 2 downTo 1) File(dir, "$BASE.$i.log").renameTo(File(dir, "$BASE.${i + 1}.log"))
        f.renameTo(File(dir, "$BASE.1.log"))
    }

    private fun writePostMortem(dir: File, text: String) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        File(dir, "postmortem-$stamp.log").writeText(text, Charsets.UTF_8)
        dir.listFiles { f -> f.name.startsWith("postmortem-") }
            ?.sortedByDescending { it.name }?.drop(POSTMORTEM_KEEP)?.forEach { it.delete() }
    }
}
