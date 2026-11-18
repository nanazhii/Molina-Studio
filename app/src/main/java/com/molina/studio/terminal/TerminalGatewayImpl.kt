package com.molina.studio.terminal

import android.content.Context
import com.molina.core.api.PasteResult
import com.molina.core.api.RootfsState
import com.molina.core.api.TerminalGateway
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Implementasi kontrak [TerminalGateway]. Satu-satunya jembatan dari fitur lain ke sesi, rootfs,
 * dan setelan terminal. Perubahan perilaku terminal cukup dilakukan di sini dan di paket terminal.
 */
class TerminalGatewayImpl(context: Context) : TerminalGateway {
    private val app: Context = context.applicationContext

    override val rootfsState: StateFlow<RootfsState> get() = RootfsManager.state
    override fun rootfsDir(): File = RootfsManager.rootfsDir(app)
    override fun refreshRootfs() { RootfsManager.refresh(app) }

    override val workspaceDir: String get() = TerminalSessions.WORKSPACE_DIR
    override val maxSessions: Int get() = TerminalSessions.MAX_SESSIONS
    override val sessionCount: Int get() = TerminalSessions.sessions.size
    override fun canAddSession(): Boolean = TerminalSessions.canAdd()
    override fun hasActiveSession(): Boolean = TerminalSessions.currentSession?.emulator != null
    override fun activeFullAccess(): Boolean? = TerminalSessions.fullAccessOf(TerminalSessions.currentSession)
    override fun ensureSession() { TerminalSessions.ensureSession(app) }
    override fun newSession(guestCwd: String?, label: String?): Boolean =
        TerminalSessions.newSession(app, guestCwd, label) != null

    override fun selectByStartDir(guestPath: String): Boolean {
        val idx = TerminalSessions.indexOfStartDir(guestPath)
        if (idx < 0) return false
        TerminalSessions.select(idx)
        return true
    }

    override fun killAll() { TerminalSessions.killAll() }

    override fun send(text: String) { TerminalActions.send(text) }

    override fun paste(text: String): PasteResult {
        val em = TerminalSessions.currentSession?.emulator ?: return PasteResult.NO_SESSION
        if (em.isAlternateBufferActive) return PasteResult.FULLSCREEN_PROGRAM
        em.paste(text)
        return PasteResult.OK
    }

    override val fullStorageAccess: Boolean get() = TerminalPrefs.fullStorageAccess
}
