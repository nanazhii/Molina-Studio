package com.molina.studio.system

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.molina.core.api.AppLog
import com.molina.core.api.LogLevel
import com.molina.core.system.RefreshReport
import com.molina.core.system.Refreshable
import com.molina.core.system.Refreshers
import com.molina.studio.terminal.TerminalService
import com.molina.studio.terminal.TerminalSessions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.molina.studio.common.AndroidProcessOps

/**
 * Penyegaran sistem: mematikan proses latar per modul sampai akar. Modul mendaftar ke [Refreshers];
 * layar Pengaturan hanya membaca registri itu. Modul Code (Fase 6) cukup memanggil
 * `Refreshers.register(...)` dengan id "code".
 */
internal object SystemRefresh {
    /** [toolRun] disuntik dari akar app (settings memiliki ToolRunHub) agar :system tidak bergantung ke :settings. */
    /** Refreshable milik modul lain disuntik dari akar app supaya :system tidak mengimpor cinema/stream/code/settings. Urutan registrasi tetap. */
    fun install(app: Context, mpv: Refreshable, download: Refreshable, toolRun: Refreshable, code: Refreshable) {
        Refreshers.register(mpv)
        // [REFRESH-ALL] Urutan penting: pembatal logika (unduhan, paket, skrip) lebih dulu supaya antrean tidak
        // memunculkan proses baru; Terminal TERAKHIR sebagai sapuan akhir semua proses ber-UID aplikasi.
        Refreshers.register(download) // [T21] disuntik: :system tidak impor :download
        Refreshers.register(toolRun)
        Refreshers.register(code)
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

    /** Tunggu sampai [cond] benar atau [timeoutMs] habis; true bila terpenuhi. Panggil dari thread latar. */
    fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!cond() && System.nanoTime() < deadline) {
            try { Thread.sleep(50) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        return cond()
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
