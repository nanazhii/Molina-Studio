package com.molina.code.ui

import androidx.compose.ui.graphics.Color

/**
 * Token warna Code Editor "Molina Cinema". Satu-satunya sumber warna untuk layar Code.
 * Acuan: docs/blueprint-code-editor.md bagian 13.2 (v2) dan 22 (v3). Nilai v1 yang digantikan
 * v2 tidak dipakai. Jangan hardcode warna di layar; tambahkan token di sini.
 */
object CodeColors {
    // Permukaan: keluarga "hitam dingin", mengikuti nav/app (Opsi B, blueprint bagian 25)
    val EditorBg = Color(0xFF000000)
    val GutterBg = Color(0xFF121212)
    val Surface1 = Color(0xFF14161B)      // header path, tab bar
    val Surface2 = Color(0xFF181B21)      // toolbar, find bar
    val SurfaceDrawer = Color(0xFF101218) // panel direktori
    val SurfaceMenu = Color(0xFF12151A)   // popup titik tiga
    val SurfaceDialog = Color(0xFF171A20) // dialog
    val SurfaceNav = Color(0xFF0A0A0F)    // bottom nav aktual (= MolinaTheme.Background)
    val Hairline = Color(0xFF2A2D35)      // pembatas 1dp, border popup/dialog

    // Ikon
    val IconDefault = Color(0xFFCDBFA3)   // champagne
    val MenuText = Color(0xFFFFFFFF)      // [MENU-WHITE] teks dan ikon menu titik tiga: putih polos
    val MenuDanger = Color(0xFFFF5252)    // [MENU-WHITE] aksi Jalankan di Terminal: merah
    val InfoBlue = Color(0xFF4FC3F7)      // [STATUS-BLUE] info Ln/Kol dan jumlah karakter di bilah status
    val IconDim = Color(0xFF7D7A72)
    val IconDisabled = Color(0xFF4D4C48)

    // Aksen (maksimal dua di toolbar: Gold dan Danger)
    val Gold = Color(0xFFFFB300)
    val GoldSoft = Color(0xFFE9B949)
    val FolderYellow = GoldSoft
    val Danger = Color(0xFFE5484D)
    val Clean = Color(0xFF3FB950)               // indikator berkas sama dengan isi awal (hijau)
    val Capsule = Color(0xFF4E3B14)       // kapsul aktif, sama dengan bottom nav

    // Editor
    val ActiveLine = Color(0xFF38280F)
    // Nomor baris bernuansa Claude (terakota): redup untuk baris biasa, terang untuk baris kursor,
    // putih-persik + latar terakota sesaat saat Pergi ke baris / lompat hasil Cari.
    val ClaudeOrange = Color(0xFFD97757)
    val LineNumber = Color(0xFF8A4F3C)
    val LineNumberCurrent = Color(0xFF5AB0FF)   // biru: nomor baris aktif beda dari terakota di sekitarnya [CODE-LINENUM-BLUE]
    val LineNumberFlash = Color(0xFFFFD9C7)
    val LineNumberPanelBg = Color(0xFF3A2218)   // panel nomor baris saat scroll cepat
    val ActiveLineFlash = Color(0xFF5A2E1F)
    val TextPrimary = Color(0xFFE6E6E6)
    val TextSecondary = Color(0xFF9AA0A6)
    val ButtonBrown = Color(0xFF8F5A2C)

    // Garis penanda ikon berkas per ekstensi di panel direktori (blueprint bagian 5.3)
    val ExtKotlin = Color(0xFFB07CFF)
    val ExtPython = Color(0xFF4FA3E0)
    val ExtMarkdown = Color(0xFF7BC8A4)
    val ExtJson = GoldSoft
    val ExtXml = Color(0xFFE08A4F)
    val ExtShell = Color(0xFF8BD35C)
    val ExtDefault = IconDim

    // Turunan beralfa (nilai alfa dari blueprint)
    val Ripple = Gold.copy(alpha = 0.16f)
    val SelectFill = Gold.copy(alpha = 0.18f)     // seleksi baris tree
    val SelectBorder = Gold.copy(alpha = 0.45f)
    val EditorSelection = Color(0x80D97757)   // blok/hasil aktif: terakota Claude 50%
    val SearchMatch = Color(0x47D97757)       // hasil lain: terakota Claude 28%
    val RailPeak = Gold.copy(alpha = 0.40f)       // puncak gradient rail emas toolbar
    val LabelChampagne = IconDefault.copy(alpha = 0.70f)
    val SecondaryChampagne = IconDefault.copy(alpha = 0.60f)
    val Caret = Gold
    val Scrim = Color.Black.copy(alpha = 0.55f)

    // Layar Tinjauan (mode gelap saja; toggle terang dihapus)
    val PreviewBg = Color(0xFF000000)
    val PreviewText = Color(0xFFE6E6E6)
    val PreviewLink = Gold
    val PreviewCodeBg = Color(0xFF121212)
    val PreviewQuoteBg = Color(0xFF0D0B09)

    // Pratinjau native (blueprint bagian 21.3 dan 22) dan panel Keluaran skrip
    val PreviewHairline = Color(0xFF3A2F22)
    val PreviewHeading = GoldSoft
    val PreviewInlineCode = GoldSoft
    val PreviewInlineCodeBg = Color(0xFF1C1814)
    val PreviewQuoteText = IconDefault
    val PreviewQuoteBar = Gold.copy(alpha = 0.60f)
    val PreviewTableHeadBg = Color(0xFF1C1814)
    val JsonKey = Color(0xFFE3A987)
    val JsonString = Color(0xFF9DBE8A)
    val JsonNumber = Color(0xFFE5C07B)
    val JsonLiteral = Color(0xFFD97757)
    val JsonPunct = Color(0xFFC9B8A8)
    val OutputText = TextPrimary
    val OutputError = Danger
    val OutputOk = Clean

    // Alfa state
    const val DisabledAlpha = 0.38f
    const val HiddenFileAlpha = 0.60f
    const val PressedFillAlpha = 0.10f
}
