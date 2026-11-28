package com.molina.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.molina.studio.ui.MolinaTheme

/**
 * [BTN-TOKEN] Permukaan tombol Molina: gradien MolinaTheme.Explorer.BtnTop ke BtnBottom dengan garis [line]
 * (bawaan BtnLine; beri DefaultLine/Brand untuk tombol utama atau aktif). SUMBER TUNGGAL gaya tombol, setara
 * MolinaTheme.Tab untuk tab. Tidak memotong (clip) dan tidak mengatur ukuran: pemanggil yang menentukan.
 */
fun Modifier.win7ButtonSurface(shape: Shape, line: Color = MolinaTheme.Explorer.BtnLine): Modifier =
    this.background(Brush.verticalGradient(listOf(MolinaTheme.Explorer.BtnTop, MolinaTheme.Explorer.BtnBottom)), shape)
        .border(1.dp, line, shape)
