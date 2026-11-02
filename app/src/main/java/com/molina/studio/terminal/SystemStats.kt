package com.molina.studio.terminal

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.molina.core.common.AppVisibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.util.Locale

enum class Level { Normal, Warn, High }

data class StatsSnapshot(
    val rxBps: Long = 0,
    val txBps: Long = 0,
    val cpuPercent: Int = 0,
    val ramMb: Int = 0,
    val totalRamMb: Int = 0,
    val cpuLevel: Level = Level.Normal,
    val ramLevel: Level = Level.Normal,
    val netPerUid: Boolean = true // false = jaringan dihitung total perangkat (per-aplikasi tak didukung)
) {
    val rxText: String get() = speed(rxBps)
    val txText: String get() = speed(txBps)
    val totalGbText: String get() = String.format(ID, "%.1f", totalRamMb / 1024.0)

    private fun speed(bps: Long): String {
        val kb = bps / 1024.0
        return if (kb < 1000) String.format(ID, "%.1f KB/s", kb)
        else String.format(ID, "%.1f MB/s", kb / 1024.0)
    }

    private companion object {
        val ID: Locale = Locale("id", "ID")
    }
}

/**
 * Statistik seluruh proses ber-UID aplikasi (termasuk proot dan guest Debian).
 * Hemat: berhenti total saat aplikasi di latar belakang, daftar PID di-cache (dipindai tiap
 * 3 dtk), dan PSS (panggilan binder yang mahal) hanya dihitung tiap 5 dtk.
 */
object SystemStats {
    // Ambang warna CPU (% kapasitas total semua core) dan RAM (% RAM perangkat).
    private const val CPU_SPIKE_POINTS = 10
    private const val CPU_WARN = 20
    private const val CPU_HIGH = 40
    private const val RAM_SPIKE_MB = 50
    private const val RAM_WARN_PCT = 10
    private const val RAM_HIGH_PCT = 20
    private const val HOLD_TICKS = 5 // lama warna kuning dipertahankan setelah lonjakan (detik)

    private const val MB = 1024L * 1024L

    // [MONITOR-REALTIME] Pencuplik ringan tiap detik (hanya baca counter); kerja berat (pindai PID, PSS)
    // dipindah ke coroutine terpisah supaya tidak menunda emisi. Waktu dt diambil tepat saat counter dibaca.
    fun flow(context: Context): Flow<StatsSnapshot> = channelFlow {
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val uid = Process.myUid()
        val clk = Os.sysconf(OsConstants._SC_CLK_TCK).coerceAtLeast(1L)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val totalMb = (ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / MB).toInt()
        // Per-UID bila didukung perangkat; jika tidak, jatuh ke total seluruh perangkat (bukan nol terus).
        val perUid = TrafficStats.getUidRxBytes(uid) >= 0L
        fun rxNow(): Long = if (perUid) TrafficStats.getUidRxBytes(uid) else TrafficStats.getTotalRxBytes()
        fun txNow(): Long = if (perUid) TrafficStats.getUidTxBytes(uid) else TrafficStats.getTotalTxBytes()

        while (true) {
            AppVisibility.foreground.first { it } // tidur sampai aplikasi terlihat lagi

            val pidsRef = AtomicReference<List<Int>>(ownPids(uid))
            val ramRef = AtomicInteger(pssMb(am, pidsRef.get()))
            val ramHold = AtomicInteger(0)
            val heavy = launch {
                var n = 0
                while (true) {
                    delay(1000)
                    n++
                    if (n % 3 == 0) pidsRef.set(ownPids(uid))
                    if (n % 5 == 0) {
                        val m = pssMb(am, pidsRef.get())
                        if (m - ramRef.get() >= RAM_SPIKE_MB) ramHold.set(HOLD_TICKS)
                        ramRef.set(m)
                    }
                }
            }
            try {
                var lastAt = SystemClock.elapsedRealtime()
                var lastRx = rxNow()
                var lastTx = txNow()
                var lastTicks = pidsRef.get().sumOf { cpuTicks(it) }
                var lastCpu = 0
                var cpuHold = 0

                while (AppVisibility.foreground.value) {
                    delay(1000)
                    val now = SystemClock.elapsedRealtime()
                    val rx = rxNow()
                    val tx = txNow()
                    val dt = (now - lastAt).coerceAtLeast(1L) / 1000.0
                    lastAt = now

                    val rxBps = if (rx >= 0 && lastRx >= 0) ((rx - lastRx) / dt).toLong().coerceAtLeast(0) else 0L
                    val txBps = if (tx >= 0 && lastTx >= 0) ((tx - lastTx) / dt).toLong().coerceAtLeast(0) else 0L
                    lastRx = rx
                    lastTx = tx

                    val ticks = pidsRef.get().sumOf { cpuTicks(it) }
                    val cpu = ((ticks - lastTicks).coerceAtLeast(0L) / clk.toDouble() / dt * 100.0 / cores)
                        .toInt().coerceIn(0, 100)
                    lastTicks = ticks
                    if (cpuHold > 0) cpuHold--
                    if (cpu - lastCpu >= CPU_SPIKE_POINTS) cpuHold = HOLD_TICKS
                    lastCpu = cpu

                    if (ramHold.get() > 0) ramHold.decrementAndGet()
                    val ramMb = ramRef.get()

                    val cpuLevel = when {
                        cpu >= CPU_HIGH -> Level.High
                        cpu >= CPU_WARN || cpuHold > 0 -> Level.Warn
                        else -> Level.Normal
                    }
                    val ramPct = if (totalMb > 0) ramMb * 100 / totalMb else 0
                    val ramLevel = when {
                        ramPct >= RAM_HIGH_PCT -> Level.High
                        ramPct >= RAM_WARN_PCT || ramHold.get() > 0 -> Level.Warn
                        else -> Level.Normal
                    }
                    send(StatsSnapshot(rxBps, txBps, cpu, ramMb, totalMb, cpuLevel, ramLevel, perUid))
                }
            } finally {
                heavy.cancel()
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun ownPids(uid: Int): List<Int> {
        val dirs = File("/proc").listFiles() ?: return emptyList()
        val out = ArrayList<Int>()
        for (d in dirs) {
            val pid = d.name.toIntOrNull() ?: continue
            val owner = try { Os.stat("/proc/$pid").st_uid } catch (e: Exception) { continue }
            if (owner == uid) out.add(pid)
        }
        return out
    }

    /** utime + stime (dalam clock tick) dari /proc/<pid>/stat. */
    private fun cpuTicks(pid: Int): Long = try {
        val f = File("/proc/$pid/stat").readText().substringAfterLast(')').trim().split(' ')
        f[11].toLong() + f[12].toLong()
    } catch (e: Exception) {
        0L
    }

    private fun pssMb(am: ActivityManager, pids: List<Int>): Int = try {
        if (pids.isEmpty()) 0
        else (am.getProcessMemoryInfo(pids.toIntArray()).sumOf { it.totalPss.toLong() } / 1024L).toInt()
    } catch (e: Exception) {
        0
    }
}
