package com.molina.code.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val BACK_PILL_MS = 8_000L

/**
 * [CODE-BACK] Pil "Kembali ke nama:baris" yang tampil beberapa detik setelah lompat (definisi, simbol, cari di berkas).
 * Ketuk = kembali ke posisi sebelum lompatan; menu titik tiga dan menu tekan-lama editor punya jalur yang sama.
 */
@Composable
internal fun BackPill(session: CodeSession, modifier: Modifier = Modifier) {
    val hint = session.backHint
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(BACK_PILL_MS)
            if (session.backHint == hint) session.backHint = null
        }
    }
    if (hint == null || session.backDepth <= 0) return
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .background(CodeColors.Surface2, shape)
            .border(1.dp, CodeColors.Gold.copy(alpha = 0.55f), shape)
            .clickable { session.jumpBack() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(CodeDimens.MenuIconSize)) { drawMenuIcon(MenuIcon.Back, CodeColors.Gold) }
        Spacer(Modifier.width(8.dp))
        BasicText(
            "Kembali ke $hint",
            style = TextStyle(color = CodeColors.TextPrimary, fontSize = 12.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 240.dp),
        )
    }
}
