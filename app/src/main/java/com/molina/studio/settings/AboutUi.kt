package com.molina.studio.settings

import com.molina.core.api.MolinaServices
import com.molina.studio.ui.components.PackageSectionHeader
import com.molina.studio.ui.components.PackageStatusCard
import androidx.compose.foundation.layout.padding
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.molina.studio.automation.AutoPrefs
import com.molina.studio.cinema.MpvVersionBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.molina.studio.backup.BackupManager
import com.molina.studio.apt.AptHistory

/**
 * [INFO-PAGE] Halaman Sistem > Info: pintu masuk semua informasi. [INFO-SUBMENU] Status Molina serta Versi dan
 * perangkat kini halaman sendiri (submenu) seperti Log; Info hanya memuat kartu tautan.
 */
@Composable
internal fun HubInfoPage(
    onStatus: () -> Unit,
    onVersion: () -> Unit,
    onHelp: () -> Unit, // [INFO-INDEX]
    onDiagnostics: () -> Unit,
    onLog: () -> Unit,
    onAbout: () -> Unit,
    onLicenses: () -> Unit
) {
    val ctx = LocalContext.current
    val diag by HealthCheck.last.collectAsState() // [DIAG-BADGE]
    val histTick by HealthHistory.changes.collectAsState() // [DIAG-LINE]
    var diagLine by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(histTick, diag) {
        diagLine = withContext(Dispatchers.IO) { runCatching { HealthHistory.problemPattern(HealthHistory.load(ctx)) }.getOrNull() }
    }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { HealthCheck.run(ctx, deep = false) } } }
    remember { AptHistory.init(ctx); AutoPrefs.init(ctx); MolinaServices.terminal.refreshRootfs() } // [INFO-SUBMENU]
    val rootfs by MolinaServices.terminal.rootfsState.collectAsState()
    val pending by AptHistory.pending.collectAsState()
    val auto by AutoPrefs.state.collectAsState()
    val versionName = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    PackageSectionHeader("Informasi")
    PackageStatusCard("Status Molina", hubStatusSummary(rootfs, pending, auto), null, onClick = onStatus, icon = { W7IconStatus() })
    Spacer(Modifier.height(8.dp))
    PackageStatusCard(
        "Versi dan perangkat",
        "Molina $versionName \u00b7 Android ${Build.VERSION.RELEASE} \u00b7 ${Build.MANUFACTURER} ${Build.MODEL}",
        null, onClick = onVersion, icon = { W7IconVersion() }
    )
    Spacer(Modifier.height(8.dp))
    PackageStatusCard( // [INFO-INDEX]
        "Keterangan setelan", "Penjelasan ikon (i) semua setelan di satu tempat, bisa dicari",
        null, onClick = onHelp, icon = { W7IconHelp() }
    )
    Spacer(Modifier.height(16.dp)) // [INFO-KARTU]
    PackageSectionHeader("Diagnostik")
    Box {
        PackageStatusCard(
            "Diagnostik dan laporan bug",
            diagLine?.let { "$it \u00b7 ketuk untuk detail" }
                ?: "Salin info debug, ZIP diagnostik sekali klik, dan laporan bug (URL disamarkan) dalam satu tempat",
            null, onClick = onDiagnostics,
            icon = { W7IconDiag() } // [INFO-IKON]
        )
        DiagBadge(diag, Modifier.align(Alignment.TopEnd).padding(end = 20.dp))
    }
    Spacer(Modifier.height(8.dp))
    PackageStatusCard("Log", "Log aktivitas mpv, Terminal, dan sistem", null, onClick = onLog, icon = { W7IconLog() })

    Spacer(Modifier.height(16.dp))
    PackageSectionHeader("Tentang")
    PackageStatusCard("Tentang Molina", "Apa itu Molina, fitur, privasi, dan kebutuhan sistem", null, onClick = onAbout, icon = { W7IconSysInfo() })
    Spacer(Modifier.height(8.dp))
    PackageStatusCard(
        "Lisensi dan atribusi",
        "GPL-3.0-or-later \u00b7 teks lisensi komponen pihak ketiga, bisa dibaca tanpa internet",
        null, onClick = onLicenses, icon = { W7IconToolList() }
    )
}

/** [INFO-SUBMENU] Sistem > Info > Status Molina: ringkasan Debian, paket, otomasi, dan cadangan; ketuk baris = buka halaman terkait. */
@Composable
internal fun HubStatusPage(
    onSystem: () -> Unit,
    onPackages: () -> Unit,
    onAutomation: () -> Unit
) {
    val ctx = LocalContext.current
    remember { AptHistory.init(ctx); AutoPrefs.init(ctx); MolinaServices.terminal.refreshRootfs() }
    val rootfs by MolinaServices.terminal.rootfsState.collectAsState()
    val pending by AptHistory.pending.collectAsState()
    val auto by AutoPrefs.state.collectAsState()
    val status = hubStatusRows(rootfs, pending, auto, maxOf(auto.lastBackup, BackupManager.lastBackupMs(ctx)), onSystem, onPackages, onAutomation)
    HubCard("Status Molina", status, showHeader = false)
}

/** [INFO-SUBMENU] Sistem > Info > Versi dan perangkat: Molina Terminal, Cinema, libmpv, FFmpeg, Android, RAM. */
@Composable
internal fun HubVersionPage() {
    val ctx = LocalContext.current
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
    HubCard("Versi dan perangkat", base.map { HubRow(it.first, it.second) }, dots = false, showHeader = false)
}

/** [INFO-DECODER] Label dekoder untuk kartu Versi dan perangkat; selaras dengan tombol SW/HW/HW+ di pemutar. */
internal fun decoderLabel(v: String?): String = when (v) {
    null -> "belum tercatat (putar video sekali)"
    "no" -> "SW (perangkat lunak)"
    "mediacodec" -> "HW+ (mediacodec)"
    else -> "HW ($v)"
}
