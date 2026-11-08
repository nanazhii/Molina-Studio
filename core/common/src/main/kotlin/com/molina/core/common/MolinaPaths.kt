package com.molina.core.common

import java.io.File

/**
 * Subfolder data aplikasi di bawah [MolinaPaths.appRoot] (/sdcard/Molina).
 * [folderName] boleh bertingkat (mis. "Media/Video").
 */
enum class MolinaDir(val folderName: String) {
    Downloads("Downloads"),
    Video("Media/Video"),
    Audio("Media/Audio"),
    Iptv("Media/IPTV"),
    Scripts("Scripts"),
    Backup("Backup"),
    Documents("Documents"),
    Export("Documents/Export"),
    Crash("Crash"),
    TerminalLog("Logs/Terminal"),
}

/**
 * Kontrak lokasi file. Kotlin murni (tanpa Context); implementasi Android ada di :core:storage.
 * Folder aplikasi ([appRoot]) terpisah total dari folder project ([devProject]).
 */
interface MolinaPaths {
    /** Akar data aplikasi (/sdcard/Molina). Terlihat di file manager, tidak privat. */
    val appRoot: File

    /** Folder kerja di storage bersama (di-bind ke Debian secara default). Namanya ditentukan build; lihat AndroidMolinaPaths. */
    val devProject: File

    /** Penyimpanan privat aplikasi (filesDir). Satu-satunya tempat untuk rahasia. */
    val privateRoot: File

    /** Alias lama = [devProject]. Dipertahankan sampai pemakai (MainActivity) dipindah ke appRoot. Subfolder Workspace sudah dihapus. */
    val workspace: File get() = devProject

    fun dir(kind: MolinaDir): File = File(appRoot, kind.folderName)

    companion object {
        const val APP_FOLDER = "Molina"
        const val DEV_FOLDER = "Molina" // [WORKSPACE-IN-MOLINA] sama dengan APP_FOLDER: tidak ada folder kerja terpisah
    }
}
