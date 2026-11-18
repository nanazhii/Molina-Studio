package com.molina.core.api

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Kontrak layanan terminal untuk fitur lain (code, settings, cinema, log, stream, system).
 * Hanya tipe JVM murni: tidak ada Context dan tidak ada tipe emulator. Implementasi hidup di
 * modul terminal dan didaftarkan lewat [MolinaServices] saat aplikasi mulai.
 */
interface TerminalGateway {
    // Rootfs Debian
    val rootfsState: StateFlow<RootfsState>
    fun rootfsDir(): File
    fun refreshRootfs()

    // Ruang kerja dan sesi
    val workspaceDir: String
    val maxSessions: Int
    val sessionCount: Int
    fun canAddSession(): Boolean
    /** True bila ada sesi aktif dengan emulator siap. */
    fun hasActiveSession(): Boolean
    /** Izin penyimpanan penuh milik sesi aktif; null bila tidak ada sesi. */
    fun activeFullAccess(): Boolean?
    fun ensureSession()
    /** Buat sesi baru; false bila batas sesi tercapai. */
    fun newSession(guestCwd: String? = null, label: String? = null): Boolean
    /** Pilih sesi yang dimulai di [guestPath]; false bila tidak ada. */
    fun selectByStartDir(guestPath: String): Boolean
    fun killAll()

    // Input ke terminal aktif
    fun send(text: String)
    /** Tempel [text] ke sesi aktif; [PasteResult] menjelaskan bila ditolak. */
    fun paste(text: String): PasteResult

    // Pengaturan yang dibaca lintas fitur
    val fullStorageAccess: Boolean
}

enum class PasteResult { OK, NO_SESSION, FULLSCREEN_PROGRAM }
