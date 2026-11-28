package com.molina.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Bingkai dialog Win7: latar biru gelap ke hitam, tepi kaca terang. Dipakai semua dialog Code. */
internal fun Modifier.win7DialogFrame(shape: Shape): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Color(0xFF2C2C2C), Color(0xFF111111), Color(0xFF060606))), shape)
    .border(1.dp, Color(0x99D0D0D0), shape)

/** Tombol dialog Win7. [isDefault] = tepi biru terang; [accent] mewarnai teks (mis. merah untuk Buang). */
@Composable
internal fun Win7DialogButton(label: String, onClick: () -> Unit, isDefault: Boolean = false, accent: Color? = null) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier
            .height(30.dp)
            .widthIn(min = 76.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF202020), Color(0xFF111111))), shape)
            .border(1.dp, if (isDefault) Color(0xFF4DA3FF) else Color(0xFF4D4D4D), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(label, style = TextStyle(color = accent ?: Win7Code.Ink, fontSize = 13.sp))
    }
}

/** Bilah judul kaca Aero dengan tombol tutup merah di kanan atas. */
@Composable
internal fun Win7DialogTitleBar(title: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().win7Aero().padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            text = title,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = Color.White, fontSize = 14.sp)
        )
        Box(
            Modifier
                .width(44.dp)
                .height(22.dp)
                .background(
                    Brush.verticalGradient(listOf(Color(0xFFE8A598), Color(0xFFC4392B))),
                    RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp)
                )
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            BasicText("\u2715", style = TextStyle(color = Color.White, fontSize = 12.sp))
        }
    }
}
