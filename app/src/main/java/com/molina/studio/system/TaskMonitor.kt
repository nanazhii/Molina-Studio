package com.molina.studio.system

import android.os.Process
import com.molina.core.common.AppVisibility
import com.molina.core.common.CpuTracker
import com.molina.core.common.ReapResult
import com.molina.core.common.TaskProbe
import com.molina.core.common.TaskRow
import com.molina.core.common.TaskTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * [TASK-MANAGER] Pemantau proses milik UID aplikasi ini (proot, guest Debian, yt-dlp, skrip Code, dst).
 * Tidur saat aplikasi di latar belakang (pola yang sama dengan SystemStats), jadi tidak menguras baterai.
 */
internal object TaskMonitor {
    private const val PERIOD_MS = 2000L

    /** Aliran daftar proses, satu sampel per [PERIOD_MS]. Penghitung CPU hidup selama aliran dikumpulkan. */
    fun rows(): Flow<List<TaskRow>> = flow {
        val tracker = CpuTracker(cores = Runtime.getRuntime().availableProcessors())
        val selfPid = Process.myPid()
        val uid = Process.myUid()
        while (true) {
            AppVisibility.foreground.first { it }
            emit(withContext(Dispatchers.IO) { sample(tracker, selfPid, uid) })
            delay(PERIOD_MS)
        }
    }

    private fun sample(tracker: CpuTracker, selfPid: Int, uid: Int): List<TaskRow> {
        val procs = AndroidProcessOps.list().filter { it.uid == uid && it.alive }
        val ticks = HashMap<Int, Long>()
        val cmds = HashMap<Int, List<String>>()
        for (p in procs) {
            TaskProbe.cpuTicks(p.pid)?.let { ticks[p.pid] = it }
            cmds[p.pid] = TaskProbe.cmdline(p.pid)
        }
        val cpu = tracker.update(System.nanoTime(), ticks)
        return TaskTable.build(procs, selfPid, uid, cmds, cpu)
    }
}

/** Aksi "Akhiri Proses" / "Akhiri Pohon Proses". Blocking: panggil dari thread latar. */
internal object TaskActions {
    /** Hanya [pid] (SIGTERM, lalu SIGKILL). Proses inti aplikasi dan `logcat` dilindungi oleh ProcessReaper. */
    fun endProcess(pid: Int): ReapResult {
        val r = AndroidProcessOps.reaper()
        return r.reap { r.treeOf(pid).filter { it.pid == pid } }
    }

    /** [pid] beserta seluruh keturunannya. */
    fun endTree(pid: Int): ReapResult = AndroidProcessOps.reaper().reapTree(pid)
}
