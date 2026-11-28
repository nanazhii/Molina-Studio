package com.molina.studio.settings

import androidx.compose.runtime.Composable
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.molina.studio.stream.YtdlpInstallState
import com.molina.studio.stream.YtdlpInstaller
import androidx.compose.ui.platform.LocalContext
import com.molina.studio.apt.AptJob
import com.molina.studio.packages.StarterProfile // [INFO-DIALOG]
import com.molina.studio.apt.AptHistory
import com.molina.studio.ShortcutNav

/** Baris kunci keluaran apt yang disimpan dialog untuk ringkasan (aman walau log terpotong). */
private val APT_KEY_LINES = Regex("""(?i)(\d+ upgraded, \d+ newly installed|packages? can be upgraded|all packages are up to date)""")
private val APT_COUNTS = Regex("""(\d+) upgraded, (\d+) newly installed, (\d+) to remove and (\d+) not upgraded""")

/** Ringkasan satu baris untuk status dialog; null bila keluaran tidak dikenali (mis. bahasa lain). */
private fun aptSummary(lines: List<String>): String? {
    val m = lines.mapNotNull { APT_COUNTS.find(it) }.lastOrNull()
    if (m != null) {
        val (up, added, removed, held) = m.destructured
        return if (up == "0" && added == "0") "Sistem sudah terbaru."
        else buildString {
            append("Ringkasan: $up diperbarui, $added baru dipasang, $removed dihapus")
            if (held != "0") append(", $held ditunda")
            append(".")
        }
    }
    return if (lines.any { it.contains("up to date", ignoreCase = true) }) "Sistem sudah terbaru." else null
}

/** Ringkasan khusus profil Starter: "0 newly installed" berarti semuanya sudah ada, bukan "sistem terbaru". */
private fun starterSummary(lines: List<String>): String? {
    val all = lines.mapNotNull { APT_COUNTS.find(it) } // [STARTER-UPGRADE] baris pertama = upgrade, terakhir = install
    val first = all.firstOrNull() ?: return null
    val up = first.groupValues[1]
    val added = all.last().groupValues[2]
    val upText = if (up == "0") "Debian sudah mutakhir" else "$up program Debian diperbarui"
    val addText = if (added == "0") "semua paket dasar sudah terpasang" else "$added paket baru dipasang (termasuk pendukungnya)"
    return "$upText, $addText."
}

private val COUNT_LINE = Regex("""^\d+$""")

private fun countSummary(lines: List<String>): String? =
    lines.lastOrNull()?.toIntOrNull()?.let { if (it == 0) "Sistem sudah terbaru." else "$it program dapat diperbarui." }

@Composable
internal fun DebianAptDialog(job: AptJob, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val check = job == AptJob.CheckUpdates
    val showStatus = job == AptJob.InstallStarter || job == AptJob.UpdateUpgrade // [STATUS-UPDATE] pasang Starter dan Perbarui semua program
    val summarize: (List<String>) -> String? = when (job) {
        AptJob.CheckUpdates -> ::countSummary
        AptJob.InstallStarter -> ::starterSummary
        else -> ::aptSummary
    }
    ToolRunDialog(
        ToolRun(
            "Debian: ${job.title}", job.command, job.timeoutMs, aptShortNote(job), !check, if (check) COUNT_LINE else APT_KEY_LINES, summarize,
            info = aptInfo(job), // [INFO-DIALOG]
            doneActionLabel = if (showStatus) "Cek Status Molina" else null, // [STARTER-STATUS] [STATUS-UPDATE]
            onDoneAction = if (showStatus) ({ SettingsNav.openStatus = true; ShortcutNav.tab = ShortcutNav.TAB_SETTINGS }) else null
        ),
        onClose = onClose
    ) { code, out ->
        if (code != 0) return@ToolRunDialog
        when (job) {
            AptJob.UpdateUpgrade -> { // [PAKET-TERPUSAT]
                AptHistory.mark(ctx); AptHistory.setPending(ctx, 0)
                com.molina.studio.automation.AutoPrefs.init(ctx)
                if (com.molina.studio.automation.AutoPrefs.state.value.ytdlpOutdated) {
                    YtdlpInstaller.install(ctx)
                    Handler(Looper.getMainLooper()).post { Toast.makeText(ctx.applicationContext, "Memperbarui yt-dlp di latar belakang\u2026", Toast.LENGTH_LONG).show() }
                }
            }
            AptJob.CheckUpdates -> out.lineSequence().map { it.trim() }.lastOrNull { COUNT_LINE.matches(it) }
                ?.toIntOrNull()?.let { AptHistory.setPending(ctx, it) }
            AptJob.CleanUp -> Unit
            AptJob.InstallStarter -> {
                AptHistory.mark(ctx); AptHistory.setPending(ctx, 0) // [STARTER-STATUS] script sudah apt upgrade: Status menampilkan Semua terbaru
                YtdlpInstaller.installIfMissing(ctx) { r -> // [STARTER-YTDLP]
                    val msg = when (r) {
                        is YtdlpInstallState.Done -> if (r.version != null) "yt-dlp terpasang, versi ${r.version}." else "yt-dlp sudah terpasang."
                        is YtdlpInstallState.Failed -> "yt-dlp gagal dipasang: ${r.message}. Coba lagi lewat kartu yt-dlp di Setelan > Paket."
                        else -> null
                    }
                    if (msg != null) Handler(Looper.getMainLooper()).post { Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_LONG).show() }
                }
            }
        }
    }
}

// [INFO-DIALOG] Catatan singkat di dialog konfirmasi; rincian lengkap lewat ikon (i). CheckUpdates sudah singkat.
private fun aptShortNote(job: AptJob): String = when (job) {
    AptJob.UpdateUpgrade -> "Perbarui semua program Debian. Butuh internet dan beberapa menit."
    AptJob.CleanUp -> "Hapus program tak terpakai dan sisa unduhan lama."
    AptJob.InstallStarter -> "Pasang perangkat dasar: ${StarterProfile.DISPLAY.joinToString(", ")}."
    else -> job.note
}

private fun aptInfo(job: AptJob): HelpTopic? = when (job) {
    AptJob.UpdateUpgrade -> HelpKey.APT_UPDATE_UPGRADE.topic
    AptJob.CleanUp -> HelpKey.APT_CLEANUP.topic
    AptJob.InstallStarter -> HelpKey.APT_INSTALL_STARTER.topic
    else -> null
}
