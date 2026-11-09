package com.molina.studio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.molina.core.common.AppLog
import com.molina.core.common.LogLevel
import com.molina.core.common.MolinaPaths
import com.molina.core.storage.AndroidMolinaPaths
import com.molina.studio.terminal.BundledScripts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Satu-satunya instance [MolinaPaths] aplikasi (service locator manual di :app). */
object AppPaths {
    @Volatile private var impl: AndroidMolinaPaths? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)
    @Volatile private var ready = false

    fun get(context: Context): MolinaPaths = instance(context)

    private fun instance(context: Context): AndroidMolinaPaths =
        impl ?: synchronized(this) {
            impl ?: AndroidMolinaPaths(context.applicationContext, com.molina.studio.terminal.TerminalSessions.WORKSPACE_DIR).also { impl = it } // [WORKSPACE-NEUTRAL]
        }

    /** Aman dipanggil berulang (tiap onResume); membuat folder data setelah izin storage ada. */
    fun ensureIfPermitted(context: Context) {
        if (ready) return
        val perm = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (context.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) return
        if (!busy.compareAndSet(false, true)) return
        val paths = instance(context)
        scope.launch {
            val ok = try { paths.ensureDirs() } catch (e: Exception) { false }
            ready = ok
            if (ok) {
                BundledScripts.install(context.applicationContext)
                com.molina.studio.code.ScriptStore.syncFromFolder() // [SCRIPT-FOLDER] daftar Script Execution mengikuti folder Scripts sejak awal
            }
            busy.set(false)
            val level = if (ok) LogLevel.Info else LogLevel.Warn
            AppLog.log("sistem", level, "AppPaths", "ensureDirs=$ok appRoot=${paths.appRoot.path}")
        }
    }
}
