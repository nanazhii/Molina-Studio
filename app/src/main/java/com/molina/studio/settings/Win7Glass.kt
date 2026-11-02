package com.molina.studio.settings

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.cinema.Win7
import com.molina.studio.ui.MolinaTheme
import kotlin.math.hypot

/** Pilihan tampilan kaca/RGB (SharedPreferences molina_ui). Animasi RGB bisa dimatikan untuk hemat baterai. */
internal object GlassPrefs {
    private const val PREFS = "molina_ui"
    private const val KEY_RGB = "rgb_anim"
    private var loaded = false

    var rgb by mutableStateOf(true)
        private set

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        rgb = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RGB, true)
    }

    fun setRgb(ctx: Context, v: Boolean) {
        loaded = true
        rgb = v
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RGB, v).apply()
    }
}

private val RgbColors = listOf(
    Color(0xFFFF1744), Color(0xFFFFEA00), Color(0xFF00E676),
    Color(0xFF00E5FF), Color(0xFF2979FF), Color(0xFFD500F9), Color(0xFFFF1744)
)

// Kaca Aero (varian gelap): biru kaca di sisi tepi, gelap di bawah.
private val GlassTop = Color(0xFF5A5A5A)
private val GlassMid = Color(0xFF2A2A2A)
private val GlassBottom = Color(0xFF101010)

/**
 * Bingkai RGB setebal 2dp. [animate] = true dan saklar RGB menyala -> sweep gradient berputar (hanya menggambar
 * ulang, tanpa recomposition). Selain itu bingkai statis emas-biru Molina tanpa animasi sama sekali.
 */
@Composable
internal fun RgbFrame(modifier: Modifier = Modifier, animate: Boolean = true, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    remember(ctx) { GlassPrefs.load(ctx) }
    val shape = RoundedCornerShape(11.dp)
    if (animate && GlassPrefs.rgb) {
        val transition = rememberInfiniteTransition()
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart)
        )
        Box(
            modifier
                .clip(shape)
                .drawBehind {
                    val d = hypot(size.width, size.height)
                    rotate(angle) {
                        drawRect(
                            brush = Brush.sweepGradient(RgbColors, center),
                            topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f),
                            size = Size(d, d)
                        )
                    }
                }
                .padding(2.dp)
        ) { content() }
    } else {
        Box(
            modifier
                .clip(shape)
                .background(Brush.linearGradient(listOf(MolinaTheme.Gold, Color(0xFF2D6DB5), MolinaTheme.Gold)), shape)
                .padding(2.dp)
        ) { content() }
    }
}

/** Jendela ala Windows 7 Aero: tepi kaca berkilap, bilah judul berikon, dan area klien hitam. */
@Composable
internal fun Win7GlassWindow(
    title: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    fillClient: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val outer = RoundedCornerShape(9.dp)
    val inner = RoundedCornerShape(3.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(outer)
            .background(Brush.verticalGradient(listOf(GlassTop, GlassMid, GlassBottom)), outer)
            .drawBehind {
                drawRect(Color.White.copy(alpha = 0.10f), Offset.Zero, Size(size.width, size.height * 0.45f))
            }
            .border(1.dp, Color(0x99D0D0D0), outer)
            .padding(start = 7.dp, end = 7.dp, bottom = 7.dp, top = 4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Spacer(Modifier.width(8.dp))
            Text(title, color = Color.White, fontSize = 13.sp)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (fillClient) Modifier.weight(1f) else Modifier)
                .clip(inner)
                .background(Win7.Face)
                .border(1.dp, Win7.FrameLine, inner)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

/**
 * Bilah progres glossy ala Windows 7. [fraction] null = indeterminate (blok bergeser). Nilai determinate
 * dihaluskan dengan animateFloatAsState.
 */
@Composable
internal fun GlossBar(
    fraction: Float?,
    top: Color,
    bottom: Color,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp
) {
    val bar = RoundedCornerShape(3.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(bar)
            .background(Brush.verticalGradient(listOf(Win7.BarBottom, Win7.BarTop)), bar)
            .border(1.dp, Win7.Line, bar)
            .padding(1.dp)
    ) {
        if (fraction != null) {
            val f by animateFloatAsState(targetValue = fraction.coerceIn(0f, 1f))
            Box(
                Modifier
                    .fillMaxWidth(f)
                    .fillMaxHeight()
                    .clip(bar)
                    .background(Brush.verticalGradient(listOf(top, bottom)), bar)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.45f)
                        .background(Color.White.copy(alpha = 0.28f))
                )
            }
        } else {
            val transition = rememberInfiniteTransition()
            val x by transition.animateFloat(
                initialValue = -0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart)
            )
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(
                    Brush.verticalGradient(listOf(top, bottom)),
                    Offset(size.width * x, 0f),
                    Size(size.width * 0.35f, size.height),
                    CornerRadius(3.dp.toPx())
                )
            }
        }
    }
}

/** Kartu berlapis gradasi gelap dengan bingkai tipis berwarna [accent] (hijau/kuning/merah/biru sesuai status). */
internal fun Modifier.win7Card(accent: Color = W7D.Line): Modifier {
    val shape = RoundedCornerShape(6.dp)
    return this
        .clip(shape)
        .background(Brush.verticalGradient(listOf(Color(0xFF161616), Color(0xFF090909))), shape)
        .border(1.dp, accent.copy(alpha = 0.55f), shape)
}

/** Grafik garis riwayat 0..100 (terbaru di kanan), maksimal [maxPoints] titik. */
@Composable
internal fun Sparkline(values: List<Int>, color: Color, modifier: Modifier = Modifier, maxPoints: Int = 60) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Win7.Input)
            .border(1.dp, Win7.Hairline, RoundedCornerShape(3.dp))
    ) {
        for (i in 1..3) {
            val y = size.height * i / 4f
            drawLine(Win7.Hairline, Offset(0f, y), Offset(size.width, y), 1f)
        }
        val n = values.size
        if (n < 2) return@Canvas
        val step = size.width / (maxPoints - 1)
        val x0 = size.width - step * (n - 1)
        val line = Path()
        val fill = Path()
        for (i in 0 until n) {
            val x = x0 + step * i
            val y = size.height - (values[i].coerceIn(0, 100) / 100f) * size.height
            if (i == 0) {
                line.moveTo(x, y)
                fill.moveTo(x, size.height)
                fill.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(size.width, size.height)
        fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(line, color, style = Stroke(width = 1.5.dp.toPx()))
    }
}
