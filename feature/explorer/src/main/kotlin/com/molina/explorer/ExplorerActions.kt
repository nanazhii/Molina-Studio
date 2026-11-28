package com.molina.explorer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.molina.core.api.LogLevel
import com.molina.core.fileops.FileOpOutcome
import com.molina.core.fileops.FileOps
import com.molina.core.library.TrashBin
import com.molina.core.library.TrashItem
import com.molina.core.log.LogBus
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [FEATURE-EXPLORER] Mesin operasi berkas Penjelajah: cek izin tulis, jalankan di IO, progres, batal, pesan hasil,
 * hapus (permanen atau Recycle Bin) dan urungkan. Dipisah dari composable Win7OpenDialog supaya UI hanya menyajikan state.
 *
 * Hook ke UI (disuntik, tanpa mengenal state UI): [onClearSelection] dipanggil saat operasi sukses,
 * [onReload] dipanggil setelah operasi selesai (sukses atau gagal) untuk memuat ulang daftar.
 * [volumes] dan [protectedPaths] berasal dari StorageVolumes / ProtectedPaths (:core:library).
 */
internal class ExplorerActions(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val volumes: List<File>,
    private val protectedPaths: Set<String>,
    private val onClearSelection: () -> Unit,
    private val onReload: () -> Unit,
) {
    var busy by mutableStateOf(false)
        private set
    var progress by mutableStateOf<Float?>(null)
        private set
    var message by mutableStateOf("")
        private set
    var messageOk by mutableStateOf(false)
        private set
    var undo by mutableStateOf<List<TrashItem>?>(null)
        private set

    private val cancelFlag = AtomicBoolean(false)

    fun say(text: String, ok: Boolean = false) { message = text; messageOk = ok }

    fun dismissMessage() { message = "" }

    /** Dipakai saat pindah folder: pesan dan tawaran Urungkan tidak boleh terbawa ke folder lain. */
    fun resetFeedback() { message = ""; undo = null }

    fun cancel() { cancelFlag.set(true) }

    fun runOp(op: (FileOps.Ctl) -> String, onDone: () -> Unit = {}, onFail: (Throwable) -> Unit = {}) {
        if (busy) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            say(FileOpOutcome.NO_WRITE_PERMISSION)
            return
        }
        busy = true
        progress = null
        cancelFlag.set(false)
        undo = null
        say("Memproses\u2026", true)
        scope.launch {
            val ctl = FileOps.Ctl(cancelFlag) { p -> progress = p }
            val r = withContext(Dispatchers.IO) { runCatching { op(ctl) } }
            busy = false
            progress = null
            r.onSuccess { say(it, true); onDone(); onClearSelection(); onReload() }
                .onFailure {
                    say(FileOpOutcome.failureText(it))
                    if (FileOpOutcome.shouldLog(it)) LogBus.log("penjelajah", LogLevel.Error, "FileOps", FileOpOutcome.trace(it))
                    onReload()
                    onFail(it)
                }
        }
    }

    /** Hapus permanen, atau pindahkan ke Recycle Bin dengan tawaran Urungkan. Jalur terlindung ditolak oleh FileOps/TrashBin. */
    fun delete(files: List<File>, permanent: Boolean) {
        if (permanent) {
            runOp(op = { ctl -> "Dihapus permanen: " + FileOps.deleteAll(files, protectedPaths, ctl) + " item" })
        } else {
            val moved = arrayOfNulls<List<TrashItem>>(1)
            runOp(
                op = { _ ->
                    val r = TrashBin.trash(files, volumes, protectedPaths)
                    moved[0] = r
                    "Dipindahkan ke Recycle Bin: " + r.size + " item"
                },
                onDone = { undo = moved[0] }
            )
        }
    }

    /** Urungkan pemindahan ke Recycle Bin terakhir (no-op bila tidak ada). */
    fun undoDelete() {
        val items = undo ?: return
        undo = null
        message = ""
        runOp(op = { _ -> "Dipulihkan: " + TrashBin.restore(items, volumes).size + " item" })
    }
}
