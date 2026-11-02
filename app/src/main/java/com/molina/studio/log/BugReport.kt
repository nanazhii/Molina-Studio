package com.molina.studio.log

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.molina.core.common.LogLevel
import com.molina.core.common.LogRedact
import com.molina.core.common.MolinaDir
import com.molina.studio.AppPaths
import com.molina.studio.automation.AutoPrefs
import com.molina.studio.settings.AptHistory
import com.molina.studio.settings.BackupManager
import com.molina.studio.settings.hubStatusRows
import com.molina.studio.terminal.CrashReporter
import com.molina.studio.terminal.RootfsManager
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Laporan bug dalam satu berkas, empat bagian: (1) ringkasan, (2) PER KELOMPOK (Cinema, Terminal, Sistem,
 * Logcat, Crash) supaya sumber masalah mudah dikenali, (3) GABUNGAN kronologis semua sumber, (4) laporan
 * crash terbaru. Seluruh teks disamarkan (URL dan token) karena folder Export terbaca aplikasi lain.
 */
internal object BugReport {
    private const val GROUP_MAX_LINES = 400
    private const val CRASH_MAX = 3
    private const val CRASH_MAX_CHARS = 12_000

    private val GROUPS = listOf(
        "cinema" to "CINEMA (pemutar mpv)",
        "terminal" to "TERMINAL",
        "sistem" to "SISTEM",
        "logcat" to "LOGCAT",
        "crash" to "CRASH",
        "lainnya" to "LAINNYA"
    )
    private val TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

    private fun groupOf(source: String): String = when (source) {
        "cinema", "mpv" -> "cinema"
        "terminal", "sistem", "logcat", "crash" -> source
        else -> "lainnya"
    }

    private fun line(e: LogEntry, withSource: Boolean): String {
        val n = if (e.count > 1) "  \u00D7${e.count}" else ""
        val src = if (withSource) "${e.source}/" else ""
        return "${TS.format(Instant.ofEpochMilli(e.time))} ${e.level.code} $src${e.tag}: ${e.message}$n"
    }

    fun build(ctx: Context, entries: List<LogEntry>, status: List<Pair<String, String>>, crashes: List<File>): String {
        val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        val byGroup = entries.groupBy { groupOf(it.source) }
        val sb = StringBuilder()
        sb.appendLine("MOLINA LAPORAN BUG")
        sb.appendLine("Dibuat    : ${DATE.format(Instant.now())}")
        sb.appendLine("Versi app : $ver")
        sb.appendLine(
            "Perangkat : ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}"
        )
        status.forEach { sb.appendLine("${it.first.padEnd(18)}: ${it.second}") }
        sb.appendLine("Isi       : 1) Ringkasan  2) Per kelompok  3) Gabungan kronologis  4) Crash")
        sb.appendLine()

        sb.appendLine("=== 1. RINGKASAN ===")
        sb.appendLine(
            "Total ${entries.size} baris; ${entries.count { it.level == LogLevel.Error }} galat, " +
                "${entries.count { it.level == LogLevel.Warn }} peringatan"
        )
        GROUPS.forEach { (key, title) ->
            val list = byGroup[key].orEmpty()
            if (list.isNotEmpty()) {
                sb.appendLine(
                    "- $title: ${list.size} baris, ${list.count { it.level == LogLevel.Error }} galat, " +
                        "${list.count { it.level == LogLevel.Warn }} peringatan"
                )
            }
        }
        val errs = entries.filter { it.level == LogLevel.Error }.takeLast(10)
        if (errs.isNotEmpty()) {
            sb.appendLine("Galat terakhir:")
            errs.forEach { sb.appendLine("  " + line(it, true)) }
        }
        sb.appendLine()

        sb.appendLine("=== 2. PER KELOMPOK ===")
        GROUPS.forEach { (key, title) ->
            val list = byGroup[key].orEmpty()
            if (list.isEmpty()) return@forEach
            val tags = list.groupingBy { it.tag }.eachCount().entries
                .sortedByDescending { it.value }.take(5).joinToString(", ") { "${it.key} (${it.value})" }
            sb.appendLine("--- $title: ${list.size} baris ---")
            sb.appendLine("Tag terbanyak: $tags")
            if (list.size > GROUP_MAX_LINES) {
                sb.appendLine("(menampilkan $GROUP_MAX_LINES baris terakhir; lengkapnya di bagian 3)")
            }
            list.takeLast(GROUP_MAX_LINES).forEach { sb.appendLine(line(it, false)) }
            sb.appendLine()
        }

        sb.appendLine("=== 3. GABUNGAN KRONOLOGIS (${entries.size} baris, sumber/tag di tiap baris) ===")
        entries.forEach { sb.appendLine(line(it, true)) }
        sb.appendLine()

        sb.appendLine("=== 4. CRASH (${crashes.size} laporan terbaru) ===")
        if (crashes.isEmpty()) sb.appendLine("Tidak ada laporan crash.")
        crashes.forEach { f ->
            sb.appendLine("--- ${CrashReporter.title(f)} ---")
            sb.appendLine(CrashReporter.read(f).take(CRASH_MAX_CHARS))
            sb.appendLine()
        }
        return sb.toString()
    }

    /** Simpan laporan ke <appRoot>/Documents/Export (thread latar; hasil lewat Toast). */
    fun export(ctx: Context) {
        val app = ctx.applicationContext
        val entries = LogBus.entries.value
        Thread({
            val msg = try {
                AptHistory.init(app)
                AutoPrefs.init(app)
                RootfsManager.refresh(app)
                val auto = AutoPrefs.state.value
                val status = hubStatusRows(
                    RootfsManager.state.value, AptHistory.pending.value, auto,
                    maxOf(auto.lastBackup, BackupManager.lastBackupMs(app))
                ).map { it.label to it.value }
                val text = LogRedact.text(build(app, entries, status, CrashReporter.list(app).take(CRASH_MAX)))
                val dir = AppPaths.get(app).dir(MolinaDir.Export)
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException("folder tidak bisa dibuat: ${dir.path}")
                val file = File(dir, "molina-laporan-bug-${STAMP.format(Instant.now())}.txt")
                file.writeText(text, Charsets.UTF_8)
                "Laporan tersimpan: ${file.path}"
            } catch (e: Exception) {
                "Gagal menyimpan laporan: ${e.message}"
            }
            Handler(Looper.getMainLooper()).post { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
        }, "molina-bugreport").start()
    }
}
