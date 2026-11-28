package com.molina.studio.terminal

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.ui.MolinaTheme
import java.util.Locale

/**
 * [MONITOR-NAV] Permintaan navigasi dari header terminal ke Settings > Monitor Sistem.
 * Dibaca (lalu dikosongkan) oleh SystemMonitorPage dan PerformanceTab.
 */
object MonitorNav {
    enum class Focus { Cpu, Ram, Net }

    /** Indeks tab di SystemMonitorPage: Aplikasi 0, Proses 1, Kinerja 2, btop 3. */
    const val TAB_KINERJA = 2
    const val TAB_BTOP = 3

    var focus by mutableStateOf<Focus?>(null)
    var tab by mutableStateOf<Int?>(null)
}

private const val SPARK_POINTS = 24
private val ChipH = 28.dp

// [MC-COLOR] Warna monitor header: idle putih polos, unggah merah, unduh hijau (kontras tinggi di atas kaca gelap)
private val ChipIdle = MolinaTheme.MonitorIdle
private val ChipUp = MolinaTheme.MonitorUp
private val ChipDown = MolinaTheme.MonitorDown // [MC-COLOR] unduh hijau tegas (token pusat)
// [MC-CONTRAST] Bayangan tipis agar teks tetap terbaca di atas latar terminal terang maupun gelap
private val ChipTextStyle = TextStyle(
    shadow = Shadow(color = Color.Black.copy(alpha = 0.65f), offset = Offset(0f, 1f), blurRadius = 3f)
)

/**
 * [MONITOR-CHIPS] Monitor ringkas di header terminal: tiga chip kaca (CPU, RAM, jaringan) dengan
 * bilah mini dan grafik 24 detik di belakang teks. Ketuk = buka Kinerja (meter yang diketuk menyala),
 * tekan lama = buka btop. Tepi chip berubah kuning/merah saat beban naik.
 */
@Composable
internal fun MonitorChips(onOpen: () -> Unit) {
    val ctx = LocalContext.current
    var snap by remember { mutableStateOf<StatsSnapshot?>(null) }
    val cpuHist = remember { mutableStateListOf<Int>() }
    val ramHist = remember { mutableStateListOf<Int>() }
    LaunchedEffect(ctx) {
        SystemStats.shared(ctx).collect { s ->
            snap = s
            cpuHist.add(s.cpuPercent)
            ramHist.add(if (s.totalRamMb > 0) s.ramMb * 100 / s.totalRamMb else 0)
            if (cpuHist.size > SPARK_POINTS) cpuHist.removeAt(0)
            if (ramHist.size > SPARK_POINTS) ramHist.removeAt(0)
        }
    }
    val st = snap ?: return
    val compact = TerminalPrefs.compactMonitor || LocalConfiguration.current.screenWidthDp < 340 // [MC-FLAG]

    fun go(f: MonitorNav.Focus?, tab: Int) {
        MonitorNav.focus = f
        MonitorNav.tab = tab
        onOpen()
    }

    val cpuC = chipColor(st.cpuLevel)
    val ramC = chipColor(st.ramLevel)
    val ramFraction = if (st.totalRamMb > 0) st.ramMb.toFloat() / st.totalRamMb else 0f
    val rxOn = st.rxBps >= 1024
    val txOn = st.txBps >= 1024

    Row(
        Modifier.padding(start = if (compact) 4.dp else 6.dp), // [MC-PAD] [MC-GAP] sisi kanan 0: jarak ke tombol ⋮ = padding tombol (4dp)
        horizontalArrangement = Arrangement.spacedBy(4.dp), // [MC-GAP] sama dengan padding start HdrBtn di TerminalChrome
        verticalAlignment = Alignment.CenterVertically
    ) {
        MonitorChip(
            width = if (compact) 46.dp else 50.dp, level = st.cpuLevel, spark = if (compact) null else cpuHist, sparkColor = cpuC, // [MC-CPU]
            description = "CPU ${st.cpuPercent} persen. Ketuk untuk detail, tekan lama untuk btop.",
            onTap = { go(MonitorNav.Focus.Cpu, MonitorNav.TAB_KINERJA) },
            onLong = { go(null, MonitorNav.TAB_BTOP) }
        ) {
            ChipHead("CPU", "${st.cpuPercent}%", cpuC, compact) // [MC-HEAD-CPU]
            Spacer(Modifier.height(2.dp))
            MiniBar(st.cpuPercent / 100f, cpuC)
        }
        MonitorChip(
            width = if (compact) 46.dp else 50.dp, level = st.ramLevel, spark = if (compact) null else ramHist, sparkColor = ramC, // [MC-RAM]
            description = "RAM ${chipRam(st.ramMb)}. Ketuk untuk detail, tekan lama untuk btop.",
            onTap = { go(MonitorNav.Focus.Ram, MonitorNav.TAB_KINERJA) },
            onLong = { go(null, MonitorNav.TAB_BTOP) }
        ) {
            ChipHead("RAM", chipRam(st.ramMb), ramC, compact) // [MC-HEAD-RAM]
            Spacer(Modifier.height(2.dp))
            MiniBar(ramFraction, ramC)
        }
        MonitorChip(
            width = if (compact) 54.dp else 56.dp, level = Level.Normal, spark = null, sparkColor = MolinaTheme.NetDown, // [MC-NET]
            description = "Jaringan unduh ${chipNet(st.rxBps)}, unggah ${chipNet(st.txBps)}. Ketuk untuk detail, tekan lama untuk btop.",
            onTap = { go(MonitorNav.Focus.Net, MonitorNav.TAB_KINERJA) },
            onLong = { go(null, MonitorNav.TAB_BTOP) }
        ) {
            NetLine("\u2193" + chipNet(st.rxBps), ChipDown, rxOn)
            NetLine("\u2191" + chipNet(st.txBps), ChipUp, txOn)
        }
    }
}

@Composable
private fun MonitorChip(
    width: Dp,
    level: Level,
    spark: List<Int>?,
    sparkColor: Color,
    description: String,
    onTap: () -> Unit,
    onLong: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLong)
    var pressed by remember { mutableStateOf(false) }
    val warn by animateFloatAsState(targetValue = if (level == Level.Normal) 0f else 1f, animationSpec = tween(300))
    val shape = RoundedCornerShape(6.dp)
    val border = lerp(Color.White.copy(alpha = 0.16f), chipColor(level).copy(alpha = 0.85f), warn)
    val top = if (pressed) 0.28f else 0.14f
    val bottom = if (pressed) 0.14f else 0.04f
    Column(
        Modifier
            .width(width)
            .height(ChipH)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.35f), shape) // [MC-SCRIM] lapisan gelap di bawah kaca putih
            .background(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = top), Color.White.copy(alpha = bottom))),
                shape
            )
            .then(if (spark != null) Modifier.sparkBehind(spark, sparkColor) else Modifier)
            .border(1.dp, border, shape)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { tap() },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        long()
                    }
                )
            }
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.Center,
        content = content
    )
}

@Composable
private fun ChipHead(label: String, value: String, color: Color, compact: Boolean = false) { // [MC-HEAD-FN]
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly /* [MC-HEAD-CENTER] pasangan label+nilai terpusat, seragam dengan chip jaringan */, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = ChipIdle, style = ChipTextStyle, fontSize = if (compact) 7.sp else 8.sp, /* [MC-LABEL] */ lineHeight = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false)
        Text(value, color = color, style = ChipTextStyle, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun NetLine(text: String, color: Color, active: Boolean) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth(), // [MC-NET-CENTER] teks unduh/unggah di tengah bingkai chip
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        color = color, // [MC-COLOR] tidak lagi diredupkan saat idle agar tetap terbaca
        style = ChipTextStyle,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, // [MC-A11Y] penanda kedua selain warna
        fontSize = 9.sp, lineHeight = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false
    )
}

@Composable
private fun MiniBar(fraction: Float, color: Color) {
    val f by animateFloatAsState(targetValue = fraction.coerceIn(0f, 1f), animationSpec = tween(400))
    val shape = RoundedCornerShape(2.dp)
    Row(Modifier.fillMaxWidth().height(3.dp).clip(shape).background(Color.Black.copy(alpha = 0.45f))) {
        Spacer(
            Modifier
                .fillMaxWidth(f)
                .fillMaxHeight()
                .background(Brush.verticalGradient(listOf(lerp(color, Color.White, 0.35f), color)), shape)
        )
    }
}

/** Area grafik tipis di belakang isi chip; dibaca saat menggambar, jadi tidak memicu recompose. */
private fun Modifier.sparkBehind(values: List<Int>, color: Color): Modifier = drawBehind {
    val n = values.size
    if (n < 2) return@drawBehind
    val step = size.width / (SPARK_POINTS - 1)
    val x0 = size.width - step * (n - 1)
    val path = Path()
    path.moveTo(x0, size.height)
    for (i in 0 until n) {
        val y = size.height - (values[i].coerceIn(0, 100) / 100f) * size.height
        path.lineTo(x0 + step * i, y)
    }
    path.lineTo(x0 + step * (n - 1), size.height)
    path.close()
    drawPath(path, color.copy(alpha = 0.40f)) // [MC-AUTO-HINT] lebih kontras
}

private fun chipColor(l: Level): Color = when (l) {
    Level.Normal -> ChipIdle
    Level.Warn -> MolinaTheme.Warn
    Level.High -> MolinaTheme.Bad
}

private fun chipRam(mb: Int): String =
    if (mb >= 1024) String.format(Locale.US, "%.1fG", mb / 1024.0) else "${mb}M"

/** Kecepatan ringkas: 0.0K/s, 12.3K/s, 350K/s, 1.2M/s. */
private fun chipNet(bps: Long): String {
    val kb = bps / 1024.0
    return when {
        kb < 100.0 -> String.format(Locale.US, "%.1fK/s", kb)
        kb < 1000.0 -> String.format(Locale.US, "%.0fK/s", kb)
        else -> String.format(Locale.US, "%.1fM/s", kb / 1024.0)
    }
}
