package com.molina.core.log

import android.content.Context
import com.molina.core.api.LogLevel
import com.molina.core.diag.LogRedact
import com.molina.core.diag.PlaybackSessions
import com.molina.core.system.ExitHooks
import com.molina.core.system.MolinaPaths
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [MPV-LOG-FILE] Penyimpan pesan mesin mpv (callback log libmpv) ke <appRoot>/Logs/Mpv/mpv.log.
 * - Rotasi 1 MB x 3 (mpv.log, mpv.1.log, mpv.2.log).
 * - append() dipanggil dari thread native mpv: hanya menyamarkan teks dan memasukkan ke antrean (tanpa I/O, tanpa blokir).
 *   Antrean penuh membuang baris TERLAMA. Penulisan di thread sendiri, flush tiap 500 ms dan langsung untuk level <= ERROR.
 * - Format baris = `adb logcat -v threadtime` (sama dengan logcat.log): `MM-dd HH:mm:ss.SSS PID TID P tag: [sNNN] pesan`,
 *   tag = mpv/<prefix mpv> (mis. mpv/ffmpeg/demuxer), supaya bisa difilter dan disejajarkan waktu dengan logcat.log.
 *   P: F fatal, E error, W warn, I info, D = mpv "v" (verbose), V = mpv debug/trace (urutan keparahan tetap menurun).
 *   TID = thread yang menyampaikan callback mpv, BUKAN thread asal pesan; asal subsistem ada di tag.
 *   Baris identik berturut-turut digabung menjadi "(xN)" (urutan waktu tetap benar).
 * - Batasan: crash native (SIGSEGV) hanya meninggalkan baris mpv terakhir sebelum proses mati, bukan backtrace.
 * URL dan token disamarkan (LogRedact) SEBELUM masuk antrean karena folder Molina terbaca aplikasi lain.
 */
object MpvLogFileWriter {
    const val LEVEL_ERROR = 20
    const val LEVEL_WARN = 30
    const val LEVEL_INFO = 40
    const val LEVEL_VERBOSE = 50

    private const val DIR = "Logs/Mpv"
    private const val BASE = "mpv"
    private const val PREFS = "molina_mpvlog"
    private const val MAX_BYTES = 1_000_000L
    private const val KEEP = 3
    private const val FLUSH_MS = 500L
    private const val RETRY_MS = 3_000L
    private const val MAX_PENDING = 5000

    private class Entry(val time: Long, val level: Int, val prefix: String, val sid: String?, val text: String, val tid: Int) {
        var count = 1
        fun sameAs(o: Entry) = level == o.level && prefix == o.prefix && sid == o.sid && text == o.text
    }

    @Volatile private var ctx: Context? = null
    @Volatile private var paths: MolinaPaths? = null
    @Volatile private var headerPending = true
    @Volatile private var warned = false

    /** false = tidak ada tulisan ke Logs/Mpv sama sekali. */
    @Volatile var enabled: Boolean = true
        private set

    /** Level tertinggi yang disimpan (angka mpv: 20 error, 30 warn, 40 info, 50 verbose). */
    @Volatile var threshold: Int = LEVEL_WARN
        private set

    private val lock = Any()
    private val flushLock = Any()
    private val pending = ArrayDeque<Entry>()
    private val scheduled = AtomicBoolean(false)
    private val io = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "molina-mpv-log").apply { isDaemon = true }
    }

    /** Aman dipanggil berulang. Panggil dari Application.onCreate (tidak menyentuh libmpv). */
    fun attach(context: Context, molinaPaths: MolinaPaths) {
        if (ctx != null) return
        val app = context.applicationContext
        val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        paths = molinaPaths
        enabled = sp.getBoolean("enabled", true)
        threshold = sp.getInt("level", LEVEL_WARN).coerceIn(LEVEL_ERROR, LEVEL_VERBOSE)
        ctx = app
        ExitHooks.register("mpv-log") { flush() }
    }

    fun enable(on: Boolean) {
        enabled = on
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean("enabled", on)?.apply()
        if (!on) synchronized(lock) { pending.clear() }
    }

    fun setLevel(level: Int) {
        val v = level.coerceIn(LEVEL_ERROR, LEVEL_VERBOSE)
        threshold = v
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putInt("level", v)?.apply()
    }

    fun levelName(level: Int): String = when {
        level <= LEVEL_ERROR -> "Error"
        level <= LEVEL_WARN -> "Warn"
        level <= LEVEL_INFO -> "Info"
        else -> "Verbose"
    }

    /** Error -> Warn -> Info -> Verbose -> Error. */
    fun nextLevel(level: Int): Int = when {
        level < LEVEL_WARN -> LEVEL_WARN
        level < LEVEL_INFO -> LEVEL_INFO
        level < LEVEL_VERBOSE -> LEVEL_VERBOSE
        else -> LEVEL_ERROR
    }

    /** Dari callback log mpv (thread native). Tidak melempar, tidak memblokir. */
    fun append(prefix: String, level: Int, text: String) {
        if (!enabled || level <= 0 || level > threshold) return
        val msg = text.trim().replace('\n', ' ')
        if (msg.isEmpty()) return
        val e = Entry(System.currentTimeMillis(), level, prefix, PlaybackSessions.current()?.label, LogRedact.text(msg), android.os.Process.myTid())
        synchronized(lock) {
            val last = pending.lastOrNull()
            if (last != null && last.sameAs(e)) last.count++
            else {
                pending.addLast(e)
                while (pending.size > MAX_PENDING) pending.removeFirst()
            }
        }
        if (level <= LEVEL_ERROR) io.execute { flush() } else schedule(FLUSH_MS)
    }

    /** [MPV-LOG-SHARE] mpv.log terkini setelah antrean ditulis (tanpa melihat toggle ZIP); null bila mati/kosong. Panggil dari thread IO. */
    fun snapshotFile(): File? {
        if (!enabled) return null
        val p = paths ?: return null
        flush()
        return File(File(p.appRoot, DIR), "$BASE.log").takeIf { it.isFile && it.length() > 0L }
    }

    /** mpv.log terkini untuk ZIP diagnostik; kosong bila dimatikan atau ZIP logcat dimatikan. Panggil dari thread IO. */
    fun diagnosticFiles(): List<File> {
        if (!enabled || !LogFileWriter.zipEnabled) return emptyList()
        val p = paths ?: return emptyList()
        flush()
        return listOfNotNull(File(File(p.appRoot, DIR), "$BASE.log").takeIf { it.isFile && it.length() > 0L })
    }

    private fun schedule(delayMs: Long) {
        if (scheduled.compareAndSet(false, true)) {
            io.schedule(Runnable { scheduled.set(false); flush() }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun flush() { synchronized(flushLock) {
        if (!enabled) { synchronized(lock) { pending.clear() }; return@synchronized }
        val p = paths ?: return@synchronized
        val batch = synchronized(lock) { val b = ArrayList(pending); pending.clear(); b }
        if (batch.isEmpty()) return@synchronized
        try {
            val dir = File(p.appRoot, DIR)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("folder tidak bisa dibuat: ${dir.path}")
            val f = File(dir, "$BASE.log")
            rotateIfNeeded(dir, f)
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) // sama dengan logcat -v threadtime (tanpa tahun)
            val pid = android.os.Process.myPid()
            val sb = StringBuilder()
            if (headerPending) {
                sb.append("# ===== mpv.log sesi proses baru ").append(fmt.format(Date()))
                    .append(" | pid ").append(pid)
                    .append(" | tingkat ").append(levelName(threshold)).append(" =====\n")
                sb.append("# Format logcat -v threadtime: MM-dd HH:mm:ss.SSS PID TID P tag: [sNNN] pesan. Tag mpv/<prefix mpv>. ")
                    .append("P: F fatal, E error, W warn, I info, D mpv-verbose, V mpv-debug/trace. ")
                    .append("TID = thread penyampai callback mpv. (xN) = baris identik berturut-turut digabung.\n")
            }
            for (e in batch) {
                sb.append(fmt.format(Date(e.time))).append(' ')
                    .append(pid.toString().padStart(5)).append(' ').append(e.tid.toString().padStart(5)).append(' ')
                    .append(levelChar(e.level)).append(" mpv/").append(e.prefix).append(": ")
                if (e.sid != null) sb.append('[').append(e.sid).append("] ")
                sb.append(e.text)
                if (e.count > 1) sb.append(" (\u00d7").append(e.count).append(')')
                sb.append('\n')
            }
            f.appendText(sb.toString(), Charsets.UTF_8)
            headerPending = false
            warned = false
        } catch (e: Exception) {
            synchronized(lock) {
                batch.asReversed().forEach { pending.addFirst(it) }
                while (pending.size > MAX_PENDING) pending.removeFirst()
            }
            if (!warned) {
                warned = true
                LogBus.log("mpv", LogLevel.Warn, "file", "Berkas log mpv belum bisa ditulis (${e.message}); dicoba ulang")
            }
            schedule(RETRY_MS)
        }
    } }

    private fun levelChar(level: Int): Char = when {
        level <= 10 -> 'F'
        level <= 20 -> 'E'
        level <= 30 -> 'W'
        level <= 40 -> 'I'
        level <= 50 -> 'D' // mpv "v"
        else -> 'V' // mpv debug/trace
    }

    private fun rotateIfNeeded(dir: File, f: File) {
        if (f.length() < MAX_BYTES) return
        File(dir, "$BASE.${KEEP - 1}.log").delete()
        for (i in KEEP - 2 downTo 1) File(dir, "$BASE.$i.log").renameTo(File(dir, "$BASE.${i + 1}.log"))
        f.renameTo(File(dir, "$BASE.1.log"))
    }
}
