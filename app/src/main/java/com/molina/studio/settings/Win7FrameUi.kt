package com.molina.studio.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Kotak klik dengan bingkai tipis ala Windows 7: bingkai abu tipis saat diam; saat ditekan atau
 * [selected] bingkai berubah biru Aero, latar bergradasi biru gelap, plus garis highlight putih tipis di dalam.
 * Dipakai ubin beranda Settings (Sistem, Paket, Berkas, ...) dan baris W7Link.
 */
@Composable
internal fun Win7FrameBox(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val t by animateFloatAsState(if (pressed || selected) 1f else 0f)
    val outer = RoundedCornerShape(4.dp)
    val inner = RoundedCornerShape(3.dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(outer)
            .background(
                Brush.verticalGradient(
                    listOf(
                        lerp(Color.Transparent, W7D.SelFill, t),
                        lerp(Color.Transparent, Color(0xFF0A1726), t)
                    )
                ),
                outer
            )
            .border(1.dp, lerp(W7D.Hairline, W7D.SelLine, t), outer)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
    ) {
        Box(Modifier.matchParentSize().padding(1.dp).border(1.dp, Color.White.copy(alpha = 0.10f * t), inner))
        content()
    }
}

/** Lencana bulat merah glossy (jumlah paket menunggu, dsb.) ala Windows 7. */
@Composable
internal fun W7Badge(text: String, aero: Boolean = false, gold: Boolean = false) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    if (gold) listOf(Color(0xFFFFDC85), com.molina.studio.ui.MolinaTheme.Gold)
                    else if (aero) listOf(Color(0xFF7FC4FF), Color(0xFF1B6FC2))
                    else listOf(Color(0xFFFF8A80), Color(0xFFC4392B))
                ),
                shape
            )
            .border(1.dp, Color(0x55000000), shape)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text, color = if (gold) Color(0xFF2B1D00) else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
