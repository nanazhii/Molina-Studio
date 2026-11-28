package com.molina.core.log

import android.content.Context
import com.molina.core.system.ExitHooks
import com.molina.core.api.LogLevel
import com.molina.core.diag.LogRedact
import com.molina.core.system.MolinaPaths
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    @Volatile private var paths: MolinaPaths? = null
    @Volatile private var prev: String? = null
    /** [LOGCAT-FILE-TOGGLE] false = tidak ada tulisan ke Logs/Logcat sama sekali (log di halaman Log tetap jalan). */
    private val enabledState = MutableStateFlow(true)
    /** [LOGCAT-FILE-BADGE] Dipantau halaman Log untuk lencana "Berkas: mati". */
    val enabledFlow: StateFlow<Boolean> get() = enabledState
    @Volatile var enabled: Boolean = true
        private set(value) { field = value; enabledState.value = value }
    /** [LOGCAT-ZIP-TOGGLE] false = logcat tidak ikut ZIP diagnostik (berkas lokal tetap ditulis bila toggle berkas menyala). */
    @Volatile var zipEnabled: Boolean = true
        private set
    private val lock = Any()
    private val flushLock = Any()
    private val pending = ArrayDeque<String>()
    private val scheduled = AtomicBoolean(false)
    private var warned = false
    private val io = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "molina-log-file").apply { isDaemon = true }
    }

    /** Aman dipanggil berulang; panggil sekali saat aplikasi mulai, sebelum LogcatReporter dinyalakan. */
    fun attach(context: Context, molinaPaths: MolinaPaths) {
        if (ctx != null) return
        paths = molinaPaths
        val app = context.applicationContext
        ctx = app
        enabled = app.getSharedPreferences("molina_logcat", Context.MODE_PRIVATE).getBoolean("file_enabled", true) // [LOGCAT-FILE-TOGGLE]
        zipEnabled = app.getSharedPreferences("molina_logcat", Context.MODE_PRIVATE).getBoolean("zip_enabled", true) // [LOGCAT-ZIP-TOGGLE]
        ExitHooks.register("logcat-file") { cleanExit() }
        io.execute { startSession(app) }
    }

    /** [LOGCAT-FILE-TOGGLE] Nyalakan/matikan penulisan berkas; pilihan disimpan antar sesi. Mati = antrean dikosongkan. */
    fun enable(on: Boolean) {
        enabled = on
        ctx?.getSharedPreferences("molina_logcat", Context.MODE_PRIVATE)?.edit()?.putBoolean("file_enabled", on)?.apply()
        if (!on) synchronized(lock) { pending.clear() }
    }

    /** [LOGCAT-ZIP-TOGGLE] Sertakan/tidak logcat di ZIP diagnostik; pilihan disimpan antar sesi. */
    fun enableZip(on: Boolean) {
        zipEnabled = on
        ctx?.getSharedPreferences("molina_logcat", Context.MODE_PRIVATE)?.edit()?.putBoolean("zip_enabled", on)?.apply()
    }

    /** Baris logcat mentah (sudah format threadtime). Aman dari thread mana pun. */
    fun append(line: String, urgent: Boolean) {
        if (line.isEmpty() || !enabled) return // [LOGCAT-FILE-TOGGLE]
        val safe = LogRedact.text(line)
        synchronized(lock) {
            pending.addLast(safe)
            while (pending.size > MAX_PENDING) pending.removeFirst()
        }
        if (urgent) io.execute { flush() } else schedule(FLUSH_MS)
    }

    /**
     * [LOGCAT-FILE-SHARE] logcat.log terkini setelah antrean ditulis; null bila belum ada atau kosong.
     * Panggil dari thread IO (menulis berkas).
     */
    fun snapshotFile(): File? {
        val p = paths ?: return null
        flush()
        return File(File(p.appRoot, DIR), "$BASE.log").takeIf { it.isFile && it.length() > 0L }
    }

    /**
     * [LOGCAT-DIAG-ZIP] Berkas log untuk ZIP diagnostik: logcat.log terkini (setelah antrean ditulis) dan satu
     * postmortem terbaru. Kosong bila penyimpanan berkas dimatikan. Panggil dari thread IO.
     */
    fun diagnosticFiles(): List<File> {
        if (!enabled || !zipEnabled) return emptyList() // [LOGCAT-ZIP-TOGGLE]
        val p = paths ?: return emptyList()
        val main = snapshotFile()
        val pm = File(p.appRoot, DIR).listFiles { f -> f.isFile && f.name.startsWith("postmortem-") && f.name.endsWith(".log") }
            ?.maxByOrNull { it.name }
        return listOfNotNull(main, pm)
    }

    private fun isLogFile(name: String): Boolean = // [LOGCAT-FILE-SIZE]
        name.endsWith(".log") && (name.startsWith("$BASE.") || name.startsWith("postmortem-"))

    /** [LOGCAT-FILE-SIZE] Total byte logcat*.log + postmortem-*.log; 0 bila folder belum ada. Panggil dari thread IO. */
    fun diskUsage(): Long {
        val p = paths ?: return 0L
        val dir = File(p.appRoot, DIR)
        return dir.listFiles { f -> isLogFile(f.name) }?.sumOf { it.length() } ?: 0L
    }

    /** [LOGCAT-FILE-SIZE] Hapus semua berkas log (dan antrean tertahan); mengembalikan jumlah berkas terhapus. Panggil dari thread IO. */
    fun deleteFiles(): Int {
        val p = paths ?: return 0
        val dir = File(p.appRoot, DIR)
        return synchronized(flushLock) {
            synchronized(lock) { pending.clear() }
            var n = 0
            dir.listFiles { f -> isLogFile(f.name) }?.forEach { if (it.delete()) n++ }
            n
        }
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
        if (unclean && enabled) prev = dumpPrevious()
        append(sessionHeader(c, unclean), false) // [LOGCAT-SESSION]
        flush()
    }

    /** [LOGCAT-SESSION] Satu baris komentar per sesi: waktu, versi, PID, perangkat, dan status akhir sesi sebelumnya. */
    private fun sessionHeader(c: Context, unclean: Boolean): String {
        val pi = runCatching { c.packageManager.getPackageInfo(c.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        val code = pi?.versionCode?.toString() ?: "?"
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        return "# ===== Molina sesi baru $stamp | versi ${pi?.versionName ?: "?"} (kode $code) | pid ${android.os.Process.myPid()} | " +
            "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) | $abi | " +
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} | " +
            "sesi sebelumnya: ${if (unclean) "TIDAK normal" else "normal"} ====="
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
        if (!enabled) { synchronized(lock) { pending.clear() }; return@synchronized } // [LOGCAT-FILE-TOGGLE]
        val p = paths ?: return@synchronized
        val pm = prev
        val batch = synchronized(lock) { val b = ArrayList(pending); pending.clear(); b }
        if (pm == null && batch.isEmpty()) return@synchronized
        try {
            val dir = File(p.appRoot, DIR)
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
