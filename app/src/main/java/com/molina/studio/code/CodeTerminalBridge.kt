package com.molina.studio.code

import com.molina.studio.terminal.TerminalActions
import com.molina.studio.terminal.TerminalSessions

/**
 * Jembatan Code -> Terminal. Teks ditempel lewat emulator (bracketed paste bila shell mendukung),
 * sehingga teks banyak baris tidak langsung dieksekusi. [execute] true = tekan Enter setelahnya.
 * Mengembalikan pesan galat, atau null bila berhasil (sekaligus pindah ke tab Terminal).
 */
internal object CodeTerminalBridge {
    private const val MAX_CHARS = 20_000

    /** [CODE-PYRUN] Jalur guest Debian untuk berkas host (lingkup storage sama dengan Terminal), atau null bila tak terlihat. */
    fun guestPath(f: java.io.File): String? = com.molina.code.preview.GuestPaths.of(
        f.absolutePath,
        android.os.Environment.getExternalStorageDirectory().absolutePath,
        TerminalSessions.WORKSPACE_DIR,
        com.molina.studio.terminal.TerminalPrefs.fullStorageAccess,
        listOf(com.molina.core.common.MolinaPaths.APP_FOLDER)
    )

    fun deliver(text: String, execute: Boolean): String? {
        if (text.length > MAX_CHARS) return "Teks terlalu panjang untuk dikirim ke Terminal"
        val em = TerminalSessions.currentSession?.emulator
            ?: return "Terminal belum siap. Buka Settings > Terminal sekali dulu."
        if (em.isAlternateBufferActive) return "Terminal sedang menjalankan program layar penuh"
        em.paste(text)
        if (execute) TerminalActions.send("\r")
        ScriptNav.openTerminal = true
        return null
    }
}
