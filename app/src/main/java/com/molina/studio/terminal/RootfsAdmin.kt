package com.molina.studio.terminal

import android.content.Context
import com.molina.core.common.LogLevel
import com.molina.core.common.MolinaDir
import com.molina.studio.AppPaths
import com.molina.studio.code.ProotScriptExecutor
import com.molina.studio.log.LogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RootfsOp(val label: String) {
    Backup("Mencadangkan rootfs"),
    Restore("Memulihkan rootfs"),
    Reset("Mengatur ulang rootfs"),
}

sealed interface RootfsAdminState {
    data object Idle : RootfsAdminState
    data class Running(val op: RootfsOp, val phase: String, val percent: Int) : RootfsAdminState
    data class Finished(val op: RootfsOp, val ok: Boolean, val message: String) : RootfsAdminState
}

data class RootfsBackupFile(val file: File, val bytes: Long, val modified: Long)

/**
 * Pembungkus [RootfsBackup] untuk UI (TERM-ROOTFS2): backup, restore, dan reset rootfs dengan [StateFlow].
 * Urutan wajib: tolak bila ada pekerjaan proot lain -> masuk mode Maintenance (UI Terminal terkunci)
 * -> hentikan semua sesi -> jalankan operasi -> keluar Maintenance. Hasil tetap tersimpan di [state]
 * sampai [dismissResult], jadi dialog boleh ditutup tanpa kehilangan hasil.
 */
object RootfsAdmin {
    private const val TAG = "rootfs-admin"
    private const val MIN_FREE_BACKUP = 128L * 1024 * 1024
    private val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<RootfsAdminState>(RootfsAdminState.Idle)
    val state: StateFlow<RootfsAdminState> = _state.asStateFlow()
    @Volatile private var control: BackupControl? = null

    fun backupDir(ctx: Context): File = File(AppPaths.get(ctx).dir(MolinaDir.Backup), "rootfs")

    /** Cadangan rootfs yang ada (terbaru dulu). Sisa ".part" tidak ikut. */
    fun listBackups(ctx: Context): List<RootfsBackupFile> =
        (backupDir(ctx).listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".tar.gz") }
            .map { RootfsBackupFile(it, it.length(), it.lastModified()) }
            .sortedByDescending { it.modified }

    fun cancel() { control?.cancel() }

    fun dismissResult() {
        if (_state.value is RootfsAdminState.Finished) _state.value = RootfsAdminState.Idle
    }

    fun backup(ctx: Context) = runOp(ctx, RootfsOp.Backup) { app, ctl ->
        val rootfs = RootfsManager.rootfsDir(app)
        val dir = backupDir(app)
        if (!dir.isDirectory && !dir.mkdirs()) throw RootfsBackupException("Folder cadangan tidak bisa dibuat: ${dir.path}. Periksa izin penyimpanan")
        if (dir.usableSpace < MIN_FREE_BACKUP) throw RootfsBackupException("Ruang kosong di tujuan kurang dari 128 MB")
        val debianVer = runCatching {
            File(rootfs, "etc/debian_version").takeIf { it.isFile }?.readLines()?.firstOrNull()
        }.getOrNull()
        val molinaVer = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
        val dest = File(dir, backupFileName(debianVer, molinaVer, STAMP.format(Date())))
        stopSessions(app)
        val r = RootfsBackup.export(rootfs, dest, AndroidRootfsPosix, ctl) { ph, pct -> progress(RootfsOp.Backup, ph, pct) }
        "Cadangan tersimpan: ${dest.name} (${r.entries} entri, ${r.skipped} dilewati)"
    }

    fun restore(ctx: Context, archive: File) = runOp(ctx, RootfsOp.Restore) { app, ctl ->
        stopSessions(app)
        RootfsBackup.restore(archive, RootfsManager.rootfsDir(app), AndroidRootfsPosix, ctl) { ph, pct -> progress(RootfsOp.Restore, ph, pct) }
        RootfsManager.markReady(app)
        "Rootfs dipulihkan dari ${archive.name}"
    }

    /** Hapus rootfs tanpa mengikuti symlink ([SafeTree]); pemasangan ulang lewat panel Setup Terminal. */
    fun reset(ctx: Context) = runOp(ctx, RootfsOp.Reset) { app, _ ->
        stopSessions(app)
        progress(RootfsOp.Reset, BackupPhase.Swap, 0)
        RootfsManager.clearMarker(app)
        if (!SafeTree.delete(RootfsManager.rootfsDir(app))) throw RootfsBackupException("Sebagian berkas rootfs gagal dihapus")
        "Rootfs dihapus. Buka Terminal untuk memasang ulang"
    }

    /** [TERM-ROOTFS3] Nama cadangan memuat versi: molina-rootfs-debian13.1-molina1.2.3-20261029-101500.tar.gz */
    internal fun backupFileName(debianVersion: String?, molinaVersion: String?, stamp: String): String {
        fun clean(v: String?) = (v ?: "").trim().replace(Regex("[^A-Za-z0-9.\\-]"), "_").take(16).ifEmpty { "x" }
        return "molina-rootfs-debian${clean(debianVersion)}-molina${clean(molinaVersion)}-$stamp.tar.gz"
    }

    private fun progress(op: RootfsOp, phase: BackupPhase, pct: Int) {
        val label = when (phase) {
            BackupPhase.Scan -> "Memindai"
            BackupPhase.Write -> "Menulis arsip"
            BackupPhase.Inspect -> "Memeriksa arsip"
            BackupPhase.Extract -> "Mengekstrak"
            BackupPhase.Swap -> "Menukar rootfs"
        }
        _state.value = RootfsAdminState.Running(op, label, pct.coerceIn(0, 100))
    }

    private fun busyReason(): String? {
        // Language server Code hanya proses latar: dihentikan, bukan menghalangi.
        ProotScriptExecutor.cancelActive(ProotScriptExecutor.Lane.LSP)
        val busy = ProotScriptExecutor.Lane.values().filter { it != ProotScriptExecutor.Lane.LSP && ProotScriptExecutor.isRunning(it) }
        return if (busy.isEmpty()) null else "Masih ada pekerjaan Debian berjalan (${busy.joinToString { it.name.lowercase() }}). Tunggu selesai atau batalkan dulu"
    }

    private fun runOp(ctx: Context, op: RootfsOp, block: suspend (Context, BackupControl) -> String) {
        val app = ctx.applicationContext
        synchronized(this) {
            if (_state.value is RootfsAdminState.Running) return
            RootfsManager.refresh(app)
            if (op == RootfsOp.Backup && RootfsManager.state.value !is RootfsState.Ready) {
                _state.value = RootfsAdminState.Finished(op, false, "Rootfs belum siap, tidak ada yang bisa dicadangkan")
                return
            }
            busyReason()?.let { _state.value = RootfsAdminState.Finished(op, false, it); return }
            if (!RootfsManager.beginMaintenance(op.label)) {
                _state.value = RootfsAdminState.Finished(op, false, "Rootfs sedang dipasang atau dikelola")
                return
            }
            _state.value = RootfsAdminState.Running(op, "Menyiapkan", 0)
        }
        val ctl = BackupControl().also { control = it }
        scope.launch {
            LogBus.log("terminal", LogLevel.Info, TAG, "${op.label}: mulai")
            val result = try {
                RootfsAdminState.Finished(op, true, block(app, ctl))
            } catch (e: BackupCancelled) {
                RootfsAdminState.Finished(op, false, "Dibatalkan")
            } catch (e: Exception) {
                LogBus.log("terminal", LogLevel.Warn, TAG, "${op.label} gagal: ${e.message}")
                RootfsAdminState.Finished(op, false, e.message ?: e.javaClass.simpleName)
            } finally {
                control = null
                RootfsManager.endMaintenance(app)
            }
            LogBus.log("terminal", LogLevel.Info, TAG, "${op.label}: ${if (result.ok) "selesai" else "tidak selesai (${result.message})"}")
            _state.value = result
        }
    }

    /** killAll() menyentuh state Compose: wajib di main thread. Tidak memakai closeSession (bisa memicu AppExit). */
    private suspend fun stopSessions(app: Context) {
        withContext(Dispatchers.Main) {
            TerminalSessions.killAll()
            TerminalService.requestStop(app)
        }
        delay(600) // beri waktu proses proot melepas berkas sebelum diekspor/ditukar
    }
}
