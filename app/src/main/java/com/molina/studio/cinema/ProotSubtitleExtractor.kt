package com.molina.studio.cinema

import android.content.Context
import android.content.Intent
import android.os.Environment
import com.molina.cinema.EmbeddedSubtitleExtractor
import com.molina.cinema.ExtractBlock
import com.molina.cinema.SubFormat
import com.molina.cinema.SubtitleExtractCommand
import com.molina.exec.plan.GuestPaths
import com.molina.exec.plan.RunPlan
import com.molina.exec.run.RunListener
import com.molina.exec.run.RunRequest
import com.molina.exec.run.RunResult
import com.molina.core.common.MolinaPaths
import com.molina.studio.MainActivity
import com.molina.studio.script.ProotScriptExecutor
import com.molina.studio.terminal.RootfsManager
import com.molina.studio.terminal.TerminalPrefs
import com.molina.studio.terminal.TerminalSessions
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * [SUBTITLE-EXTRACT] Mengekstrak subtitle tertanam dengan FFmpeg di Debian PRoot (Settings > Paket > Alat).
 * libmpv Molina tidak punya muxer, jadi ekstraksi in-process tidak mungkin. Berjalan di lane SUBTITLE sendiri
 * (tidak memblokir editor Code, Unduhan, atau cek versi alat). Terdaftar di MolinaApp.
 *
 * Lingkup storage sama dengan Terminal: tanpa "Akses penyimpanan penuh" hanya folder Molina yang terlihat di
 * Debian, jadi video di folder lain mendapat pesan yang jelas (bukan galat FFmpeg yang membingungkan).
 */
internal class ProotSubtitleExtractor(context: Context) : EmbeddedSubtitleExtractor {
    private val app = context.applicationContext

    override suspend fun unavailableReason(mediaPath: String): ExtractBlock? = withContext(Dispatchers.IO) {
        val rootfs = RootfsManager.rootfsDir(app)
        when {
            !File(rootfs, "usr/bin/env").exists() -> ExtractBlock(MSG_NO_DEBIAN)
            !ProotScriptExecutor.hasGuestCommand(rootfs, "ffmpeg") -> ExtractBlock(MSG_NO_FFMPEG) { openTools() }
            guestOf(mediaPath) == null -> ExtractBlock(MSG_OUTSIDE_BIND)
            else -> null
        }
    }

    override suspend fun extract(mediaPath: String, streamIndex: Int, target: SubFormat, outPath: String): String? {
        val guestIn = guestOf(mediaPath) ?: return MSG_OUTSIDE_BIND
        val guestOut = guestOf(outPath) ?: return MSG_OUT_NOT_VISIBLE
        val argv = SubtitleExtractCommand.argv(guestIn, streamIndex, target, guestOut) ?: return MSG_BAD_REQUEST
        val req = RunRequest(
            name = "subtitle-extract",
            scriptText = "", // mode argv: tidak dipakai
            plan = RunPlan("ffmpeg", "ffmpeg", "txt", false),
            hostDir = null,
            timeoutMs = TIMEOUT_MS,
            maxOutputChars = 20_000L,
            guestArgv = argv,
            missingHint = MSG_NO_FFMPEG,
        )
        val err = StringBuilder()
        val result: RunResult = suspendCancellableCoroutine { cont ->
            val handle = ProotScriptExecutor(app, ProotScriptExecutor.Lane.SUBTITLE).start(req, object : RunListener {
                override fun onOutput(text: String, isError: Boolean) {
                    if (isError) synchronized(err) { if (err.length < MAX_ERR_CHARS) err.append(text) }
                }

                override fun onFinished(result: RunResult) {
                    if (result.cancelled) runCatching { File(outPath).delete() } // [SUBTITLE-CANCEL] proses sudah mati; buang sisa keluaran
                    if (cont.isActive) cont.resume(result)
                }
            })
            cont.invokeOnCancellation { handle.cancel() }
        }
        val out = File(outPath)
        val failure = when {
            result.startError != null -> result.startError
            result.cancelled -> "Ekstraksi dibatalkan."
            result.timedOut -> "FFmpeg melewati batas waktu (${TIMEOUT_MS / 1000} detik)."
            result.exitCode != 0 -> {
                val line = SubtitleExtractCommand.errorLine(synchronized(err) { err.toString() })
                "FFmpeg gagal (kode ${result.exitCode})" + if (line.isEmpty()) "." else ": $line"
            }
            !out.isFile || out.length() == 0L -> "Hasil ekstraksi kosong. Trek ini mungkin tidak berisi teks."
            else -> null
        }
        if (failure != null) runCatching { out.delete() } // jangan tinggalkan berkas setengah jadi
        return failure
    }

    /** Jalur guest untuk [hostPath] sesuai lingkup storage Terminal, atau null bila tidak terlihat di Debian. */
    private fun guestOf(hostPath: String): String? {
        TerminalPrefs.init(app) // idempoten; pemutar bisa jalan sebelum MainActivity pernah dibuat
        val storage = Environment.getExternalStorageDirectory().absolutePath
        val host = if (hostPath.startsWith("/sdcard/")) storage + hostPath.removePrefix("/sdcard") else hostPath
        return GuestPaths.of(host, storage, TerminalSessions.WORKSPACE_DIR, TerminalPrefs.fullStorageAccess, listOf(MolinaPaths.APP_FOLDER))
    }

    /** Buka Settings > Paket di MainActivity; pemutar tetap hidup di tugas lain. */
    private fun openTools() {
        val i = Intent(app, MainActivity::class.java)
            .putExtra("molina_open_tab", "packages")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { app.startActivity(i) }
    }

    private companion object {
        const val TIMEOUT_MS = 180_000L
        const val MAX_ERR_CHARS = 4_000
        const val MSG_NO_DEBIAN = "Debian belum terpasang. Siapkan Debian di Settings > Terminal, lalu pasang FFmpeg di Settings > Paket > Alat."
        const val MSG_NO_FFMPEG = "FFmpeg belum terpasang di Debian. Pasang lewat Settings > Paket > Alat."
        const val MSG_OUTSIDE_BIND = "Video ini berada di luar folder yang terlihat oleh Debian. Pindahkan ke folder Molina, atau aktifkan \u201CAkses penyimpanan penuh\u201D di Settings > Molina Terminal > Terminal I/O."
        const val MSG_OUT_NOT_VISIBLE = "Folder hasil tidak terlihat oleh Debian."
        const val MSG_BAD_REQUEST = "Permintaan ekstraksi tidak valid."
    }
}
