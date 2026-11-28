package com.molina.cinema

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [JNI-MAIN] Penjaga debug: mendeteksi main thread yang tertahan (mis. JNI sinkron ke mpv) dan mencatat stack-nya
 * ke MpvLog dengan tag "watchdog". Nonaktif bawaan: aktifkan dengan membuat berkas
 * /storage/emulated/0/Molina/debug-watchdog (hapus untuk mematikan; berlaku saat pemutar dibuka lagi).
 * Tanpa berkas itu tidak ada thread dan tidak ada biaya.
 */
internal object MainThreadWatchdog {
    private const val TAG = "watchdog"
    private const val FLAG_PATH = "/storage/emulated/0/Molina/debug-watchdog"
    private const val TICK_MS = 100L
    private const val STALL_MS = 300L

    private val main = Handler(Looper.getMainLooper())
    private val pending = AtomicBoolean(false)
    @Volatile private var postedAt = 0L
    @Volatile private var running = false
    @Volatile private var sampled = false
    private var worker: Thread? = null

    private val beat = Runnable {
        val dt = SystemClock.uptimeMillis() - postedAt
        pending.set(false)
        if (sampled) {
            sampled = false
            MpvLog.w(TAG, "[ui-macet] selesai: main thread tertahan total $dt ms")
        }
    }

    @Synchronized
    fun start() {
        if (running || !File(FLAG_PATH).exists()) return
        running = true
        MpvLog.w(TAG, "penjaga main thread aktif (ambang $STALL_MS ms)")
        worker = Thread({ loop() }, "molina-watchdog").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        worker?.interrupt()
        worker = null
        main.removeCallbacks(beat)
        pending.set(false)
        sampled = false
    }

    private fun loop() {
        val mainThread = Looper.getMainLooper().thread
        while (running) {
            if (pending.compareAndSet(false, true)) {
                postedAt = SystemClock.uptimeMillis()
                main.post(beat)
            }
            try {
                Thread.sleep(TICK_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (pending.get() && !sampled && SystemClock.uptimeMillis() - postedAt >= STALL_MS) {
                sampled = true
                MpvLog.w(TAG, describe(mainThread.stackTrace))
            }
        }
    }

    private fun describe(trace: Array<StackTraceElement>): String {
        fun fmt(f: StackTraceElement): String =
            "${f.className.substringAfterLast('.')}.${f.methodName}" + (if (f.isNativeMethod) "(native)" else ":${f.lineNumber}")
        val top = trace.take(8).joinToString(" < ") { fmt(it) }
        val app = trace.firstOrNull { it.className.startsWith("com.molina") && !it.className.contains("MainThreadWatchdog") }
        val jni = trace.any { it.isNativeMethod && it.className.contains("MPVLib") }
        return "[ui-macet] main thread tertahan >= $STALL_MS ms" + (if (jni) " [JNI mpv]" else "") +
            " | kode app: " + (if (app != null) fmt(app) else "-") + " | stack: " + top
    }
}
