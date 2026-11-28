package com.molina.studio.cinema

import android.content.Context
import android.os.Process
import com.molina.core.api.AppLog
import com.molina.core.api.LogLevel
import com.molina.core.system.RefreshReport
import com.molina.core.system.Refreshable
import com.molina.core.system.RssDelta
import com.molina.core.system.TaskGroup
import com.molina.core.system.TaskProbe
import com.molina.core.system.TaskTable
import com.molina.studio.stream.StreamCache
import com.molina.studio.stream.YtdlpRunner
import com.molina.studio.common.AndroidProcessOps
import com.molina.studio.system.MpvControl

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
