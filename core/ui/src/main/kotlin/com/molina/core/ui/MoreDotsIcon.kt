package com.molina.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** [DRAWER-DOTS] Titik tiga vertikal (tombol menu per baris dan header). Satu sumber untuk Code dan drawer Terminal. */
@Composable
fun MoreDotsIcon(color: Color, iconSize: Dp = 16.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(iconSize)) {
        val u = this.size.minDimension / 24f
        val cx = this.size.width / 2f
        drawCircle(color, u * 2f, Offset(cx, u * 4f))
        drawCircle(color, u * 2f, Offset(cx, u * 12f))
        drawCircle(color, u * 2f, Offset(cx, u * 20f))
    }
}
