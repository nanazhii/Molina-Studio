package com.molina.studio.terminal

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.molina.core.api.PasteResult
import com.molina.core.api.RootfsState
import com.molina.core.api.TerminalGateway
import com.molina.terminal.TerminalSession
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Implementasi kontrak [TerminalGateway]. Satu-satunya jembatan dari fitur lain ke sesi, rootfs,
 * dan setelan terminal. Perubahan perilaku terminal cukup dilakukan di sini dan di paket terminal.
 */
class TerminalGatewayImpl(context: Context) : TerminalGateway {
    private val app: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override val rootfsState: StateFlow<RootfsState> get() = RootfsManager.state
    override fun rootfsDir(): File = RootfsManager.rootfsDir(app)
    override fun refreshRootfs() { RootfsManager.refresh(app) }

    override val workspaceDir: String get() = TerminalSessions.WORKSPACE_DIR
    override val maxSessions: Int get() = TerminalSessions.MAX_SESSIONS
    override val sessionCount: Int get() = TerminalSessions.sessions.size
    override fun canAddSession(): Boolean = TerminalSessions.canAdd()
    override fun hasActiveSession(): Boolean = TerminalSessions.currentSession?.emulator != null
    override fun isFullscreenProgramActive(): Boolean =
        TerminalSessions.currentSession?.emulator?.isAlternateBufferActive == true
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

    override fun typeCommand(command: String, inNewSession: Boolean): Boolean {
        val session = (if (inNewSession) TerminalSessions.newSession(app) else TerminalSessions.ensureSession(app))
            ?: return false
        typeWhenReady(session, command)
        return true
    }

    /**
     * Sesi baru baru benar-benar punya shell setelah view terminal dipasang (pid > 0 dan emulator ada),
     * jadi tunggu dulu. Mengetik sebelum prompt aman: PTY menahan masukan sampai bash membacanya.
     * "\r" = tombol Enter.
     */
    private fun typeWhenReady(session: TerminalSession, command: String) {
        val bytes = (command + "\r").toByteArray(Charsets.UTF_8)
        var polls = 0
        val task = object : Runnable {
            override fun run() {
                if (!session.isRunning) return
                if (session.pid > 0 && session.emulator != null) {
                    main.postDelayed({ if (session.isRunning) session.write(bytes, 0, bytes.size) }, SETTLE_MS)
                    return
                }
                if (++polls < MAX_POLLS) main.postDelayed(this, POLL_MS)
            }
        }
        main.post(task)
    }

    override fun pasteRaw(text: String): Boolean {
        val em = TerminalSessions.currentSession?.emulator ?: return false
        em.paste(text)
        return true
    }

    override fun paste(text: String): PasteResult {
        val em = TerminalSessions.currentSession?.emulator ?: return PasteResult.NO_SESSION
        if (em.isAlternateBufferActive) return PasteResult.FULLSCREEN_PROGRAM
        em.paste(text)
        return PasteResult.OK
    }

    override val fullStorageAccess: Boolean get() = TerminalPrefs.fullStorageAccess

    private companion object {
        const val SETTLE_MS = 250L
        const val POLL_MS = 100L
        const val MAX_POLLS = 150 // 15 dtk menunggu shell siap
    }
}
