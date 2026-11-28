package com.molina.studio.cinema

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// [CINEMA-ICONS] Ikon garis modern untuk ubin beranda Cinema. Digambar di Canvas pada kisi 24 (tanpa aset, tanpa pustaka ikon).

/** Roda gigi 8 gigi berbentuk garis tepi dengan lingkaran tengah (ikon Setelan). */
@Composable
internal fun CinemaGearIcon(color: Color, box: Dp = 26.dp) {
    Canvas(Modifier.size(box)) {
        val u = size.width / 24f
        val c = Offset(12f * u, 12f * u)
        val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun at(deg: Double, r: Float): Offset {
            val a = deg * PI / 180.0
            return Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
        }
        val valley = 8.2f * u
        val crest = 10.4f * u
        val path = Path()
        for (i in 0 until 8) {
            val a = i * 45.0
            val pts = listOf(at(a - 14, valley), at(a - 8, crest), at(a + 8, crest), at(a + 14, valley))
            pts.forEachIndexed { j, o -> if (i == 0 && j == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        }
        path.close()
        drawPath(path, color, style = stroke)
        drawCircle(color, radius = 3.2f * u, center = c, style = stroke)
    }
}

/** [REFRESH-CENTER] Busur 270 derajat searah jarum jam + kepala panah, digeser agar kotak pembatasnya tepat di tengah kisi 24. */
private const val RF_R = 8f
private const val RF_START = -40f
private const val RF_SWEEP = 270f
private const val RF_HEAD = 5.2f

/** Pasangan (ujung panah, ujung garis kepala) pada kisi 24. */
private fun refreshHead(): List<Pair<Offset, Offset>> {
    val end = (RF_START + RF_SWEEP).toDouble() * PI / 180.0
    val tip = Offset(12f + RF_R * cos(end).toFloat(), 12f + RF_R * sin(end).toFloat())
    val bx = sin(end).toFloat() // arah mundur = -singgung = (sin, -cos)
    val by = -cos(end).toFloat()
    return listOf(40.0, -40.0).map { deg ->
        val p = deg * PI / 180.0
        val dx = bx * cos(p).toFloat() - by * sin(p).toFloat()
        val dy = bx * sin(p).toFloat() + by * cos(p).toFloat()
        tip to Offset(tip.x + dx * RF_HEAD, tip.y + dy * RF_HEAD)
    }
}

/** Selisih (kisi 24) yang memindahkan pusat kotak pembatas ikon ke (12, 12). */
private val RefreshShift: Offset = run {
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    fun add(x: Float, y: Float) {
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
    }
    var a = RF_START
    while (a <= RF_START + RF_SWEEP) {
        val r = a.toDouble() * PI / 180.0
        add(12f + RF_R * cos(r).toFloat(), 12f + RF_R * sin(r).toFloat())
        a += 2f
    }
    refreshHead().forEach { (t, e) -> add(t.x, t.y); add(e.x, e.y) }
    Offset(12f - (minX + maxX) / 2f, 12f - (minY + maxY) / 2f)
}

/** Busur 270 derajat searah jarum jam dengan kepala panah di ujungnya (ikon Segarkan). */
@Composable
internal fun CinemaRefreshIcon(color: Color, box: Dp = 26.dp) {
    Canvas(Modifier.size(box)) {
        val u = size.width / 24f
        val c = Offset(12f * u, 12f * u)
        val r = RF_R * u
        val sw = 2f * u
        translate(RefreshShift.x * u, RefreshShift.y * u) {
            drawArc(
                color, startAngle = RF_START, sweepAngle = RF_SWEEP, useCenter = false,
                topLeft = Offset(c.x - r, c.y - r), size = Size(2f * r, 2f * r),
                style = Stroke(width = sw, cap = StrokeCap.Round)
            )
            refreshHead().forEach { (t, e) ->
                drawLine(color, Offset(t.x * u, t.y * u), Offset(e.x * u, e.y * u), strokeWidth = sw, cap = StrokeCap.Round)
            }
        }
    }
}
