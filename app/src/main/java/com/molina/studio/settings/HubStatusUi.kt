package com.molina.studio.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.automation.AutoState
import com.molina.studio.terminal.RootfsManager
import com.molina.studio.terminal.RootfsState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Nada status pada kartu Molina Hub: Ok hijau, Warn kuning, Bad merah, Idle abu-abu. */
internal enum class HubTone { Ok, Warn, Bad, Idle }

internal class HubRow(
    val label: String,
    val value: String,
    val tone: HubTone = HubTone.Idle,
    val onClick: (() -> Unit)? = null
)

private fun hubToneColor(t: HubTone): Color = when (t) {
    HubTone.Ok -> Color(0xFF4CC38A)
    HubTone.Warn -> Color(0xFFE5A93C)
    HubTone.Bad -> Color(0xFFE5645A)
    HubTone.Idle -> W7D.TextDim
}

internal fun hubTime(ms: Long): String =
    if (ms <= 0L) "belum pernah"
    else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

/** Baris status dari data yang sudah ada (tanpa pemeriksaan baru): Debian, apt, yt-dlp, cadangan, otomasi. */
internal fun hubStatusRows(
    rootfs: RootfsState,
    aptPending: Int,
    auto: AutoState,
    backupMs: Long,
    onSystem: (() -> Unit)? = null,
    onPackages: (() -> Unit)? = null,
    onAutomation: (() -> Unit)? = null
): List<HubRow> {
    val debian = when (rootfs) {
        RootfsState.Ready -> Pair("Siap", HubTone.Ok)
        RootfsState.Missing -> Pair("Belum terpasang", HubTone.Warn)
        RootfsState.Verifying -> Pair("Memverifikasi rootfs", HubTone.Idle)
        is RootfsState.Downloading -> Pair("Mengunduh ${rootfs.percent}%", HubTone.Idle)
        is RootfsState.Extracting -> Pair("Mengekstrak ${rootfs.percent}%", HubTone.Idle)
        is RootfsState.Failed -> Pair("Gagal dipasang", HubTone.Bad)
        is RootfsState.Maintenance -> Pair("Sedang dikelola", HubTone.Idle)
    }
    val apt = when {
        aptPending < 0 -> Pair("Belum diperiksa", HubTone.Idle)
        aptPending == 0 -> Pair("Semua terbaru", HubTone.Ok)
        else -> Pair("$aptPending pembaruan tersedia", HubTone.Warn)
    }
    val yt = when {
        auto.ytdlpInstalled.isEmpty() -> Pair("Belum terpasang atau belum diketahui", HubTone.Idle)
        auto.ytdlpOutdated -> Pair("${auto.ytdlpInstalled} (terbaru ${auto.ytdlpLatest})", HubTone.Warn)
        else -> Pair(auto.ytdlpInstalled, HubTone.Ok)
    }
    val backup =
        if (backupMs <= 0L) Pair("Belum ada cadangan", HubTone.Warn) else Pair(hubTime(backupMs), HubTone.Ok)
    val autoRow =
        if (auto.autoUpdate) Pair("Aktif, cek terakhir ${hubTime(auto.lastCheck)}", HubTone.Ok)
        else Pair("Mati", HubTone.Idle)
    return listOf(
        HubRow("Debian", debian.first, debian.second, onSystem),
        HubRow("Paket Debian", apt.first, apt.second, onPackages),
        HubRow("yt-dlp", yt.first, yt.second, onPackages),
        HubRow("Cadangan", backup.first, backup.second, onAutomation),
        HubRow("Pembaruan otomatis", autoRow.first, autoRow.second, onPackages)
    )
}

/** Tabel bergaya Aero (sama dengan W7SystemSummary); [dots] menampilkan titik status per baris. */
@Composable
internal fun HubCard(title: String, rows: List<HubRow>, dots: Boolean = true) {
    val shape = RoundedCornerShape(3.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, W7D.Hairline, shape).background(W7D.Card)) {
        Text(
            title, color = W7D.NavHeader, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(W7D.BarTop, W7D.BarBottom)))
                .padding(horizontal = 10.dp, vertical = 7.dp)
        )
        rows.forEach { r ->
            val click = r.onClick
            Row(
                Modifier.fillMaxWidth()
                    .then(if (click != null) Modifier.clickable(onClick = click) else Modifier)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (dots) {
                    Box(Modifier.size(8.dp).background(hubToneColor(r.tone), CircleShape))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    r.label, color = W7D.TextDim, fontSize = 12.sp,
                    modifier = Modifier.width(112.dp)
                )
                Text(r.value, color = W7D.Ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** Kartu "Status Molina" di Sistem > Info; ketuk baris = buka halaman terkait. */
@Composable
internal fun W7HubStatusCard(
    aptPending: Int,
    auto: AutoState,
    onSystem: () -> Unit,
    onPackages: () -> Unit,
    onAutomation: () -> Unit
) {
    val ctx = LocalContext.current
    remember { RootfsManager.refresh(ctx) }
    val rootfs by RootfsManager.state.collectAsState()
    val backupMs = remember(auto.lastBackup) { maxOf(auto.lastBackup, BackupManager.lastBackupMs(ctx)) }
    HubCard("Status Molina", hubStatusRows(rootfs, aptPending, auto, backupMs, onSystem, onPackages, onAutomation))
    Spacer(Modifier.height(12.dp))
}
