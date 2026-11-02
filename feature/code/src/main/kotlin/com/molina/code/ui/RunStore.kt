package com.molina.code.ui

/** [CODE-PYRUN2] Penyimpanan kecil pilihan dialog Jalankan di Terminal. Dipasang :app (SharedPreferences). */
interface RunStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

internal object RunPrefs {
    /** "heredoc" atau "file": mode terakhir yang dipakai. */
    const val KEY_MODE = "run_mode"

    /** "0" = jangan tanya lagi (langsung pakai mode terakhir), selain itu dialog tampil. */
    const val KEY_ASK = "run_ask"

    /** [CODE-FONT] Ukuran font editor (sp), bilangan bulat. Memakai penyimpanan yang sama. */
    const val KEY_FONT_SP = "editor_font_sp"

    /** [CODE-STYLE] Keluarga font editor ("jetbrains", "fira", "system"), ligature ("1"/"0"), sticky scroll ("1"/"0"). */
    const val KEY_FONT_FAMILY = "editor_font_family"
    const val KEY_LIGATURE = "editor_ligature"
    const val KEY_STICKY = "editor_sticky"

    /** [CODE-TM] Tema token TextMate (id) dan saklar highlight TextMate ("1"/"0"). */
    const val KEY_TM_THEME = "editor_tm_theme"
    const val KEY_TM_ON = "editor_tm_on"

    /** [CODE-FORMAT] Saklar "Format saat simpan" ("1" = aktif; bawaan mati). */
    const val KEY_FORMAT_SAVE = "editor_format_save"

    /** [CODE-CHECK-SAVE] Saklar "Periksa saat simpan" ("1" = aktif; bawaan mati). */
    const val KEY_CHECK_SAVE = "editor_check_save"

    /** [CODE-LSP3] Saklar "Diagnostik langsung (Python)" ("1" = aktif; bawaan mati). */
    const val KEY_LSP_LIVE = "editor_lsp_live"

    /** [CODE-HISTORY2] Diff kontras tinggi di Riwayat versi ("1" = aktif; bawaan mati). */
    const val KEY_DIFF_CONTRAST = "editor_diff_contrast"
}
