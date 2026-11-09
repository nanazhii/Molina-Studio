package com.molina.code.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp

/**
 * [CODE-BACKORB] Bulatan kembali ala Win7, bentuk dan warnanya sama dengan Win7BackOrb di :app
 * (header Pengaturan). :feature:code tidak boleh bergantung pada :app, jadi digambar ulang di sini.
 * Hanya gambar: area ketuk dipegang pembungkusnya (PathHeader).
 */
@Composable
internal fun CodeBackOrb() {
    Box(
        Modifier.size(26.dp).clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF8FC4EE), Color(0xFF2D6DB5))), CircleShape)
            .border(1.dp, Color(0xFF1F4E85), CircleShape),
        Alignment.Center
    ) {
        Canvas(Modifier.size(14.dp)) {
            val w = size.width
            val h = size.height
            val p = Path().apply {
                moveTo(w * 0.05f, h * 0.5f); lineTo(w * 0.5f, h * 0.08f); lineTo(w * 0.5f, h * 0.32f)
                lineTo(w * 0.95f, h * 0.32f); lineTo(w * 0.95f, h * 0.68f); lineTo(w * 0.5f, h * 0.68f)
                lineTo(w * 0.5f, h * 0.92f); close()
            }
            drawPath(p, Color.White)
        }
    }
}
