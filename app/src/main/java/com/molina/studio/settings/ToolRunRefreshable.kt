package com.molina.studio.settings

import com.molina.core.api.AppLog
import com.molina.core.api.LogLevel
import com.molina.core.system.RefreshReport
import com.molina.core.system.Refreshable
import com.molina.studio.system.SystemRefresh

/** [REFRESH-ALL] Proses Paket (pasang/hapus/apt/yt-dlp lewat ToolRunHub + ToolRunService). */
internal class ToolRunRefreshable : Refreshable {
    override val id = "toolrun"
    override val label = "Proses Paket"

    private fun running(): Boolean = ToolRunHub.session.value?.running == true

    override fun activeCount(): Int = if (running()) 1 else 0

    override fun refresh(): RefreshReport {
        if (!running()) return RefreshReport(id)
        ToolRunHub.cancel()
        val gone = SystemRefresh.waitUntil(2500L) { !running() }
        AppLog.log("system", if (gone) LogLevel.Info else LogLevel.Warn, "refresh", "toolrun: dibatalkan=${if (gone) 1 else 0}")
        return RefreshReport(
            id, stopped = if (gone) 1 else 0, remaining = if (gone) 0 else 1,
            note = if (gone) null else "Proses Paket belum berhenti; sisa proses disapu Terminal."
        )
    }
}
