package com.molina.studio.stream

import android.content.Context
import com.molina.core.common.YtdlpCommand
import com.molina.studio.terminal.RootfsManager
import java.io.File

/** Hasil pemeriksaan prasyarat yt-dlp di rootfs Debian. */
internal sealed interface YtdlpStatus {
    /** [guestBinary] jalur di dalam guest (mis. /usr/local/bin/yt-dlp). */
    data class Ready(val guestBinary: String) : YtdlpStatus
    data object NoRootfs : YtdlpStatus
    data object NoPython : YtdlpStatus
    data object NoYtdlp : YtdlpStatus

    /** Pesan untuk log/UI; null bila siap. */
    fun hint(): String? = when (this) {
        is Ready -> null
        NoRootfs -> "Rootfs Debian belum dipasang. Siapkan di Molina Terminal dulu."
        NoPython -> "python3 belum ada di Debian. ${YtdlpCommand.INSTALL_PYTHON_HINT}"
        NoYtdlp -> YtdlpCommand.HINT_INSTALL
    }
}

internal object YtdlpLocator {
    /** Berkas di host untuk sebuah jalur guest (rootfs adalah folder biasa di filesDir). */
    fun hostFile(ctx: Context, guestPath: String): File =
        File(RootfsManager.rootfsDir(ctx), guestPath.trimStart('/'))

    fun status(ctx: Context): YtdlpStatus {
        val root = RootfsManager.rootfsDir(ctx)
        if (!root.isDirectory || !File(root, "bin").exists()) return YtdlpStatus.NoRootfs
        if (!hostFile(ctx, YtdlpCommand.GUEST_PYTHON).exists()) return YtdlpStatus.NoPython
        val bin = YtdlpCommand.GUEST_BINARIES.firstOrNull { hostFile(ctx, it).isFile }
            ?: return YtdlpStatus.NoYtdlp
        return YtdlpStatus.Ready(bin)
    }
}
