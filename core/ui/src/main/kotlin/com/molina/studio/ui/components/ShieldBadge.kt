package com.molina.studio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.molina.studio.ui.MolinaTheme

/** [SHIELD-BADGE] Lencana perisai marun Molina Cinema dengan tanda centang putih; digambar vektor, tanpa aset. */
@Composable
fun ShieldBadge(modifier: Modifier = Modifier, badgeSize: Dp = 28.dp) {
    Canvas(modifier.size(badgeSize)) {
        val w = size.width
        val h = size.height
        val shield = Path().apply {
            moveTo(w * 0.5f, 0f)
            lineTo(w * 0.92f, h * 0.16f)
            lineTo(w * 0.92f, h * 0.50f)
            cubicTo(w * 0.92f, h * 0.76f, w * 0.74f, h * 0.92f, w * 0.5f, h)
            cubicTo(w * 0.26f, h * 0.92f, w * 0.08f, h * 0.76f, w * 0.08f, h * 0.50f)
            lineTo(w * 0.08f, h * 0.16f)
            close()
        }
        drawPath(shield, MolinaTheme.CinemaMaroon)
        drawPath(shield, Color.White.copy(alpha = 0.85f), style = Stroke(width = w * 0.05f, join = StrokeJoin.Round))
        val tick = Path().apply {
            moveTo(w * 0.32f, h * 0.52f)
            lineTo(w * 0.45f, h * 0.65f)
            lineTo(w * 0.69f, h * 0.38f)
        }
        drawPath(tick, Color.White, style = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
