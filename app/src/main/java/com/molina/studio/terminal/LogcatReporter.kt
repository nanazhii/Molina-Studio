package com.molina.studio.terminal

import com.molina.studio.log.LogBus
import com.molina.core.common.LogLevel

/**
 * Logcat reporter: membaca logcat milik proses aplikasi sendiri (tanpa izin tambahan)
 * dan menyalurkannya ke LogBus (sumber "logcat") sehingga ikut digabung oleh LogGrouping.
 * Default mati; dinyalakan lewat Settings > Terminal > Debugging.
 */
object LogcatReporter {
    private val LINE = Regex("""^\S+\s+\S+\s+\d+\s+\d+\s+([VDIWEFA])\s+(.+?)\s*:\s?(.*)$""")

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
        val (lv, tag, msg) = m.destructured
        val level = when (lv[0]) {
            'V', 'D' -> LogLevel.Debug
            'I' -> LogLevel.Info
            'W' -> LogLevel.Warn
            else -> LogLevel.Error
        }
        LogBus.log("logcat", level, tag, msg)
    }
}
