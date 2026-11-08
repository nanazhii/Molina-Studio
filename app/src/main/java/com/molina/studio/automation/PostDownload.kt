package com.molina.studio.automation

import android.content.Context
import android.os.Environment
import com.molina.core.common.AppLog
import com.molina.core.common.DownloadItemStatus
import com.molina.core.common.DownloadPaths
import com.molina.core.common.DownloadSessionState
import com.molina.core.common.LogLevel
import com.molina.studio.cinema.CinemaRecent
import java.io.File

/**
 * Aksi setelah unduhan selesai: berkas media yang berhasil diunduh ditambahkan ke "Terakhir diputar" Molina Cinema
 * (satu ketukan "Lanjutkan" di beranda Cinema). Hanya bila sakelarnya menyala; tanpa notifikasi tambahan.
 */
object PostDownload {
    private val MEDIA = setOf("mp4", "mkv", "webm", "mov", "m4v", "mp3", "m4a", "opus", "flac", "ogg", "wav", "aac")

    fun onFinished(ctx: Context, session: DownloadSessionState) {
        try {
            AutoPrefs.init(ctx)
            if (!AutoPrefs.state.value.addToCinema) return
            val base = Environment.getExternalStorageDirectory().absolutePath
            var added = 0
            // Dibalik agar item pertama berakhir paling atas di riwayat.
            for (item in session.items.asReversed()) {
                if (item.status != DownloadItemStatus.DONE) continue
                val media = item.files.lastOrNull { it.name.substringAfterLast('.', "").lowercase() in MEDIA } ?: continue
                val host = DownloadPaths.toAndroid(media.path, base)?.let { File(it) } ?: continue
                if (!host.isFile) continue
                CinemaRecent.add(ctx, host.absolutePath, item.title?.takeIf { it.isNotBlank() } ?: host.name)
                added++
            }
            if (added > 0) AppLog.log("system", LogLevel.Info, "auto", "unduhan -> riwayat Cinema: $added berkas")
        } catch (e: Exception) {
            AppLog.log("system", LogLevel.Warn, "auto", "aksi pasca-unduh gagal: ${e.javaClass.simpleName}")
        }
    }
}
