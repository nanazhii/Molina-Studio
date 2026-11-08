package com.molina.studio.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp

/** Ikon ubin Unduhan di beranda Settings: panel Aero biru dengan panah turun dan nampan. */
@Composable
internal fun W7IconDownloadBig(box: Dp) {
    Canvas(Modifier.size(box)) {
        val w = size.width
        val h = size.height
        drawRoundRect(Color(0xFF444444), Offset(w * 0.10f, h * 0.06f), Size(w * 0.80f, h * 0.88f), CornerRadius(w * 0.08f))
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFF8FC4EE), Color(0xFF2D6DB5))),
            Offset(w * 0.15f, h * 0.11f), Size(w * 0.70f, h * 0.78f), CornerRadius(w * 0.05f)
        )
        val s = w * 0.07f
        drawLine(Color.White, Offset(w * 0.50f, h * 0.24f), Offset(w * 0.50f, h * 0.56f), s, StrokeCap.Round)
        drawLine(Color.White, Offset(w * 0.50f, h * 0.58f), Offset(w * 0.34f, h * 0.42f), s, StrokeCap.Round)
        drawLine(Color.White, Offset(w * 0.50f, h * 0.58f), Offset(w * 0.66f, h * 0.42f), s, StrokeCap.Round)
        drawLine(Color.White, Offset(w * 0.30f, h * 0.72f), Offset(w * 0.70f, h * 0.72f), s, StrokeCap.Round)
    }
}
