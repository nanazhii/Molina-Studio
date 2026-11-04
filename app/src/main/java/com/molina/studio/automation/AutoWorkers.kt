package com.molina.studio.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.molina.core.common.AppLog
import com.molina.core.common.LogLevel
import com.molina.core.common.MolinaDir
import com.molina.studio.AppPaths
import com.molina.studio.packages.ToolChecker
import com.molina.studio.settings.BackupManager
import com.molina.studio.stream.YtdlpLocator
import com.molina.studio.stream.YtdlpStatus
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val DAY_MS = 23 * 3_600_000L // sedikit di bawah 24 jam agar jadwal periodik tidak terlewat

private const val TOOLS_WAIT_MS = 4 * 60_000L

/**
 * [PAKET-WORKER] Periksa versi alat Paket yang belum pernah dicek atau hasilnya lebih tua dari 24 jam. Lokal, tanpa jaringan;
 * dilewati bila Debian belum dipasang. Menunggu sampai selesai agar proses tidak dimatikan di tengah jalan.
 */
private suspend fun checkTools(app: Context) {
    try {
        val rootfsReady = withContext(Dispatchers.IO) { YtdlpLocator.status(app) != YtdlpStatus.NoRootfs }
        if (!rootfsReady || !ToolChecker.requestStale(app, force = true)) return
        val done = withTimeoutOrNull(TOOLS_WAIT_MS) { ToolChecker.state.first { it.checkingId == null } }
        if (done == null) {
            ToolChecker.cancel()
            AppLog.log("system", LogLevel.Warn, "auto", "cek versi alat melewati batas waktu; dihentikan")
        } else {
            AppLog.log("system", LogLevel.Info, "auto", "cek versi alat selesai")
        }
    } catch (e: CancellationException) {
        ToolChecker.cancel()
        throw e
    } catch (e: Exception) {
        AppLog.log("system", LogLevel.Warn, "auto", "cek versi alat gagal: ${e.javaClass.simpleName}")
    }
}

/** Periksa pembaruan apt + yt-dlp (Wi-Fi saja, maks. 1x/24 jam). Hasil hanya mengubah lencana. */
class UpdateCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        AutoPrefs.init(app)
        val st = AutoPrefs.state.value
        if (!st.autoUpdate) return Result.success()
        if (System.currentTimeMillis() - st.lastCheck < DAY_MS) return Result.success()
        try {
            UpdateChecker.checkAll(app)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.log("system", LogLevel.Warn, "auto", "periksa otomatis gagal: ${e.javaClass.simpleName}")
        }
        return Result.success()
    }
}

/** [PAKET-WORKER2] Cek versi alat Paket tiap ~24 jam. Lokal (tanpa jaringan) dan tidak bergantung pada "pembaruan otomatis". */
class ToolCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        checkTools(applicationContext)
        return Result.success()
    }
}

/**
 * Cadangan gabungan harian (BackupManager). Pengaman: salinan sebelumnya disimpan sebagai molina-backup.prev.json,
 * dan bila hasil baru jauh lebih kecil (mis. data aplikasi baru dihapus) berkas lama dipulihkan, bukan ditimpa.
 */
class AutoBackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        AutoPrefs.init(app)
        val st = AutoPrefs.state.value
        if (!st.autoBackup) return Result.success()
        if (System.currentTimeMillis() - st.lastBackup < DAY_MS) return Result.success()
        withContext(Dispatchers.IO) {
            try {
                val dir = AppPaths.get(app).dir(MolinaDir.Backup)
                val f = File(dir, BackupManager.FILE_NAME)
                val prev = File(dir, "molina-backup.prev.json")
                val oldSize = if (f.isFile) f.length() else 0L
                if (oldSize > 0L) f.copyTo(prev, overwrite = true)
                BackupManager.backupAll(app)
                if (oldSize > 2048L && f.length() < oldSize / 2) {
                    prev.copyTo(f, overwrite = true)
                    AppLog.log("system", LogLevel.Warn, "auto", "cadangan baru jauh lebih kecil; berkas lama dipertahankan")
                } else {
                    AutoPrefs.markBackup(app)
                    AppLog.log("system", LogLevel.Info, "auto", "cadangan harian selesai")
                }
            } catch (e: Exception) {
                AppLog.log("system", LogLevel.Warn, "auto", "cadangan harian gagal: ${e.javaClass.simpleName}")
            }
        }
        return Result.success()
    }
}
