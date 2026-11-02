package com.molina.studio.code

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.molina.code.preview.ScriptLaunch
import com.molina.core.common.LogLevel
import com.molina.studio.log.LogBus
import com.molina.studio.terminal.RootfsManager
import com.molina.studio.terminal.RootfsState
import com.molina.studio.terminal.TerminalSessions
import com.molina.terminal.TerminalSession
import java.io.File

/** Permintaan pindah ke tab Terminal dari luar (dikonsumsi MainActivity), pola yang sama dengan SettingsNav. */
object ScriptNav {
    var openTerminal by mutableStateOf(false)

    /** Id skrip yang diminta dijalankan dari luar (pintasan layar utama); selalu lewat dialog konfirmasi. */
    var confirmRunId by mutableStateOf<String?>(null)
}

/** Permintaan membuka skrip di editor Code (tombol EDIT di Script Execution); dikonsumsi MainActivity. */
class CodeOpenRequest(val title: String, val key: String, val text: String)

object CodeNav {
    var pending by mutableStateOf<CodeOpenRequest?>(null)

    /** Berkas dari Penjelajah Molina (Buka di Code); dikonsumsi MainActivity lalu dikosongkan. */
    var pendingFiles by mutableStateOf<List<File>>(emptyList())
}

/**
 * Menjalankan skrip Script Execution di sesi Molina Terminal yang interaktif: isi skrip ditulis ke
 * `/root/.molina/scripts` di rootfs Debian (LF, bit eksekusi), lalu satu baris perintah
 * `chmod +x ... && ...` diketik ke PTY diikuti Enter. Skrip berjalan seperti diketik pengguna, jadi
 * input interaktif dan Ctrl+C bekerja normal.
 */
object ScriptRunner {
    private const val GUEST_DIR = "/root/.molina/scripts"
    private const val HOST_REL = "root/.molina/scripts"
    private const val SETTLE_MS = 250L
    private const val POLL_MS = 100L
    private const val MAX_POLLS = 150 // 15 dtk menunggu shell siap

    private val main = Handler(Looper.getMainLooper())

    /**
     * Mulai [item]. [newSession] = buka sesi terminal baru; bila tidak, ketik ke sesi aktif.
     * Mengembalikan pesan galat siap tampil, atau null bila perintah sudah dijadwalkan.
     * Panggil dari main thread.
     */
    fun run(ctx: Context, item: ScriptItem, newSession: Boolean): String? {
        val app = ctx.applicationContext
        RootfsManager.refresh(app)
        if (RootfsManager.state.value !is RootfsState.Ready) {
            return "Debian belum terpasang. Pasang lebih dulu lewat Settings > Terminal"
        }
        val text = ScriptLaunch.normalizeEol(item.body)
        if (text.isBlank()) return "Isi skrip kosong"
        val plan = ScriptLaunch.planFor(item.title, text)
        val name = ScriptLaunch.fileName(item.id, item.title, plan)
        val dir = File(RootfsManager.rootfsDir(app), HOST_REL)
        try {
            dir.mkdirs()
            removeFiles(dir, item.id) // judul bisa berubah: jangan sisakan berkas lama
            val f = File(dir, name)
            f.writeText(text, Charsets.UTF_8)
            f.setReadable(true, false)
            f.setExecutable(true, false)
        } catch (e: Exception) {
            return "Gagal menulis skrip ke Debian: ${e.message ?: e.javaClass.simpleName}"
        }
        val session: TerminalSession = if (newSession) {
            TerminalSessions.newSession(app) ?: return "Batas ${TerminalSessions.MAX_SESSIONS} sesi terminal tercapai"
        } else {
            TerminalSessions.ensureSession(app)
        }
        val command = ScriptLaunch.terminalCommand("$GUEST_DIR/$name", plan, text, item.args)
        typeWhenReady(session, command)
        ScriptStore.markRun(item.id)
        LogBus.log("terminal", LogLevel.Info, "script", "Script Execution: \"${item.title}\" dikirim ke sesi terminal")
        ScriptNav.openTerminal = true
        return null
    }

    /** Hapus berkas hasil tulis untuk skrip [id] (dipanggil saat skrip dihapus dari Settings). */
    fun cleanup(ctx: Context, id: String) {
        removeFiles(File(RootfsManager.rootfsDir(ctx.applicationContext), HOST_REL), id)
    }

    private fun removeFiles(dir: File, id: String) {
        val marker = "-$id."
        dir.listFiles()?.forEach { if (it.isFile && it.name.contains(marker)) it.delete() }
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
}
