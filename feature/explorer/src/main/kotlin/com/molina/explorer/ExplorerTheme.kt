package com.molina.explorer

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.molina.studio.ui.MolinaTheme

// [EXPLORER-THEME-2] Aksen Cinema dalam satu konstanta. Mau mengikuti ungu merek Cinema (MolinaTheme.Cinema)?
// Ganti dua baris ini menjadi: CINEMA_ACCENT = MolinaTheme.Cinema dan CINEMA_ON_ACCENT = Color(0xFF1A0F33).
private val CINEMA_ACCENT = Color(0xFFE0A93B)
private val CINEMA_ON_ACCENT = Color(0xFF2B1B00)

/**
 * [EXPLORER-THEME] Palet Penjelajah per produk, disuplai lewat [ExplorerEnv.theme]. Dipakai lembar arsip (ZIP dan 7z/tar)
 * dan panel Penjelajah. Dasar hitam, ink, dim, dan bingkai dibagi bersama; yang berbeda per produk hanya aksen,
 * LED ikon drive, dan warna baris terpilih.
 */
@Immutable
class ExplorerTheme(
    // lembar arsip
    val bg: Color,
    val surface: Color,
    val ink: Color,
    val dim: Color,
    val frame: Color,
    val accent: Color,
    val onAccent: Color,
    val warn: Color,
    // [EXPLORER-THEME-2] panel Penjelajah
    val brand: Color,
    val star: Color,
    val driveLed: Color,
    val selTop: Color,
    val selBottom: Color,
) {
    companion object {
        /** Molina Terminal: tampilan lama dipertahankan persis (sheet teal, panel hijau zamrud, LED drive hijau). */
        val Terminal = ExplorerTheme(
            bg = Color(0xFF000000), surface = Color(0xFF0D0D0D), ink = Color(0xFFE6EAEE), dim = Color(0xFFA9B3BD),
            frame = Color(0xFF9AA0A8), accent = Color(0xFF00BFA5), onAccent = Color(0xFF00201B), warn = Color(0xFF87CF3E),
            brand = MolinaTheme.Brand, star = Color(0xFFE0A800), driveLed = Color(0xFF3CC13C),
            selTop = Color(0xFF16324D), selBottom = Color(0xFF0F2438),
        )

        /** Molina Cinema: aksen hangat ala lampu home theater; baris terpilih bernuansa hangat, bukan biru. */
        val Cinema = ExplorerTheme(
            bg = Color(0xFF000000), surface = Color(0xFF100D08), ink = Color(0xFFEFE9DF), dim = Color(0xFFB5AB9C),
            frame = Color(0xFFA39A8A), accent = CINEMA_ACCENT, onAccent = CINEMA_ON_ACCENT, warn = Color(0xFF87CF3E),
            brand = CINEMA_ACCENT, star = Color(0xFFE0A800), driveLed = CINEMA_ACCENT,
            selTop = Color(0xFF3A2A10), selBottom = Color(0xFF2A1E0B),
        )
    }
}

internal val LocalExplorerTheme = staticCompositionLocalOf { ExplorerTheme.Terminal }
