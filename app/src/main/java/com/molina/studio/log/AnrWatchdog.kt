package com.molina.studio.log

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * [ANR-LOG] Padanan "ANR in <paket>" dari ActivityManager yang di PC terlihat lewat `adb logcat`.
 * Aplikasi tanpa READ_LOGS tidak bisa membaca log system_server, jadi ANR dideteksi dari dalam proses:
 * heartbeat ke main thread tiap 500 ms. Bila tak dijalankan >= 2 dtk -> WARN; >= 5 dtk (batas input dispatch
 * Android) -> ERROR berisi stack main thread + thread molina-*, mpv, Dispatchers (mirip traces.txt).
 * Ditulis lewat android.util.Log (tag Molina/ANR) sehingga LogcatReporter ikut menyalinnya ke halaman Log dan
 * berkas mentah. Hidup/mati mengikuti Settings > Terminal > Debugging > Logcat Reporter.
 */
object AnrWatchdog {
    const val TAG = "Molina/ANR"
    private const val TICK_MS = 500L
    private const val WARN_MS = 2_000L
    private const val ANR_MS = 5_000L
    private const val MAX_FRAMES = 30

    @Volatile private var thread: Thread? = null
    @Volatile private var pendingSince = 0L // uptime saat heartbeat diposting; 0 = main thread sudah menjalankannya
    @Volatile private var pkg = "com.molina.studio"
    private val main = Handler(Looper.getMainLooper())
    private val beat = Runnable { pendingSince = 0L }

    fun attach(c: android.content.Context) { pkg = c.applicationContext.packageName }

    @Synchronized
    fun setEnabled(on: Boolean) {
        if (on) {
            if (thread != null) return
            pendingSince = 0L
            val t = Thread({ loop() }, "molina-anr-watchdog").apply { isDaemon = true }
            thread = t
            t.start()
        } else {
            thread?.interrupt()
            thread = null
        }
    }

    private fun loop() {
        val me = Thread.currentThread()
        var level = 0 // 0 normal, 1 sudah WARN, 2 sudah ERROR (satu laporan per episode macet)
        var worst = 0L
        while (thread === me && !me.isInterrupted) {
            if (pendingSince == 0L) {
                pendingSince = SystemClock.uptimeMillis()
                main.post(beat)
            }
            try { Thread.sleep(TICK_MS) } catch (e: InterruptedException) { return }
            val since = pendingSince
            val blocked = if (since == 0L) 0L else SystemClock.uptimeMillis() - since
            if (blocked > worst) worst = blocked
            when {
                blocked >= ANR_MS && level < 2 -> {
                    level = 2
                    Log.println(Log.ERROR, TAG, "ANR in $pkg: main thread terblokir $blocked ms\n" + dumpAll())
                }
                blocked >= WARN_MS && level < 1 -> {
                    level = 1
                    Log.println(Log.WARN, TAG, "Main thread tidak merespons $blocked ms\n" + dump(Looper.getMainLooper().thread))
                }
                blocked == 0L && level > 0 -> {
                    Log.println(Log.INFO, TAG, "Main thread pulih; macet terlama ~$worst ms")
                    level = 0
                    worst = 0L
                }
            }
        }
    }

    private fun dump(t: Thread): String {
        val st = t.stackTrace
        val sb = StringBuilder("\"").append(t.name).append("\" state=").append(t.state)
        for (i in 0 until minOf(st.size, MAX_FRAMES)) sb.append("\n    at ").append(st[i])
        if (st.size > MAX_FRAMES) sb.append("\n    ... ").append(st.size - MAX_FRAMES).append(" frame lagi")
        return sb.toString()
    }

    private fun dumpAll(): String {
        val mainT = Looper.getMainLooper().thread
        val sb = StringBuilder(dump(mainT))
        for (t in Thread.getAllStackTraces().keys) {
            if (t === mainT || t.name == "molina-anr-watchdog") continue
            val n = t.name.lowercase()
            if (n.startsWith("molina-") || n.contains("mpv") || n.startsWith("defaultdispatcher") || n.startsWith("event"))
                sb.append("\n\n").append(dump(t))
        }
        return sb.toString()
    }
}
