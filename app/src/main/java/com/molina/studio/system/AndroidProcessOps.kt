package com.molina.studio.system

import android.os.Process
import android.system.Os
import android.system.OsConstants
import com.molina.core.common.ProcFs
import com.molina.core.common.ProcInfo
import com.molina.core.common.ProcessOps
import com.molina.core.common.ProcessReaper

/** [ProcessOps] Android: daftar dari /proc, sinyal lewat Os.kill. */
internal object AndroidProcessOps : ProcessOps {
    override fun list(): List<ProcInfo> = ProcFs.list()
    override fun term(pid: Int) { Os.kill(pid, OsConstants.SIGTERM) }
    override fun kill(pid: Int) { Os.kill(pid, OsConstants.SIGKILL) }

    /** Reaper untuk UID aplikasi ini; `logcat` milik LogcatReporter dilindungi. */
    fun reaper(): ProcessReaper = ProcessReaper(this, Process.myPid(), Process.myUid())
}
