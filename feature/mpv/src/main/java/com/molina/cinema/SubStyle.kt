package com.molina.cinema

/*
 * [SUBTITLE-STYLE] Data murni (tanpa Android) untuk Style Editor dan koreksi kecepatan subtitle.
 * Semua properti ini milik mpv/libass yang sudah dibangun di libmpv Molina; tidak ada native baru.
 */

/**
 * Preset gaya. Semua preset HARUS memuat kumpulan kunci yang sama supaya berpindah preset
 * selalu menimpa total gaya sebelumnya (diperiksa di scripts/mpv-tests).
 */
internal data class SubStylePreset(val id: String, val props: List<Pair<String, String>>)

internal object SubStyles {
    const val PREF_PRESET = "sub_style_preset"
    const val PREF_SCALE = "sub_style_scale"
    const val PREF_POS = "sub_style_pos"
    const val PREF_ASS_FORCE = "sub_style_ass_force"

    const val DEFAULT_ID = "default"

    private fun p(color: String, border: String, borderSize: String, shadow: String, bold: String) = listOf(
        "sub-color" to color,
        "sub-border-color" to border,
        "sub-border-size" to borderSize,
        "sub-shadow-offset" to shadow,
        "sub-bold" to bold,
    )

    val PRESETS: List<SubStylePreset> = listOf(
        SubStylePreset(DEFAULT_ID, p("#FFFFFF", "#000000", "3", "0", "no")),      // bawaan mpv
        SubStylePreset("classic", p("#FFFFFF", "#000000", "2", "1", "no")),       // putih, outline tipis
        SubStylePreset("anime", p("#FFEB3B", "#000000", "3.5", "0", "yes")),      // kuning, outline tebal
        SubStylePreset("contrast", p("#FFFFFF", "#000000", "4", "2", "yes")),     // terbaca di adegan terang
    )

    fun byId(id: String?): SubStylePreset? = PRESETS.firstOrNull { it.id == id }

    fun isHexColor(s: String): Boolean = Regex("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$").matches(s)
}

/**
 * Koreksi beda fps untuk subtitle berbasis frame. Nilai `sub-speed` mpv = fps video / fps asumsi subtitle
 * (contoh resmi manual mpv: --sub-speed=25/23.976 untuk subtitle yang dimuat dengan asumsi 23.976 fps
 * dan diputar pada 25 fps). INI KEBALIKAN dari mengalikan timestamp berkas (lihat SrtCodec.scale).
 */
internal data class SubSpeedPreset(val subFps: Double, val videoFps: Double) {
    val speed: Double get() = videoFps / subFps
}

internal object SubSpeeds {
    private const val NTSC_FILM = 24000.0 / 1001.0 // 23,976
    const val PROPERTY = "sub-speed"

    val NORMAL = SubSpeedPreset(1.0, 1.0)

    val PRESETS: List<SubSpeedPreset> = listOf(
        NORMAL,
        SubSpeedPreset(NTSC_FILM, 25.0),
        SubSpeedPreset(25.0, NTSC_FILM),
        SubSpeedPreset(24.0, 25.0),
        SubSpeedPreset(25.0, 24.0),
    )

    /** Indeks preset yang cocok dengan [current], atau -1 (nilai kustom/tidak diketahui). */
    fun indexOf(current: Double?): Int {
        if (current == null) return -1
        return PRESETS.indexOfFirst { kotlin.math.abs(it.speed - current) < 1e-4 }
    }

    /** "23,976 → 25 fps (×1,0427)"; null untuk preset Normal (label dari string resource). */
    fun label(p: SubSpeedPreset): String? {
        if (p == NORMAL) return null
        fun f(v: Double) = if (v == Math.rint(v)) "%.0f".format(v) else "%.3f".format(v)
        return "%s → %s fps (×%.4f)".format(f(p.subFps), f(p.videoFps), p.speed)
    }
}
