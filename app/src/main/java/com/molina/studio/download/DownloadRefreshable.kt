package com.molina.studio.download

import com.molina.core.api.AppLog
import com.molina.core.api.LogLevel
import com.molina.core.system.RefreshReport
import com.molina.core.system.Refreshable
import com.molina.studio.system.SystemRefresh

/** [REFRESH-ALL] Unduhan (DownloadRunner + DownloadService). Dibatalkan sebelum Terminal menyapu sisa proses. */
internal class DownloadRefreshable : Refreshable {
    override val id = "download"
    override val label = "Unduhan"

    override fun activeCount(): Int = if (DownloadRunner.isRunning()) 1 else 0

    override fun refresh(): RefreshReport {
        if (!DownloadRunner.isRunning()) return RefreshReport(id)
        DownloadRunner.cancel() // berkas .part dipertahankan agar bisa dilanjutkan
        val gone = SystemRefresh.waitUntil(2500L) { !DownloadRunner.isRunning() }
        AppLog.log("system", if (gone) LogLevel.Info else LogLevel.Warn, "refresh", "download: dibatalkan=${if (gone) 1 else 0}")
        return RefreshReport(
            id, stopped = if (gone) 1 else 0, remaining = if (gone) 0 else 1,
            note = if (gone) null else "Unduhan belum berhenti; sisa proses disapu Terminal."
        )
    }
}
