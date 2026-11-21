package com.molina.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * [EXPANDER-ICON] Indikator ekspansi folder: satu segitiga yang berputar 90 derajat (kanan -> bawah) dengan animasi halus.
 * Warna ikut berpindah dari [color] (tertutup) ke [activeColor] (terbuka). Satu sumber untuk drawer Terminal dan tree Code.
 */
@Composable
fun ExpanderIcon(expanded: Boolean, color: Color, activeColor: Color = color, iconSize: Dp = 10.dp, modifier: Modifier = Modifier) {
    val t by animateFloatAsState(if (expanded) 1f else 0f, tween(150), label = "expander")
    Canvas(modifier.size(iconSize).graphicsLayer { rotationZ = 90f * t }) {
        val k = size.minDimension / 10f
        val p = Path().apply {
            moveTo(3f * k, 1f * k)
            lineTo(8f * k, 5f * k)
            lineTo(3f * k, 9f * k)
            close()
        }
        drawPath(p, lerp(color, activeColor, t))
    }
}
