package com.molina.studio.terminal

import com.molina.studio.log.LogBus
import com.molina.core.common.LogLevel

/**
 * Logcat reporter: membaca logcat milik proses aplikasi sendiri (tanpa izin tambahan)
 * dan menyalurkannya ke LogBus (sumber "logcat") sehingga ikut digabung oleh LogGrouping.
 * Default mati; dinyalakan lewat Settings > Terminal > Debugging.
 */
object LogcatReporter {
    private val LINE = Regex("""^(\d\d)-(\d\d)\s+(\d\d):(\d\d):(\d\d)\.(\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+(.+?)\s*:\s?(.*)$""")

    @Volatile private var proc: Process? = null
    private var everStarted = false // [LOGCAT-TOGGLE] nyala ulang tidak memutar ulang 200 baris lama

    @Synchronized
    fun setEnabled(on: Boolean) { if (on) start() else stop() }

    @Synchronized
    private fun start() {
        if (proc != null) return
        val p = try {
            ProcessBuilder(
                "logcat", "-v", "threadtime", "-T", if (everStarted) "1" else "200",
                "--pid=${android.os.Process.myPid()}"
            ).redirectErrorStream(true).start()
        } catch (e: Exception) {
            LogBus.log("logcat", LogLevel.Warn, "reporter", "Gagal memulai logcat: ${e.message}")
            return
        }
        proc = p
        everStarted = true
        Thread({
            try {
                p.inputStream.bufferedReader().use { r -> r.forEachLine { line -> handle(line) } }
            } catch (e: Exception) {
                // proses dihentikan (destroy) -> abaikan
            } finally { // [LOGCAT-PULIH] bersihkan status agar bisa dinyalakan ulang bila logcat mati sendiri
                val unexpected = synchronized(LogcatReporter) {
                    if (proc === p) { proc = null; true } else false
                }
                try { p.destroy() } catch (e: Exception) { }
                try { p.waitFor() } catch (e: InterruptedException) { }
                if (unexpected) {
                    LogBus.log("logcat", LogLevel.Warn, "reporter", "Logcat berhenti sendiri; nyalakan ulang di Settings > Terminal > Debugging")
                }
            }
        }, "molina-logcat").apply { isDaemon = true }.start()
        LogBus.log("logcat", LogLevel.Info, "reporter", "Logcat reporter aktif")
    }

    @Synchronized
    private fun stop() {
        proc?.destroy()
        proc = null
    }

    private fun handle(line: String) {
        if (line.startsWith("---")) return
        val m = LINE.find(line) ?: return
        val g = m.groupValues
        val pid = g[7].toIntOrNull() ?: return
        val tid = g[8].toIntOrNull() ?: return
        val prio = g[9][0]
        val level = when (prio) {
            'V', 'D' -> LogLevel.Debug
            'I' -> LogLevel.Info
            'W' -> LogLevel.Warn
            else -> LogLevel.Error
        }
        val t = epochOf(g[1], g[2], g[3], g[4], g[5], g[6])
        // [LOGCAT-ADB] simpan waktu/pid/tid/prioritas asli agar tampilan dan ekspor 1:1 dengan `logcat -v threadtime`
        LogBus.logRaw("logcat", level, g[10], g[11], t, pid, tid, prio)
    }

    /** Waktu logcat (tanpa tahun) -> epoch ms; bila hasilnya di masa depan (lintas tahun baru) pakai tahun lalu. */
    private fun epochOf(mo: String, d: String, h: String, mi: String, sec: String, ms: String): Long {
        val c = java.util.Calendar.getInstance()
        val year = c.get(java.util.Calendar.YEAR)
        c.clear()
        c.set(year, (mo.toIntOrNull() ?: 1) - 1, d.toIntOrNull() ?: 1,
            h.toIntOrNull() ?: 0, mi.toIntOrNull() ?: 0, sec.toIntOrNull() ?: 0)
        c.set(java.util.Calendar.MILLISECOND, ms.toIntOrNull() ?: 0)
        var t = c.timeInMillis
        if (t > System.currentTimeMillis() + 86_400_000L) {
            c.set(java.util.Calendar.YEAR, year - 1)
            t = c.timeInMillis
        }
        return t
    }
}
