package com.molina.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** [CODE-HEADER-FRAME] Header path editor: hitam pekat dengan bingkai hijau Molina 1dp sebagai pembatas. */
internal fun Modifier.codeHeaderFrame(): Modifier =
    this.background(Color.Black).border(1.dp, CodeColors.Brand)
