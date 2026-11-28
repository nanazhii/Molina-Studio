package com.molina.cinema

import android.os.Debug
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [PERF-MEM] Pengukur memori selama pemutaran. Satu thread daemon berprioritas rendah mencatat ringkasan PSS
 * (java-heap, native-heap, graphics, code, stack, total) ke log Molina. Baris dicatat bila total berubah >= 8 MB
 * atau tiap 60 detik, sehingga log tidak banjir. Tujuannya atribusi: native-heap tinggi = ffmpeg/cache demuxer/dekode
 * perangkat lunak; graphics tinggi = GL/gpu-next/scaler/interpolasi; java-heap tinggi = bitmap/koleksi Kotlin.
 */
internal object MemProbe {
    private const val TAG = "mem"
    private const val INTERVAL_MS = 15_000L
    private const val DELTA_KB = 8 * 1024
    private const val HEARTBEAT_TICKS = 4
    private val running = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val t = Thread({
            val mi = Debug.MemoryInfo()
            var lastTotal = -1
            var quiet = 0
            try {
                while (running.get()) {
                    Debug.getMemoryInfo(mi)
                    val total = mi.totalPss
                    if (lastTotal < 0 || Math.abs(total - lastTotal) >= DELTA_KB || ++quiet >= HEARTBEAT_TICKS) {
                        MpvLog.i(TAG, "PSS total=${mb(total)} java=${stat(mi, "summary.java-heap")} " +
                            "native=${stat(mi, "summary.native-heap")} graphics=${stat(mi, "summary.graphics")} " +
                            "code=${stat(mi, "summary.code")} stack=${stat(mi, "summary.stack")} " +
                            "nativeAlloc=${mb((Debug.getNativeHeapAllocatedSize() / 1024L).toInt())}")
                        lastTotal = total
                        quiet = 0
                    }
                    Thread.sleep(INTERVAL_MS)
                }
            } catch (e: InterruptedException) {
                // berhenti normal
            } catch (e: Exception) {
                MpvLog.w(TAG, "probe berhenti: ${e.javaClass.simpleName}")
            }
        }, "molina-mem-probe")
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY
        thread = t
        t.start()
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun mb(kb: Int): String = "${kb / 1024}MB"
    private fun stat(mi: Debug.MemoryInfo, key: String): String =
        mi.getMemoryStat(key)?.toIntOrNull()?.let { mb(it) } ?: "?"
}
