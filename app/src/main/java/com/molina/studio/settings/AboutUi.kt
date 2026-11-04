package com.molina.studio.settings

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
import com.molina.studio.terminal.RootfsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    onLog: () -> Unit,
    onMonitor: () -> Unit,
    onAbout: () -> Unit,
    onLicenses: () -> Unit
) {
    val ctx = LocalContext.current
    remember { AptHistory.init(ctx); AutoPrefs.init(ctx); RootfsManager.refresh(ctx) }
    val rootfs by RootfsManager.state.collectAsState()
    val pending by AptHistory.pending.collectAsState()
    val auto by AutoPrefs.state.collectAsState()
    val diag by HealthCheck.last.collectAsState() // [DIAG-BADGE]
    val histTick by HealthHistory.changes.collectAsState() // [DIAG-LINE]
    var diagLine by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(histTick, diag) {
        diagLine = withContext(Dispatchers.IO) { runCatching { HealthHistory.problemPattern(HealthHistory.load(ctx)) }.getOrNull() }
    }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { HealthCheck.run(ctx, deep = false) } } }
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
    Box {
        W7Link({ W7IconLog() }, "Diagnostik dan laporan bug", diagLine?.let { "$it \u00b7 ketuk untuk detail" }
            ?: "Salin info debug, ZIP diagnostik sekali klik, dan laporan bug (URL disamarkan) dalam satu tempat") { onDiagnostics() }
        DiagBadge(diag, Modifier.align(Alignment.TopEnd))
    }
    W7Link({ W7IconLog() }, "Log", "Log aktivitas mpv, Terminal, dan sistem") { onLog() }
    W7Link({ W7IconMonitor(32.dp) }, "Monitor Sistem", "Unduh, unggah, CPU, dan RAM secara langsung") { onMonitor() }
    Category("Tentang")
    W7Link({ W7IconInfo() }, "Tentang Molina", "Apa itu Molina, fitur, privasi, dan kebutuhan sistem") { onAbout() }
    W7Link({ W7IconToolList() }, "Lisensi dan atribusi", "GPL-3.0-or-later \u00b7 teks lisensi komponen pihak ketiga, bisa dibaca tanpa internet") { onLicenses() }
}

/** [INFO-DECODER] Label dekoder untuk kartu Versi dan perangkat; selaras dengan tombol SW/HW/HW+ di pemutar. */
internal fun decoderLabel(v: String?): String = when (v) {
    null -> "belum tercatat (putar video sekali)"
    "no" -> "SW (perangkat lunak)"
    "mediacodec" -> "HW+ (mediacodec)"
    else -> "HW ($v)"
}
