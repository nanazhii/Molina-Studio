package com.molina.studio.code

import com.molina.core.api.AppLog
import com.molina.core.api.LogLevel
import com.molina.core.system.RefreshReport
import com.molina.core.system.Refreshable

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
