package com.molina.studio.cmd

import android.content.Context
import android.os.SystemClock
import com.molina.core.common.LogLevel
import com.molina.core.common.MolinaPaths
import com.molina.studio.log.LogBus
import com.molina.studio.terminal.TerminalPrefs
import com.molina.studio.terminal.TerminalSessions
import java.util.concurrent.atomic.AtomicLong

/**
 * [CLI-SCOPE] Batas aman untuk aksi bridge CLI yang menyentuh berkas atau membuka UI.
 * Semua proses di Debian berbagi UID aplikasi, jadi tanpa batas ini skrip di guest bisa menjangkau berkas yang
 * tidak di-bind ke guest (saat Akses penuh mati) lewat share/open/play, dan membanjiri UI.
 */
internal object CliScope {
    private const val TAG = "CliScope"
    private const val MIN_GAP_MS = 300L
    private val last = AtomicLong(0)

    /** Null bila [host] boleh dipakai; selain itu balasan galat siap kirim. [shown] = path asli dari guest. */
    fun deny(ctx: Context, host: String, storageRoot: String, shown: String): String? {
        val app = ctx.applicationContext
        TerminalPrefs.init(app)
        val allowed = listOf(TerminalSessions.WORKSPACE_DIR, MolinaPaths.APP_FOLDER)
        if (CommandProtocol.inScope(host, storageRoot, TerminalPrefs.fullStorageAccess, allowed)) return null
        LogBus.log("sistem", LogLevel.Warn, TAG, "Path di luar folder yang terlihat di Debian ditolak")
        return CommandProtocol.err(
            "scope",
            "path di luar folder yang terlihat di Debian: $shown. Aktifkan Akses penuh storage di Setelan > Terminal bila memang perlu"
        )
    }

    /** True bila aksi ini terlalu cepat setelah aksi sebelumnya (bersama untuk play, open, share). */
    fun rateLimited(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val prev = last.get()
        return !CommandProtocol.gapOk(prev, now, MIN_GAP_MS) || !last.compareAndSet(prev, now)
    }
}
