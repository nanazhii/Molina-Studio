package com.molina.studio.terminal

import com.molina.studio.ui.MolinaTheme
import com.molina.terminal.TerminalColors
import com.molina.terminal.TextStyle

/** Varian warna dasar terminal. Palet ANSI 0-15 (Molina Noir) sama untuk semua; beda di teks, latar, kursor. */
enum class TerminalLook(val id: String, val label: String, val fg: Int, val bg: Int, val cursor: Int) {
    NoirBrand("noir_gold", "Noir Emerald", 0xFFEDEDED.toInt(), 0xFF0A0A0F.toInt(), MolinaTheme.BRAND_ARGB),
    CinemaViolet("cinema_violet", "Cinema Violet", 0xFFE6E0F5.toInt(), 0xFF0A0A0F.toInt(), 0xFFB388FF.toInt()),
    AmberCrt("amber_crt", "Amber CRT", 0xFFFFB74D.toInt(), 0xFF0D0A05.toInt(), 0xFFFFB74D.toInt());

    companion object {
        fun fromId(id: String?): TerminalLook = entries.firstOrNull { it.id == id } ?: NoirBrand
    }
}

object TerminalLooks {
    /** Tulis warna dasar ke skema global; emulator yang dibuat sesudahnya langsung memakainya. */
    fun applyDefaults(look: TerminalLook) {
        val d = TerminalColors.COLOR_SCHEME.mDefaultColors
        d[TextStyle.COLOR_INDEX_FOREGROUND] = look.fg
        d[TextStyle.COLOR_INDEX_BACKGROUND] = look.bg
        d[TextStyle.COLOR_INDEX_CURSOR] = look.cursor
    }

    /** Terapkan ke skema global dan sesi yang sedang terbuka, lalu gambar ulang layar. */
    fun applyLive(look: TerminalLook) {
        applyDefaults(look)
        TerminalSessions.sessions.toList().forEach { it.emulator?.mColors?.reset() }
        TerminalSessions.client.view?.let { v -> v.post { v.onScreenUpdated() } }
    }
}
