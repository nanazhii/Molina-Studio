package com.molina.studio.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.cinema.Win7
import com.molina.studio.terminal.StatsSnapshot
import com.molina.studio.terminal.SystemStats
import java.util.Locale
import kotlin.math.ln
import com.molina.studio.terminal.Level as LoadLevel

private val MonLocale: Locale = Locale("id", "ID")
private const val HISTORY_POINTS = 60

/** Satu baris meter: label, nilai, dan bilah progres glossy ala Windows 7. */
@Composable
private fun MonitorMeter(label: String, value: String, fraction: Float, top: Color, bottom: Color) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Win7.Ink, fontSize = 13.sp)
            Text(value, color = Win7.NavHeader, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(5.dp))
        GlossBar(fraction, top, bottom, height = 16.dp)
    }
}

private fun levelColors(l: LoadLevel): Pair<Color, Color> = when (l) {
    LoadLevel.Normal -> Color(0xFF7BE87A) to Color(0xFF1E9E1A)
    LoadLevel.Warn -> Color(0xFFFFE680) to Color(0xFFE0A800)
    LoadLevel.High -> Color(0xFFFF8A80) to Color(0xFFC4392B)
}

/** Skala logaritmik: 1 KB/s ~ 6%, 1 MB/s ~ 60%, 100 MB/s = penuh. */
private fun netFraction(bps: Long): Float =
    (ln(1.0 + bps.coerceAtLeast(0L) / 1024.0) / ln(1.0 + 102400.0)).toFloat().coerceIn(0f, 1f)

private fun ramText(s: StatsSnapshot): String {
    val used = if (s.ramMb >= 1024) String.format(MonLocale, "%.1f GB", s.ramMb / 1024.0) else "${s.ramMb} MB"
    val pct = if (s.totalRamMb > 0) s.ramMb * 100 / s.totalRamMb else 0
    return "$used / ${s.totalGbText} GB ($pct%)"
}

/** Halaman Settings > Sistem > Monitor Sistem: meter unduh/unggah/CPU/RAM, grafik 60 detik, saklar animasi RGB. */
@Composable
internal fun SystemMonitorPage() {
    val ctx = LocalContext.current
    var stats by remember { mutableStateOf(StatsSnapshot()) }
    val cpuHist = remember { mutableStateListOf<Int>() }
    val ramHist = remember { mutableStateListOf<Int>() }
    val rxHist = remember { mutableStateListOf<Int>() } // [MONITOR-REALTIME]
    val txHist = remember { mutableStateListOf<Int>() }
    // Dikumpulkan langsung (bukan collectAsState) agar tiap sampel masuk riwayat walau nilainya sama.
    LaunchedEffect(ctx) {
        SystemStats.flow(ctx).collect { s ->
            stats = s
            cpuHist.add(s.cpuPercent)
            ramHist.add(if (s.totalRamMb > 0) s.ramMb * 100 / s.totalRamMb else 0)
            if (cpuHist.size > HISTORY_POINTS) cpuHist.removeAt(0)
            if (ramHist.size > HISTORY_POINTS) ramHist.removeAt(0)
            rxHist.add((netFraction(s.rxBps) * 100f).toInt())
            txHist.add((netFraction(s.txBps) * 100f).toInt())
            if (rxHist.size > HISTORY_POINTS) rxHist.removeAt(0)
            if (txHist.size > HISTORY_POINTS) txHist.removeAt(0)
        }
    }
    val cpuC = levelColors(stats.cpuLevel)
    val ramC = levelColors(stats.ramLevel)
    val ramFraction = if (stats.totalRamMb > 0) stats.ramMb.toFloat() / stats.totalRamMb else 0f

    Spacer(Modifier.height(4.dp))
    RgbFrame(Modifier.fillMaxWidth()) {
        Win7GlassWindow("Monitor Sistem", icon = { W7IconMonitor(18.dp) }) {
            MonitorMeter("\u2193 Unduh", stats.rxText, netFraction(stats.rxBps), Color(0xFF8FC4EE), Color(0xFF2D6DB5))
            MonitorMeter("\u2191 Unggah", stats.txText, netFraction(stats.txBps), Color(0xFFE8776B), Color(0xFFB3261E))
            MonitorMeter("CPU", "${stats.cpuPercent}%", stats.cpuPercent / 100f, cpuC.first, cpuC.second)
            MonitorMeter("RAM", ramText(stats), ramFraction, ramC.first, ramC.second)
            Text("Riwayat 60 detik", color = Win7.TextDim, fontSize = 12.sp)
            Sparkline(cpuHist, cpuC.second, maxPoints = HISTORY_POINTS)
            Sparkline(ramHist, ramC.second, maxPoints = HISTORY_POINTS)
            Sparkline(rxHist, Color(0xFF2D6DB5), maxPoints = HISTORY_POINTS)
            Sparkline(txHist, Color(0xFFB3261E), maxPoints = HISTORY_POINTS)
            Text("Dari atas: CPU %, RAM % dari total perangkat, unduh, unggah (skala logaritmik).", color = Win7.TextDim, fontSize = 11.sp)
            if (!stats.netPerUid) Text("Jaringan: total seluruh perangkat (hitungan per-aplikasi tidak didukung perangkat ini).", color = Color(0xFFE0A800), fontSize = 11.sp)
        }
    }
    Spacer(Modifier.height(10.dp))
    SwitchRow(
        "Animasi frame RGB",
        "Matikan untuk menghemat baterai; bingkai menjadi emas-biru statis. Berlaku juga di halaman Unduhan.",
        GlassPrefs.rgb
    ) { GlassPrefs.setRgb(ctx, it) }
    Text(
        "Mengukur seluruh proses aplikasi (termasuk Debian PRoot). Berhenti saat aplikasi di latar belakang. " +
            "Bilah jaringan memakai skala logaritmik. Kuning = lonjakan, merah = tinggi.",
        color = Win7.TextDim,
        fontSize = 12.sp
    )
}
