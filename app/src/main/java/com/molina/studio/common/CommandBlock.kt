package com.molina.studio.common

import com.molina.studio.ui.AeroDark
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.code.preview.CommandBlockSpec

/**
 * [CODE-CMDBLOCK] Blok perintah/skrip standar Molina di :app: monospace, tanpa bungkus baris (geser horizontal),
 * palet AeroDark. Ukuran dari [CommandBlockSpec] (sama dengan dialog Code).
 */
@Composable
internal fun CommandBlock(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(CommandBlockSpec.CORNER_DP.dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AeroDark.Field)
            .border(CommandBlockSpec.BORDER_DP.dp, AeroDark.Hairline, shape)
            .horizontalScroll(rememberScrollState())
            .padding(CommandBlockSpec.PADDING_DP.dp)
    ) {
        Text(
            text,
            color = AeroDark.Ink,
            fontSize = CommandBlockSpec.FONT_SP.sp,
            lineHeight = CommandBlockSpec.LINE_HEIGHT_SP.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false
        )
    }
}
