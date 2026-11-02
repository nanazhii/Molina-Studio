package com.molina.studio.system

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.molina.core.common.AppLog
import com.molina.core.common.LogLevel
import com.molina.core.common.RefreshReport
import com.molina.core.common.Refreshable
import com.molina.core.common.Refreshers
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

    override fun memoryKb(): Long = -1L

    override fun refresh(): RefreshReport {
        var killed = 0
        // 1) proses luar (yt-dlp + proot + python), 2) pemutar lewat jalur resmi, 3) lepas resolver + GC.
        killed += YtdlpRunner.cancelActive()
        val stopped = if (MpvControl.shutdown(app)) 1 else 0
        // Cache unduhan TikTok dibersihkan setelah mpv berhenti, supaya tidak menghapus berkas yang sedang diputar.
        val cleared = try { StreamCache.clear(app) } catch (e: Exception) { 0 }
        val mem = MemoryRefresh.run(releaseResolver = true)
        AppLog.log("system", LogLevel.Info, "refresh", "mpv: dihentikan=$stopped dimatikan=$killed cache=$cleared ${MemoryRefresh.describe(mem)}")
        return RefreshReport(id, stopped = stopped, killed = killed, note = MemoryRefresh.describe(mem))
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
        try { TerminalService.requestStop(app) } catch (e: Exception) { }
        val r = AndroidProcessOps.reaper().reapOwn()
        AppLog.log(
            "system", LogLevel.Info, "refresh",
            "terminal: sesi=$sessions proses=${r.found} bersih=${r.terminated} paksa=${r.killed} tersisa=${r.remaining}"
        )
        return RefreshReport(
            id, stopped = sessions, killed = r.total, remaining = r.remaining,
            note = if (r.remaining > 0) "${r.remaining} proses tidak mau mati" else null
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
