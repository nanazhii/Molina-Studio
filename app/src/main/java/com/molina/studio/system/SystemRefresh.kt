package com.molina.studio.system

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.molina.core.common.AppLog
import com.molina.core.common.LogLevel
import com.molina.core.common.RefreshReport
import com.molina.core.common.Refreshable
import com.molina.core.common.Refreshers
import com.molina.core.common.RssDelta
import com.molina.core.common.TaskGroup
import com.molina.core.common.TaskProbe
import com.molina.core.common.TaskTable
import com.molina.studio.cinema.MemoryRefresh
import com.molina.studio.code.ProotScriptExecutor
import com.molina.studio.stream.StreamCache
import com.molina.studio.stream.YtdlpRunner
import com.molina.studio.terminal.TerminalService
import com.molina.studio.terminal.TerminalSessions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Penyegaran sistem: mematikan proses latar per modul sampai akar. Modul mendaftar ke [Refreshers];
 * layar Pengaturan hanya membaca registri itu. Modul Code (Fase 6) cukup memanggil
 * `Refreshers.register(...)` dengan id "code".
 */
internal object SystemRefresh {
    fun install(app: Context) {
        Refreshers.register(MpvRefreshable(app.applicationContext))
        Refreshers.register(CodeRefreshable())
        Refreshers.register(TerminalRefreshable(app.applicationContext))
    }

    /**
     * Jumlah proses zombie (state Z) milik UID aplikasi. Polling singkat karena thread waitpid
     * (JNI/JVM) butuh waktu untuk me-reap proses yang baru dimatikan. SIGKILL tidak bisa
     * menghapus zombie; hanya wait() dari induknya yang bisa.
     */
    fun settledZombies(maxWaitMs: Long = 500L): Int {
        val uid = Process.myUid()
        var n = AndroidProcessOps.list().count { it.uid == uid && it.state == 'Z' }
        val deadline = System.nanoTime() + maxWaitMs * 1_000_000L
        while (n > 0 && System.nanoTime() < deadline) {
            try { Thread.sleep(50) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
            n = AndroidProcessOps.list().count { it.uid == uid && it.state == 'Z' }
        }
        return n
    }

    /** Jalankan [block] di main thread dan tunggu (maks [timeoutMs]); dipanggil dari thread latar. */
    fun onMainBlocking(timeoutMs: Long = 2000L, block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { block(); return }
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try { block() } finally { latch.countDown() }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }
}

/** Pemutar mpv + resolver stream + proses yt-dlp. Aplikasi tetap hidup. */
internal class MpvRefreshable(private val app: Context) : Refreshable {
    override val id = "mpv"
    override val label = "Pemutar (mpv)"

    override fun activeCount(): Int =
        (if (MpvControl.isActive()) 1 else 0) + (if (YtdlpRunner.isRunning()) 1 else 0)

    private val inProcess = RssDelta()

    /**
     * [MEM-MPV] mpv berjalan di dalam proses aplikasi (tanpa PID sendiri), jadi RAM-nya diperkirakan dari selisih
     * RSS proses terhadap dasar saat pemutar tidak aktif, ditambah RSS proses yt-dlp/ffmpeg yang terukur nyata.
     */
    override fun memoryKb(): Long =
        RssDelta.combine(inProcess.estimate(MpvControl.isActive(), MemoryRefresh.rssKb()), downloaderRssKb())

    private fun downloaderRssKb(): Long = try {
        val uid = Process.myUid()
        val procs = AndroidProcessOps.list().filter { it.uid == uid && it.alive }
        val cmds = procs.associate { it.pid to TaskProbe.cmdline(it.pid) }
        TaskTable.build(procs, Process.myPid(), uid, cmds, emptyMap())
            .filter { it.group == TaskGroup.Unduhan }
            .sumOf { it.rssKb }
    } catch (e: Exception) {
        0L
    }

    override fun refresh(): RefreshReport {
        var killed = 0
        // 1) proses luar (yt-dlp + proot + python), 2) pemutar lewat jalur resmi, 3) lepas resolver + GC.
        killed += YtdlpRunner.cancelActive()
        val requested = MpvControl.shutdown(app)
        // Verifikasi: "dihentikan" hanya dihitung bila pemutar benar-benar sudah tidak aktif.
        val still = MpvControl.isActive()
        val stopped = if (requested && !still) 1 else 0
        // Cache unduhan TikTok dibersihkan hanya setelah mpv berhenti, supaya tidak menghapus berkas yang sedang diputar.
        val cleared = if (still) 0 else try { StreamCache.clear(app) } catch (e: Exception) { 0 }
        val mem = MemoryRefresh.run(releaseResolver = !still)
        val warn = if (still) " Pemutar belum berhenti." else ""
        AppLog.log(
            "system", if (still) LogLevel.Warn else LogLevel.Info, "refresh",
            "mpv: dihentikan=$stopped dimatikan=$killed cache=$cleared tersisa=${if (still) 1 else 0} ${MemoryRefresh.describe(mem)}"
        )
        return RefreshReport(
            id, stopped = stopped, killed = killed, remaining = if (still) 1 else 0,
            note = MemoryRefresh.describe(mem) + warn
        )
    }
}

/** Semua sesi Debian dan setiap proses anak/yatim milik aplikasi (proot, guest, yt-dlp). */
internal class TerminalRefreshable(private val app: Context) : Refreshable {
    override val id = "terminal"
    override val label = "Terminal"

    override fun activeCount(): Int = AndroidProcessOps.reaper().ownTargets().size

    override fun memoryKb(): Long =
        AndroidProcessOps.reaper().ownTargets().sumOf { it.rssKb }

    override fun refresh(): RefreshReport {
        val sessions = TerminalSessions.sessions.size
        // killAll memutasi daftar sesi (state Compose): lakukan di main thread. Callback "sesi berakhir"
        // baru jalan setelah ini (asinkron) dan diabaikan karena daftar sudah kosong.
        SystemRefresh.onMainBlocking { TerminalSessions.killAll() }
        try { TerminalService.requestStop(app) } catch (e: Exception) {
            AppLog.log("system", LogLevel.Warn, "refresh", "terminal: requestStop gagal ${e.javaClass.simpleName}")
        }
        val r = AndroidProcessOps.reaper().reapOwn()
        val z = SystemRefresh.settledZombies()
        AppLog.log(
            "system", LogLevel.Info, "refresh",
            "terminal: sesi=$sessions proses=${r.found} bersih=${r.terminated} paksa=${r.killed} tersisa=${r.remaining} zombie=$z"
        )
        val notes = listOfNotNull(
            if (r.remaining > 0) "${r.remaining} proses tidak mau mati" else null,
            if (z > 0) "$z zombie menunggu di-reap induknya" else null,
        )
        return RefreshReport(
            id, stopped = sessions, killed = r.total, remaining = r.remaining,
            note = notes.joinToString("; ").ifEmpty { null }
        )
    }
}

/** Skrip .sh/.py yang dijalankan dari editor Code (Debian PRoot). Aplikasi tetap hidup. */
internal class CodeRefreshable : Refreshable {
    override val id = "code"
    override val label = "Skrip Code"

    override fun activeCount(): Int = if (ProotScriptExecutor.isRunning()) 1 else 0

    override fun refresh(): RefreshReport {
        val n = ProotScriptExecutor.cancelActive()
        AppLog.log("system", LogLevel.Info, "refresh", "code: dibatalkan=$n")
        return RefreshReport(id, stopped = n)
    }
}
