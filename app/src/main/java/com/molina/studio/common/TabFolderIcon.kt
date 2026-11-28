package com.molina.studio.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Ikon tab Berkas: folder satu warna (mengikuti warna tab) digambar dengan Canvas,
 * sehingga tidak bergantung pada glyph font Android 8.
 */
@Composable
fun TabFolderIcon(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(color.copy(alpha = 0.70f), Offset(w * 0.06f, h * 0.16f), Size(w * 0.40f, h * 0.22f), CornerRadius(w * 0.05f))
        drawRoundRect(color, Offset(w * 0.06f, h * 0.28f), Size(w * 0.88f, h * 0.58f), CornerRadius(w * 0.08f))
    }
}
