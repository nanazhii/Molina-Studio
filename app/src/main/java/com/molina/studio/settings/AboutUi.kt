package com.molina.studio.settings

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.molina.studio.automation.AutoPrefs
import com.molina.core.common.BundledComponents
import com.molina.engine.newpipe.NewPipeInfo
import com.molina.studio.cinema.MpvVersionBridge
import com.molina.studio.terminal.RootfsManager

/**
 * [INFO-PAGE] Halaman Sistem > Info: rumah semua informasi diagnostik. Isinya Status Molina, versi dan
 * perangkat (Molina Terminal, Molina Cinema, libmpv, FFmpeg, RAM), tautan diagnostik, dan atribusi lisensi.
 * Gabungan dari halaman About lama.
 */
@Composable
internal fun HubInfoPage(
    onSystem: () -> Unit,
    onPackages: () -> Unit,
    onAutomation: () -> Unit,
    onDiagnostics: () -> Unit,
    onBugReport: () -> Unit,
    onLog: () -> Unit,
    onMonitor: () -> Unit
) {
    val ctx = LocalContext.current
    remember { AptHistory.init(ctx); AutoPrefs.init(ctx); RootfsManager.refresh(ctx) }
    val rootfs by RootfsManager.state.collectAsState()
    val pending by AptHistory.pending.collectAsState()
    val auto by AutoPrefs.state.collectAsState()
    val base = remember {
        val pi = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        val code = pi?.versionCode?.toString() ?: "?"
        listOf(
            "Molina Studio" to "${pi?.versionName ?: "?"} (kode $code)",
            "Molina Terminal" to "${pi?.versionName ?: "?"} (emulator Termux v0.118.0)",
            "Molina Cinema" to "${pi?.versionName ?: "?"} (libmpv ${MpvVersionBridge.mpv(ctx) ?: "belum tercatat"})",
            "Dekoder aktif" to decoderLabel(MpvVersionBridge.decoder(ctx)),
            "Paket" to ctx.packageName,
            "Diperbarui" to hubTime(pi?.lastUpdateTime ?: 0L),
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Perangkat" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Arsitektur" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "?"),
            "RAM" to run {
                val mi = ActivityManager.MemoryInfo()
                (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
                "${mi.availMem / 1048576} MB tersedia dari ${mi.totalMem / 1048576} MB"
            },
            "FFmpeg" to (MpvVersionBridge.ffmpeg(ctx) ?: "belum tercatat (putar video sekali)")
        )
    }
    val status = hubStatusRows(rootfs, pending, auto, maxOf(auto.lastBackup, BackupManager.lastBackupMs(ctx)), onSystem, onPackages, onAutomation)
    HubCard("Status Molina", status)
    Spacer(Modifier.height(12.dp))
    HubCard("Versi dan perangkat", base.map { HubRow(it.first, it.second) }, dots = false)
    Spacer(Modifier.height(8.dp))
    Category("Diagnostik")
    W7Link({ W7IconInfo() }, "Salin info debug", "Salin ringkasan versi dan status untuk laporan bug") {
        val text = (base + status.map { it.label to it.value }).joinToString("\n") { "${it.first}: ${it.second}" }
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Molina info debug", text))
        Toast.makeText(ctx, "Info debug disalin", Toast.LENGTH_SHORT).show()
    }
    W7Link({ W7IconLog() }, "Diagnostik sekali klik", "Satu ZIP: perangkat, versi, status PRoot, laporan bug, crash (URL disamarkan)") { onDiagnostics() }
    W7Link({ W7IconLog() }, "Laporan bug", "Gabungan kronologis dan per kelompok (Cinema, Terminal, Sistem, Logcat, Crash) ke folder Export") { onBugReport() }
    W7Link({ W7IconLog() }, "Log", "Log aktivitas mpv, Terminal, dan sistem") { onLog() }
    W7Link({ W7IconMonitor(32.dp) }, "Monitor Sistem", "Unduh, unggah, CPU, dan RAM secara langsung") { onMonitor() }
    Category("Lisensi dan atribusi") // [LICENSE-SCREEN] dihasilkan dari BundledComponents
    val licenseItems = remember { BundledComponents.all(NewPipeInfo.VERSION) }
    // [ABOUT-REPO] alamat resmi repo dan lisensi; dibuka lewat browser, bisa disalin
    val repoUrl = "https://github.com/nanazhii/Molina-Studio"
    val openUrl: (String) -> Unit = { url ->
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(ctx, "Tidak ada browser untuk membuka tautan", Toast.LENGTH_SHORT).show() }
    }
    SettingsItem("Lisensi Molina Studio", "GPL-3.0-or-later \u00b7 ketuk untuk membaca teks lengkap") { openUrl("$repoUrl/blob/main/LICENSE") }
    SettingsItem("Kode sumber", "$repoUrl \u00b7 ketuk untuk membuka") { openUrl(repoUrl) }
    SettingsItem("Salin alamat repo", "$repoUrl.git") {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Molina kode sumber", "$repoUrl.git"))
        Toast.makeText(ctx, "Alamat repo disalin", Toast.LENGTH_SHORT).show()
    }
    SettingsItem("Pengembang", "\u00a9 Molina Studio (nanazhii). Perangkat lunak bebas di bawah GPL-3.0-or-later, tanpa jaminan apa pun.") {}
    licenseItems.forEach { c ->
        SettingsItem(c.name, c.license + (if (c.version.isBlank()) "" else " \u00b7 " + c.version) + "\n" + c.source) {}
    }
}

/** [INFO-DECODER] Label dekoder untuk kartu Versi dan perangkat; selaras dengan tombol SW/HW/HW+ di pemutar. */
private fun decoderLabel(v: String?): String = when (v) {
    null -> "belum tercatat (putar video sekali)"
    "no" -> "SW (perangkat lunak)"
    "mediacodec" -> "HW+ (mediacodec)"
    else -> "HW ($v)"
}
