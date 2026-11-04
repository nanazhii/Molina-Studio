package com.molina.studio.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.molina.studio.cinema.ResumeBridge
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.automation.AutoPrefs
import com.molina.studio.automation.AutoState
import com.molina.studio.automation.AutoWork
import com.molina.studio.automation.UpdateChecker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Menumpuk lencana di tepi kanan sebuah baris (mis. W7Link) tanpa mengubah tanda tangan W7Link. */
@Composable
internal fun BadgeOverlay(badge: String?, aero: Boolean, gold: Boolean = false, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        content()
        if (badge != null) {
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) { W7Badge(badge, aero, gold) }
        }
    }
}

private fun whenText(at: Long, never: String): String =
    if (at <= 0L) never else SimpleDateFormat("d MMM HH.mm", Locale("id", "ID")).format(Date(at))

private fun ytdlpText(s: AutoState): String = when {
    s.ytdlpInstalled.isEmpty() && s.ytdlpLatest.isEmpty() -> "Belum dicek, atau yt-dlp belum terpasang"
    s.ytdlpOutdated -> "Pembaruan tersedia: ${s.ytdlpInstalled} \u2192 ${s.ytdlpLatest}"
    s.ytdlpInstalled.isNotEmpty() -> "Terbaru (${s.ytdlpInstalled})"
    else -> "Rilis terbaru: ${s.ytdlpLatest}"
}

private fun newpipeText(s: AutoState): String {
    val cur = com.molina.engine.newpipe.NewPipeInfo.VERSION
    return when {
        s.newpipeOutdated -> "Pembaruan tersedia: $cur \u2192 ${s.newpipeLatest}. Sudah menyatu di aplikasi, jadi baru terbarui lewat versi Molina yang baru."
        s.newpipeLatest.isNotEmpty() -> "Terbaru ($cur)"
        else -> "Dibundel $cur. Belum diperiksa."
    }
}

/** Jumlah komponen yang punya pembaruan (apt dihitung 1 bila ada paket tertunda). */
internal fun updateCount(s: AutoState, aptPending: Int): Int =
    (if (aptPending > 0) 1 else 0) + (if (s.ytdlpOutdated) 1 else 0) + (if (s.newpipeOutdated) 1 else 0)

/** Settings > Paket: status pembaruan (Debian, yt-dlp, NewPipe) dan pemeriksaan otomatis [PAKET-PUSAT]. */
@Composable
internal fun UpdateStatusSection(onApt: (AptJob) -> Unit) { // [PAKET-TERPUSAT]
    val ctx = LocalContext.current
    remember { AutoPrefs.init(ctx); AptHistory.init(ctx) }
    val st by AutoPrefs.state.collectAsState()
    val pending by AptHistory.pending.collectAsState()
    val aptLast by AptHistory.last.collectAsState()
    val running by UpdateChecker.running.collectAsState()

    Category("Pembaruan")
    val updates = updateCount(st, pending)
    Text(
        when {
            updates > 0 -> "$updates pembaruan tersedia"
            st.lastCheck <= 0L -> "Belum pernah diperiksa"
            else -> "Semua sudah terbaru"
        },
        color = if (updates > 0) com.molina.studio.ui.MolinaTheme.Gold else W7D.TextDim,
        fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    BadgeOverlay(if (pending > 0) pending.toString() else null, false, gold = true) {
        SettingsItem("Program Debian", AptHistory.checkSubtitle(pending)) {}
    }
    BadgeOverlay(if (st.ytdlpOutdated) "Baru" else null, true, gold = true) {
        SettingsItem("yt-dlp (pengunduh tambahan)", ytdlpText(st) + if (st.ytdlpOutdated) ". Ikut diperbarui lewat Perbarui semua." else "") {}
    }
    BadgeOverlay(if (st.newpipeOutdated) "Baru" else null, true, gold = true) {
        SettingsItem("NewPipeExtractor (pencari video)", newpipeText(st)) {}
    }

    // [PAKET-BRAND] subjudul Pembaruan tunggal
    SwitchRow(
        "Cek pembaruan otomatis",
        "Paling sering sekali per 24 jam dan hanya lewat Wi-Fi. Memperbarui angka pembaruan program Debian dan " +
            "yt-dlp di menu Paket tanpa membuka dialog. Tanpa notifikasi dan tidak memasang apa pun.",
        st.autoUpdate
    ) {
        AutoPrefs.setAutoUpdate(ctx, it)
        AutoWork.sync(ctx)
    }
    // [PAKET-SAKELAR]
    SwitchRow(
        "Periksa versi alat otomatis",
        "Memperbarui versi dan tanggal di kartu alat Paket sekitar sekali per 24 jam dan saat Paket dibuka. " +
            "Hanya membaca versi: tanpa jaringan dan tanpa memasang apa pun. Tidak dijalankan bila baterai lemah.",
        st.autoToolCheck
    ) {
        AutoPrefs.setAutoToolCheck(ctx, it)
        AutoWork.sync(ctx)
    }
    SettingsItem(
        if (running) "Memeriksa\u2026" else "Periksa sekarang",
        "Terakhir: " + whenText(st.lastCheck, "belum pernah") + ". Berjalan di latar belakang dan bisa memakai data seluler."
    ) { if (!running) UpdateChecker.checkNow(ctx) }
    BadgeOverlay(if (updates > 0) updates.toString() else null, false, gold = true) {
        SettingsItem(
            "Perbarui semua",
            "Program Debian, lalu yt-dlp bila ada rilis baru, berurutan dalam satu proses. " + AptHistory.subtitle(aptLast)
        ) { onApt(AptJob.UpdateUpgrade) }
    }

}

/**
 * Settings > Sistem > Cadangan dan profil: cadangan harian otomatis. [SETELAN-PUSAT] Halaman "Cadangan dan otomasi"
 * dibubarkan: "Ingat posisi putar" dan "Unduhan masuk ke Cinema" kini di Pemutar.
 */
@Composable
internal fun AutoBackupRows() {
    val ctx = LocalContext.current
    remember { AutoPrefs.init(ctx) }
    val st by AutoPrefs.state.collectAsState()
    SwitchRow(
        "Cadangan gabungan harian",
        "Menyimpan penanda video, snippet Terminal, dan setelan ke folder Backup sekali per hari. " +
            "Salinan sebelumnya dijaga sebagai molina-backup.prev.json.",
        st.autoBackup
    ) {
        AutoPrefs.setAutoBackup(ctx, it)
        AutoWork.sync(ctx)
    }
    SettingsItem("Cadangan otomatis terakhir", whenText(st.lastBackup, "belum pernah")) {}
}
