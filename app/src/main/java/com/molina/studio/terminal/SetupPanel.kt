package com.molina.studio.terminal

import com.molina.studio.ui.MolinaTheme
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

private val SetupCardBg = MolinaTheme.CardBg
private val SetupCardLine = MolinaTheme.CardLine
private val SetupMuted = MolinaTheme.Muted
private val SetupGood = MolinaTheme.Good
private val SetupBad = MolinaTheme.Bad
private val SetupWarn = MolinaTheme.Warn
private val SetupButtonText = Color(0xFF1A1405)
private val SetupButtonWidth = 176.dp
private val SetupLocale: Locale = Locale("id", "ID")

// Puncak disk saat pasang: arsip ~30 MB + hasil ekstrak ~105 MB (arsip dihapus di akhir) + cadangan.
private const val SETUP_REQUIRED_FREE_MB = 140L
// Ukuran unduhan (sama dengan ROOTFS_SIZE di Rootfs.kt); MB ditampilkan diturunkan dari persen.
private const val SETUP_DOWNLOAD_MB = 30.2

private enum class SetupNet { Wifi, Cellular, Ethernet, Other, None }

/**
 * Layar pemasangan Debian: monogram Molina, status, kartu info, langkah dan galat ramah.
 * Murni UI: hanya membaca [RootfsState]; pemasangan dipicu lewat [onInstall].
 */
@Composable
fun SetupPanel(state: RootfsState, onInstall: () -> Unit, onCancel: () -> Unit) {
    val gold = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val installing = state is RootfsState.Downloading ||
        state is RootfsState.Verifying || state is RootfsState.Extracting

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MolinaMonogram()
            Spacer(Modifier.height(10.dp))
            Text("Siapkan lingkungan Linux", color = onSurface, fontSize = 18.sp,
                fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text("Debian trixie \u00B7 arm64", color = SetupMuted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            when {
                state is RootfsState.Failed -> StatusPill("Gagal", SetupBad)
                state is RootfsState.Maintenance -> StatusPill("Sedang dikelola", gold)
                installing -> StatusPill("Sedang memasang", gold)
                else -> StatusPill("Belum terpasang", SetupWarn)
            }
            Spacer(Modifier.height(14.dp))

            when (state) {
                is RootfsState.Failed -> FailedSection(state.message, onInstall)
                is RootfsState.Maintenance -> MaintenanceSection(state.label)
                is RootfsState.Downloading, is RootfsState.Verifying, is RootfsState.Extracting ->
                    ProgressSection(state, onCancel)
                else -> IdleSection(onInstall)
            }

            Spacer(Modifier.height(12.dp))
            Text("Sumber resmi Debian \u00B7 tanpa akun \u00B7 tanpa telemetri",
                color = SetupMuted, fontSize = 10.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun MaintenanceSection(label: String) {
    val gold = MaterialTheme.colorScheme.primary
    SetupCard {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp), color = gold, trackColor = SetupCardLine)
        Spacer(Modifier.height(8.dp))
        Text("Rootfs sedang dikelola dari Setelan > Sistem. Terminal aktif kembali setelah selesai.", color = SetupMuted, fontSize = 10.sp)
    }
}

@Composable
private fun MolinaMonogram() {
    val gold = MaterialTheme.colorScheme.primary
    Box(Modifier.size(40.dp).border(1.5.dp, gold, CircleShape), contentAlignment = Alignment.Center) {
        Text("M", color = gold, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Row(
        Modifier
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 11.sp)
    }
}

@Composable
private fun SetupCard(
    borderColor: Color = SetupCardLine,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SetupCardBg, RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 2.dp),
        content = content
    )
}

@Composable
private fun SetupDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(SetupCardLine))
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = SetupMuted, fontSize = 12.sp)
        Spacer(Modifier.width(12.dp))
        Text(value, color = valueColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InstallButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val gold = MaterialTheme.colorScheme.primary
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.width(SetupButtonWidth).height(40.dp),
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = gold,
            contentColor = SetupButtonText,
            disabledContainerColor = gold.copy(alpha = 0.25f),
            disabledContentColor = SetupButtonText.copy(alpha = 0.6f)
        )
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun IdleSection(onInstall: () -> Unit) {
    val ctx = LocalContext.current
    val initialNet = remember { currentNet(ctx) }
    val net by produceState(initialNet) {
        while (true) {
            delay(3000)
            value = withContext(Dispatchers.IO) { currentNet(ctx) }
        }
    }
    val free = remember { freeBytes(ctx) }
    val partial = remember { RootfsManager.partialBytes(ctx) }
    val enoughDisk = free < 0 || free >= SETUP_REQUIRED_FREE_MB * 1024L * 1024L
    val freeText = if (free < 0) "tidak diketahui" else "tersedia ${formatBytes(free)}"
    val (netText, netColor) = when (net) {
        SetupNet.Wifi -> "Wi-Fi" to MaterialTheme.colorScheme.onSurface
        SetupNet.Ethernet -> "Ethernet" to MaterialTheme.colorScheme.onSurface
        SetupNet.Cellular -> "Data seluler" to SetupWarn
        SetupNet.Other -> "Terhubung" to MaterialTheme.colorScheme.onSurface
        SetupNet.None -> "Tidak ada koneksi" to SetupBad
    }

    SetupCard {
        InfoRow("Unduhan", "~30 MB")
        SetupDivider()
        if (partial > 0) {
            InfoRow("Tersimpan", formatBytes(partial), SetupGood)
            SetupDivider()
        }
        InfoRow("Ruang disk", "~105 MB \u00B7 $freeText", if (enoughDisk) SetupGood else SetupBad)
        SetupDivider()
        InfoRow("Jaringan", netText, netColor)
        SetupDivider()
        InfoRow("Verifikasi", "SHA-256")
    }
    if (net == SetupNet.Cellular) {
        Spacer(Modifier.height(6.dp))
        Text("Unduhan ~30 MB akan memakai kuota data seluler.", color = SetupWarn,
            fontSize = 10.sp, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(14.dp))
    val canInstall = net != SetupNet.None && enoughDisk
    InstallButton(if (partial > 0) "Lanjutkan" else "Pasang Debian", canInstall, onInstall)
    if (!canInstall) {
        Spacer(Modifier.height(6.dp))
        Text(
            if (net == SetupNet.None) "Tidak ada koneksi internet."
            else "Ruang penyimpanan kurang (butuh ~$SETUP_REQUIRED_FREE_MB MB).",
            color = SetupBad, fontSize = 11.sp, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ProgressSection(state: RootfsState, onCancel: () -> Unit) {
    var cancelling by remember { mutableStateOf(false) }
    val gold = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val labels = listOf("Unduh", "Verifikasi", "Ekstrak")
    val current = when (state) {
        is RootfsState.Downloading -> 0
        is RootfsState.Verifying -> 1
        else -> 2
    }
    SetupCard {
        labels.forEachIndexed { i, label ->
            val mark: String
            val markColor: Color
            when {
                i < current -> { mark = "\u2713"; markColor = SetupGood }
                i == current -> { mark = "\u25CF"; markColor = gold }
                else -> { mark = "\u25CB"; markColor = SetupMuted }
            }
            val detail = when {
                i != current -> ""
                state is RootfsState.Downloading -> downloadText(state.percent)
                state is RootfsState.Extracting -> "${state.percent}%"
                else -> "SHA-256"
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(mark, color = markColor, fontSize = 12.sp, modifier = Modifier.width(18.dp))
                Text(label, color = if (i <= current) onSurface else SetupMuted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(detail, color = markColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            if (i < labels.lastIndex) SetupDivider()
        }
    }
    Spacer(Modifier.height(12.dp))
    val barModifier = Modifier.fillMaxWidth().height(3.dp)
    when (state) {
        is RootfsState.Downloading -> LinearProgressIndicator(
            progress = { state.percent / 100f }, modifier = barModifier,
            color = gold, trackColor = SetupCardLine)
        is RootfsState.Extracting -> LinearProgressIndicator(
            progress = { state.percent / 100f }, modifier = barModifier,
            color = gold, trackColor = SetupCardLine)
        else -> LinearProgressIndicator(modifier = barModifier, color = gold, trackColor = SetupCardLine)
    }
    Spacer(Modifier.height(8.dp))
    Text("Biarkan aplikasi tetap terbuka sampai selesai.", color = SetupMuted,
        fontSize = 10.sp, textAlign = TextAlign.Center)
    Spacer(Modifier.height(2.dp))
    TextButton(onClick = { cancelling = true; onCancel() }, enabled = !cancelling) {
        Text(if (cancelling) "Membatalkan..." else "Batal", color = SetupMuted, fontSize = 12.sp)
    }
}

@Composable
private fun FailedSection(message: String, onRetry: () -> Unit) {
    val ctx = LocalContext.current
    SetupCard(borderColor = SetupBad.copy(alpha = 0.6f)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text(friendlyError(message), color = SetupBad, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text(message, color = SetupMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.height(14.dp))
    InstallButton("Coba lagi", true, onRetry)
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("molina-setup", "Pemasangan Debian gagal: $message"))
        Toast.makeText(ctx, "Pesan galat disalin", Toast.LENGTH_SHORT).show()
    }) {
        Text("Salin pesan galat", color = SetupMuted, fontSize = 12.sp)
    }
}

private fun friendlyError(msg: String): String {
    val m = msg.lowercase(Locale.ROOT)
    return when {
        "checksum" in m || "sha-256" in m -> "Berkas unduhan rusak (checksum tidak cocok). Coba lagi."
        "no space" in m || "enospc" in m || "ruang" in m -> "Ruang penyimpanan tidak cukup."
        "http" in m -> "Server menolak unduhan. Coba lagi beberapa saat lagi."
        "unable to resolve" in m || "unknownhost" in m || "failed to connect" in m ||
            "timed out" in m || "timeout" in m || "network" in m ->
            "Tidak dapat terhubung ke server. Periksa koneksi lalu coba lagi."
        else -> "Pemasangan gagal. Coba lagi."
    }
}

private fun downloadText(percent: Int): String =
    String.format(SetupLocale, "%.1f / %.1f MB", percent * SETUP_DOWNLOAD_MB / 100.0, SETUP_DOWNLOAD_MB)

private fun formatBytes(b: Long): String =
    if (b >= (1L shl 30)) String.format(SetupLocale, "%.1f GB", b / 1073741824.0)
    else "${b shr 20} MB"

private fun freeBytes(ctx: Context): Long =
    runCatching { StatFs(ctx.filesDir.absolutePath).availableBytes }.getOrDefault(-1L)

private fun currentNet(ctx: Context): SetupNet = runCatching {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        ?: return@runCatching SetupNet.None
    when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> SetupNet.Wifi
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> SetupNet.Ethernet
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> SetupNet.Cellular
        else -> SetupNet.Other
    }
}.getOrDefault(SetupNet.Other)
