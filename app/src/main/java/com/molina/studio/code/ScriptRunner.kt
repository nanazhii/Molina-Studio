package com.molina.studio.code

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.molina.code.preview.ScriptLaunch
import com.molina.core.api.LogLevel
import com.molina.core.log.LogBus
import com.molina.core.api.MolinaServices
import com.molina.core.api.RootfsState
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

    /** Minta editor Code dibuka layar penuh dari tab Berkas; dikonsumsi MainActivity lalu dikembalikan ke false. */
    var openEditor by mutableStateOf(false)
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

    /**
     * Mulai [item]. [newSession] = buka sesi terminal baru; bila tidak, ketik ke sesi aktif.
     * Mengembalikan pesan galat siap tampil, atau null bila perintah sudah dijadwalkan.
     * Panggil dari main thread.
     */
    fun run(ctx: Context, requested: ScriptItem, newSession: Boolean): String? {
        // [SCRIPT-FOLDER] isi dibaca ulang dari berkas di folder Scripts tepat sebelum dijalankan
        val item = ScriptStore.fresh(requested)
            ?: return "Berkas skrip tidak ditemukan di folder Molina/Scripts (mungkin sudah dihapus)"
        val app = ctx.applicationContext
        val gw = MolinaServices.terminal
        gw.refreshRootfs()
        if (gw.rootfsState.value !is RootfsState.Ready) {
            return "Debian belum terpasang. Pasang lebih dulu lewat Settings > Terminal"
        }
        val text = ScriptLaunch.normalizeEol(item.body)
        if (text.isBlank()) return "Isi skrip kosong"
        val plan = ScriptLaunch.planFor(item.title, text)
        val name = ScriptLaunch.fileName(item.id, item.title, plan)
        val dir = File(gw.rootfsDir(), HOST_REL)
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
        val command = ScriptLaunch.terminalCommand("$GUEST_DIR/$name", plan, text, item.args)
        if (!gw.typeCommand(command, newSession)) return "Batas ${gw.maxSessions} sesi terminal tercapai"
        ScriptStore.markRun(item.id)
        LogBus.log("terminal", LogLevel.Info, "script", "Script Execution: \"${item.title}\" dikirim ke sesi terminal")
        ScriptNav.openTerminal = true
        return null
    }

    /** Hapus berkas hasil tulis untuk skrip [id] (dipanggil saat skrip dihapus dari Settings). */
    fun cleanup(ctx: Context, id: String) {
        removeFiles(File(MolinaServices.terminal.rootfsDir(), HOST_REL), id)
    }

    private fun removeFiles(dir: File, id: String) {
        val marker = "-$id."
        dir.listFiles()?.forEach { if (it.isFile && it.name.contains(marker)) it.delete() }
    }
}
