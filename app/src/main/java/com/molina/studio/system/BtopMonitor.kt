package com.molina.studio.system

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.molina.core.common.AppVisibility
import com.molina.core.common.BtopProbe
import com.molina.core.common.BtopRow
import com.molina.core.common.CoreCpuTracker
import com.molina.core.common.LoadAvg
import com.molina.core.common.MemInfo
import com.molina.core.common.ProcCpuTracker
import com.molina.core.common.TaskProbe
import com.molina.core.common.TaskTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

/** Satu inti CPU perangkat. [usage] null bila /proc/stat diblokir; [freqMhz]/[maxMhz] null bila sysfs tak terbaca. */
internal data class CoreInfo(val index: Int, val usage: Float?, val freqMhz: Int?, val maxMhz: Int?)

internal data class BtopSnapshot(
    val cores: List<CoreInfo> = emptyList(),
    val cpuCount: Int = 1,
    /** Total CPU semua proses Molina; 100 = satu inti penuh. */
    val appCpu: Float = 0f,
    val mem: MemInfo? = null,
    /** Total PSS semua proses Molina (KB); < 0 bila belum terukur. */
    val appPssKb: Long = -1L,
    val load: LoadAvg? = null,
    val uptimeSec: Long = 0L,
    val rows: List<BtopRow> = emptyList(),
) {
    /** true bila usage per inti perangkat tersedia (tidak diblokir SELinux). */
    val hasCoreUsage: Boolean get() = cores.isNotEmpty() && cores.all { it.usage != null }
}

/**
 * [BTOP-MONITOR] Pencuplik detail ala btop. Proses hanya milik UID aplikasi (aturan Android). Data tingkat
 * perangkat (per-inti, meminfo, loadavg) dibaca bila diizinkan; yang diblokir dikembalikan null/kosong, bukan
 * ditebak. Tidur saat aplikasi di latar belakang. PSS (panggilan binder yang mahal) dihitung tiap [PSS_EVERY] sampel.
 */
internal object BtopMonitor {
    private const val PERIOD_MS = 1500L
    private const val PSS_EVERY = 3

    fun snapshots(context: Context): Flow<BtopSnapshot> = flow {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val clk = Os.sysconf(OsConstants._SC_CLK_TCK).coerceAtLeast(1L).toInt()
        val procTracker = ProcCpuTracker(clk)
        val coreTracker = CoreCpuTracker()
        val selfPid = Process.myPid()
        val uid = Process.myUid()
        var n = 0
        var pss: Map<Int, Long> = emptyMap()
        while (true) {
            AppVisibility.foreground.first { it }
            val snap = withContext(Dispatchers.IO) {
                val procs = AndroidProcessOps.list().filter { it.uid == uid && it.alive }
                val stats = HashMap<Int, com.molina.core.common.ProcStat>()
                val cmds = HashMap<Int, List<String>>()
                for (p in procs) {
                    BtopProbe.readProcStat(p.pid)?.let { stats[p.pid] = it }
                    cmds[p.pid] = TaskProbe.cmdline(p.pid)
                }
                val cpu = procTracker.update(System.nanoTime(), stats.mapValues { it.value.ticks })
                if (n % PSS_EVERY == 0) pss = readPss(am, procs.map { it.pid })
                n++
                val uptime = readUptime()
                val base = TaskTable.build(procs, selfPid, uid, cmds, emptyMap())
                val rows = base.map { r ->
                    val st = stats[r.pid]
                    BtopRow(
                        pid = r.pid,
                        ppid = r.ppid,
                        name = r.name,
                        cmdline = r.cmdline,
                        state = r.state,
                        threads = st?.threads ?: 1,
                        nice = st?.nice ?: 0,
                        rssKb = r.rssKb,
                        pssKb = pss[r.pid] ?: -1L,
                        cpu = cpu[r.pid] ?: 0f,
                        runtimeSec = st?.let { BtopProbe.runtimeSec(it.startTicks, uptime, clk) } ?: 0L,
                        group = r.group,
                        isSelf = r.isSelf,
                    )
                }
                val usage = coreTracker.update(readText("/proc/stat")?.let(BtopProbe::parseCpuStat).orEmpty())
                val cores = (0 until cpuCount).map { i ->
                    CoreInfo(
                        index = i,
                        usage = usage.getOrNull(i),
                        freqMhz = readKhz("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq"),
                        maxMhz = readKhz("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq"),
                    )
                }
                BtopSnapshot(
                    cores = cores,
                    cpuCount = cpuCount,
                    appCpu = rows.sumOf { it.cpu.toDouble() }.toFloat(),
                    mem = readText("/proc/meminfo")?.let(BtopProbe::parseMemInfo),
                    appPssKb = if (pss.isEmpty()) -1L else pss.values.sum(),
                    load = readText("/proc/loadavg")?.let(BtopProbe::parseLoadAvg),
                    uptimeSec = uptime.toLong(),
                    rows = rows,
                )
            }
            emit(snap)
            delay(PERIOD_MS)
        }
    }

    /** PSS per PID (KB) lewat ActivityManager; hanya untuk PID milik aplikasi. Gagal -> peta kosong. */
    private fun readPss(am: ActivityManager, pids: List<Int>): Map<Int, Long> = try {
        if (pids.isEmpty()) emptyMap()
        else {
            val infos = am.getProcessMemoryInfo(pids.toIntArray())
            pids.indices.associate { pids[it] to infos[it].totalPss.toLong() }
        }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun readUptime(): Double =
        readText("/proc/uptime")?.let(BtopProbe::parseUptimeSec) ?: (SystemClock.elapsedRealtime() / 1000.0)

    private fun readText(path: String): String? = try { File(path).readText() } catch (e: Exception) { null }

    /** sysfs memberi kHz; diubah ke MHz. */
    private fun readKhz(path: String): Int? =
        readText(path)?.trim()?.toLongOrNull()?.let { (it / 1000L).toInt() }
}
