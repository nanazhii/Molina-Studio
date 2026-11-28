package com.molina.studio.cinema

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// [ORB-SET] Set ikon orb kaca (gaya Aero) untuk ubin beranda Cinema. Digambar di Canvas, tanpa aset gambar.
// Satu fungsi dasar (cincin hitam, cakram gradasi, cahaya bawah, kilau atas) + glyph putih per fungsi.

/** Warna cakram per fungsi: atas, tengah, bawah, cahaya bawah (ARGB). */
internal enum class OrbTone(val top: Long, val mid: Long, val bottom: Long, val glow: Long) {
    Green(0xFF55BE30, 0xFF8EDB2B, 0xFFD9EA3A, 0xCCF4F54A),
    Blue(0xFF4C96E0, 0xFF2F6FC4, 0xFF63C6F5, 0xCCA6E8FF),
    Orange(0xFFF08A24, 0xFFF7A93B, 0xFFFFD866, 0xCCFFEFA0),
    Red(0xFFB8283A, 0xFFDD4256, 0xFFFF9A8A, 0xCCFFC2B8),
    Purple(0xFF6C42B8, 0xFF8B5FD8, 0xFFCDA9FF, 0xCCE6D2FF)
}

/** Glyph digambar pada pusat [c] dengan jari-jari efektif [rr]; [fg] warna isi, [ink] warna lubang/garis. */
private typealias OrbGlyph = DrawScope.(c: Offset, rr: Float, fg: Color, ink: Color) -> Unit

private fun DrawScope.drawOrb(tone: OrbTone, spin: Float, glyph: OrbGlyph) {
    val r = size.minDimension / 2f
    val c = center
    val rr = r * 0.88f
    drawCircle(Color(0xFF050505), radius = r, center = c)
    drawCircle(Color.White.copy(alpha = 0.85f), radius = r * 0.93f, center = c, style = Stroke(r * 0.035f))
    drawCircle(
        Brush.verticalGradient(
            listOf(Color(tone.top), Color(tone.mid), Color(tone.bottom)),
            startY = c.y - rr, endY = c.y + rr
        ),
        radius = rr, center = c
    )
    drawCircle(
        Brush.radialGradient(
            listOf(Color(tone.glow), Color.Transparent),
            center = Offset(c.x, c.y + rr * 0.95f), radius = rr * 0.8f
        ),
        radius = rr, center = c
    )
    drawOval(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.04f)),
            startY = c.y - rr * 0.92f, endY = c.y
        ),
        topLeft = Offset(c.x - rr * 0.78f, c.y - rr * 0.94f),
        size = Size(rr * 1.56f, rr * 0.92f)
    )
    val shadow = Color(0x59000000)
    translate(0f, rr * 0.04f) { rotate(spin, c) { glyph(c, rr, shadow, shadow) } }
    rotate(spin, c) { glyph(c, rr, Color.White, Color(tone.mid)) }
}

@Composable
private fun Orb(box: Dp, tone: OrbTone, spin: State<Float>? = null, glyph: OrbGlyph) {
    // Nilai animasi dibaca di blok gambar: hanya menggambar ulang, tanpa recomposition per frame.
    Canvas(Modifier.size(box)) { drawOrb(tone, spin?.value ?: 0f, glyph) }
}

/** Putaran berlawanan arah jarum jam (searah panah) hanya saat [active]; null bila diam. */
@Composable
private fun rememberOrbSpin(active: Boolean): State<Float>? =
    if (active) {
        val t = rememberInfiniteTransition(label = "orbSpin")
        t.animateFloat(
            initialValue = 0f, targetValue = -360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
            label = "orbSpinAngle"
        )
    } else null

private fun DrawScope.fileGlyph(c: Offset, rr: Float, fg: Color, ink: Color) {
    val l = c.x - rr * 0.36f
    val rt = c.x + rr * 0.36f
    val t = c.y - rr * 0.48f
    val b = c.y + rr * 0.48f
    val fold = rr * 0.26f
    drawPath(Path().apply {
        moveTo(l, t); lineTo(rt - fold, t); lineTo(rt, t + fold); lineTo(rt, b); lineTo(l, b); close()
    }, fg)
    drawPath(Path().apply {
        moveTo(rt - fold, t); lineTo(rt - fold, t + fold); lineTo(rt, t + fold); close()
    }, ink.copy(alpha = 0.35f))
    val x0 = l + rr * 0.13f
    val x1 = rt - rr * 0.13f
    listOf(0.0f to 1f, 0.18f to 1f, 0.36f to 0.6f).forEach { (dy, f) ->
        val y = c.y + rr * dy
        drawLine(ink.copy(alpha = 0.85f), Offset(x0, y), Offset(x0 + (x1 - x0) * f, y), strokeWidth = rr * 0.075f, cap = StrokeCap.Round)
    }
}

private fun DrawScope.channelsGlyph(c: Offset, rr: Float, fg: Color, ink: Color) {
    val sw = rr * 0.1f
    drawCircle(fg, radius = rr * 0.14f, center = c)
    listOf(0.36f, 0.62f).forEach { k ->
        val ra = rr * k
        val tl = Offset(c.x - ra, c.y - ra)
        val sz = Size(2f * ra, 2f * ra)
        drawArc(fg, startAngle = 140f, sweepAngle = 80f, useCenter = false, topLeft = tl, size = sz, style = Stroke(sw, cap = StrokeCap.Round))
        drawArc(fg, startAngle = -40f, sweepAngle = 80f, useCenter = false, topLeft = tl, size = sz, style = Stroke(sw, cap = StrokeCap.Round))
    }
}

private fun DrawScope.tvGlyph(c: Offset, rr: Float, fg: Color, ink: Color) {
    val cy = c.y + rr * 0.1f
    val w = rr * 0.9f
    val h = rr * 0.6f
    drawRoundRect(
        fg, topLeft = Offset(c.x - w / 2f, cy - h / 2f), size = Size(w, h),
        cornerRadius = CornerRadius(rr * 0.14f), style = Stroke(rr * 0.1f)
    )
    drawPath(Path().apply {
        moveTo(c.x - rr * 0.08f, cy - rr * 0.13f); lineTo(c.x - rr * 0.08f, cy + rr * 0.13f); lineTo(c.x + rr * 0.14f, cy); close()
    }, fg)
    val base = Offset(c.x, cy - h / 2f - rr * 0.02f)
    drawLine(fg, base, Offset(c.x - rr * 0.26f, c.y - rr * 0.5f), strokeWidth = rr * 0.07f, cap = StrokeCap.Round)
    drawLine(fg, base, Offset(c.x + rr * 0.26f, c.y - rr * 0.5f), strokeWidth = rr * 0.07f, cap = StrokeCap.Round)
}

private fun DrawScope.gearGlyph(c: Offset, rr: Float, fg: Color, ink: Color) {
    fun at(deg: Double, rad: Float): Offset {
        val a = deg * PI / 180.0
        return Offset(c.x + rad * cos(a).toFloat(), c.y + rad * sin(a).toFloat())
    }
    val valley = rr * 0.50f
    val crest = rr * 0.68f
    val path = Path()
    for (i in 0 until 8) {
        val a = i * 45.0
        val pts = listOf(at(a - 14, valley), at(a - 8, crest), at(a + 8, crest), at(a + 14, valley))
        pts.forEachIndexed { j, o -> if (i == 0 && j == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
    }
    path.close()
    drawPath(path, fg)
    drawCircle(ink, radius = rr * 0.22f, center = c)
}

private fun DrawScope.refreshGlyph(c: Offset, rr: Float, fg: Color, ink: Color) {
    val ra = rr * 0.5f
    val sw = rr * 0.2f
    listOf(0f, 180f).forEach { rot ->
        drawArc(
            fg, startAngle = -30f + rot, sweepAngle = -135f, useCenter = false,
            topLeft = Offset(c.x - ra, c.y - ra), size = Size(2f * ra, 2f * ra), style = Stroke(width = sw)
        )
        val th = (-165f + rot) * PI / 180.0
        val rx = cos(th).toFloat()
        val ry = sin(th).toFloat()
        val base = Offset(c.x + ra * rx, c.y + ra * ry)
        val tx = ry // singgung berlawanan arah jarum jam = (sin, -cos)
        val ty = -rx
        val w = rr * 0.30f
        val h = rr * 0.36f
        drawPath(Path().apply {
            moveTo(base.x + rx * w, base.y + ry * w)
            lineTo(base.x + tx * h, base.y + ty * h)
            lineTo(base.x - rx * w, base.y - ry * w)
            close()
        }, fg)
    }
}

@Composable
internal fun CinemaFileOrb(box: Dp = 28.dp) = Orb(box, OrbTone.Blue) { c, rr, fg, ink -> fileGlyph(c, rr, fg, ink) }

@Composable
internal fun CinemaChannelsOrb(box: Dp = 28.dp) = Orb(box, OrbTone.Orange) { c, rr, fg, ink -> channelsGlyph(c, rr, fg, ink) }

@Composable
internal fun CinemaTvOrb(box: Dp = 28.dp) = Orb(box, OrbTone.Red) { c, rr, fg, ink -> tvGlyph(c, rr, fg, ink) }

@Composable
internal fun CinemaSettingsOrb(box: Dp = 28.dp) = Orb(box, OrbTone.Purple) { c, rr, fg, ink -> gearGlyph(c, rr, fg, ink) }

/** Orb Segarkan; [spinning] = true memutar panah selama proses refresh berjalan. */
@Composable
internal fun CinemaRefreshOrb(box: Dp = 28.dp, spinning: Boolean = false) {
    val spin = rememberOrbSpin(spinning)
    Orb(box, OrbTone.Green, spin) { c, rr, fg, ink -> refreshGlyph(c, rr, fg, ink) }
}
