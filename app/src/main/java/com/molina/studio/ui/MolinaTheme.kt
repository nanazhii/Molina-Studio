package com.molina.studio.ui

import androidx.compose.ui.graphics.Color
import com.molina.studio.ui.MolinaTheme

/** Token desain Molina: satu sumber warna merek dan status untuk seluruh UI dan shell. */
object MolinaTheme {
    // Aksen merek (hijau zamrud), acuan visual #2ECC71. Nilai ARGB dan SGR (ANSI truecolor) harus tetap sama.
    const val BRAND_ARGB = 0xFF2ECC71.toInt()
    const val BRAND_SGR = "38;2;46;204;113"

    val Brand = Color(BRAND_ARGB)
    val Background = Color(0xFF0A0A0F)
    val OnBackground = Color(0xFFEDEDED)
    // Hijau status (#9BE564), sengaja beda hue dari Brand (zamrud). Nilai ARGB dan SGR (ANSI truecolor) harus tetap sama.
    const val GOOD_ARGB = 0xFF9BE564.toInt()
    const val GOOD_SGR = "38;2;155;229;100"
    val Good = Color(GOOD_ARGB)
    val Warn = Color(0xFFFFD54F)
    val Bad = Color(0xFFFF8A80)
    /** Aksen Molina Cinema (ikon putar, penanda baris log mpv). */
    val Cinema = Color(0xFFB388FF)
    /** Kecepatan jaringan di bilah sesi Terminal: unduh biru, unggah merah. */
    val NetDown = Color(0xFF82B1FF)
    val NetUp = Bad
    val Muted = Color(0xFF9AA0A6)
    val CardBg = Color(0xFF14161B)
    val CardLine = Color(0xFF2A2D35)

    /** Palet Penjelajah Molina Cinema (dulu token Win7). Garis tombol utama memakai aksen merek. */
    object Explorer {
        val FrameTop = Color(0xFF1B1B1B)
        val FrameBottom = Color(0xFF0A0A0A)
        val FrameLine = Color(0xFF3A3A3A)
        val Face = Color(0xFF000000)
        val NavBg = Color(0xFF070707)
        val NavHeader = Color(0xFF8FC4EE)
        val Ink = Color(0xFFEDEDED)
        val TextDim = Color(0xFFA3A3A3)
        val Line = Color(0xFF5C5C5C)
        val Field = Color(0xFF3A3A3A)
        val Hairline = Color(0xFF262626)
        val SelFill = Color(0xFF12263A)
        val SelLine = Color(0xFF3C7FB1)
        val Folder = Color(0xFFF3CF6E)
        val FolderDark = Color(0xFFD9A93B)
        val CloseTop = Color(0xFFE8A598)
        val CloseBottom = Color(0xFFC4392B)
        val BtnTop = Color(0xFF202020)
        val BtnBottom = Color(0xFF111111)
        val BtnLine = Color(0xFF4D4D4D)
        val DefaultLine = MolinaTheme.Brand
        val Input = Color(0xFF101010)
        val BarTop = Color(0xFF151515)
        val BarBottom = Color(0xFF060606)
    }
}
