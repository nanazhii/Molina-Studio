package com.molina.studio

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.system.Os
import android.system.OsConstants
import com.molina.core.common.ExitHooks
import com.molina.studio.terminal.TerminalSessions
import java.io.File
import java.lang.ref.WeakReference

/**
 * Pintu keluar tunggal aplikasi: tidak boleh ada proses Molina yang tersisa.
 *
 * Urutan: hook modul (mpv, editor, ...) -> matikan sesi terminal -> sapu SEMUA proses
 * ber-UID aplikasi (proot, guest Debian, anak proses yatim, helper mpv) -> tutup Activity
 * dan hapus dari recents -> bunuh proses aplikasi sendiri.
 *
 * Modul baru wajib mendaftarkan pembersihan, misalnya:
 *   ExitHooks.register("mpv") { MPVLib.destroy() }   // com.molina.core.common
 */
object AppExit {
    @Volatile private var activityRef: WeakReference<Activity>? = null

    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    private val exiting = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Aman dipanggil dari thread mana pun; hanya panggilan pertama yang dijalankan.
     * delayMs: jeda sebelum mematikan semuanya (mis. agar pesan "Process completed" terbaca).
     */
    fun exit(delayMs: Long = 0L) {
        if (!exiting.compareAndSet(false, true)) return
        Thread({
            if (delayMs > 0) {
                try { Thread.sleep(delayMs) } catch (e: InterruptedException) {}
            }
            ExitHooks.runAll()
            try { TerminalSessions.killAll() } catch (e: Throwable) {}
            repeat(3) {
                sweepOwnProcesses()
                try { Thread.sleep(60) } catch (e: InterruptedException) {}
            }
            val main = Handler(Looper.getMainLooper())
            main.post {
                activityRef?.get()?.finishAndRemoveTask()
                main.postDelayed({ Process.killProcess(Process.myPid()) }, 200)
            }
        }, "molina-exit").start()
    }

    /** SIGKILL ke setiap proses lain milik UID aplikasi ini (dibaca dari /proc). */
    private fun sweepOwnProcesses() {
        val me = Process.myPid()
        val uid = Process.myUid()
        val dirs = File("/proc").listFiles() ?: return
        for (d in dirs) {
            val pid = d.name.toIntOrNull() ?: continue
            if (pid == me) continue
            val line = try {
                File(d, "status").useLines { seq -> seq.firstOrNull { it.startsWith("Uid:") } }
            } catch (e: Exception) {
                null
            } ?: continue
            val realUid = line.substringAfter("Uid:").trim()
                .split(Regex("\\s+")).firstOrNull()?.toIntOrNull() ?: continue
            if (realUid == uid) {
                try { Os.kill(pid, OsConstants.SIGKILL) } catch (e: Exception) {}
            }
        }
    }
}
