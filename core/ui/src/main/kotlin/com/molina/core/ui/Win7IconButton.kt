package com.molina.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * [ICON-BTN] Tombol gaya Win7 berisi ikon (tanpa teks) agar hemat ruang. Tampilan sama dengan [Win7Button];
 * [contentDescription] dibacakan pembaca layar.
 */
@Composable
fun Win7IconButton(
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier.height(28.dp).widthIn(min = 40.dp)
            .clip(shape)
            .win7ButtonSurface(shape)
            .semantics { this.contentDescription = contentDescription }
            .clickable(enabled = enabled, onClick = onClick),
        Alignment.Center
    ) { icon() }
}

/** [ICON-BTN] Ikon papan klip (Tempel), digambar di Canvas agar tajam di semua kepadatan layar. Sumber tunggal ikon Tempel. */
@Composable
fun Win7PasteGlyph(
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = Win7.Ink,
    glyphSize: androidx.compose.ui.unit.Dp = 16.dp
) {
    androidx.compose.foundation.Canvas(modifier.size(glyphSize)) {
        val sw = 1.4.dp.toPx()
        val w = size.width
        val h = size.height
        fun at(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(w * x, h * y)
        drawRoundRect( // badan papan klip
            tint, topLeft = at(0.12f, 0.16f),
            size = androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.80f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.10f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(sw)
        )
        drawRoundRect( // penjepit
            tint, topLeft = at(0.32f, 0.04f),
            size = androidx.compose.ui.geometry.Size(w * 0.36f, h * 0.22f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f)
        )
        drawLine(tint, at(0.30f, 0.52f), at(0.70f, 0.52f), sw)
        drawLine(tint, at(0.30f, 0.72f), at(0.60f, 0.72f), sw)
    }
}
