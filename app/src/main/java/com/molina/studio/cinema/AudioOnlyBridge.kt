package com.molina.studio.cinema

import android.content.Context

/**
 * Jembatan Settings Molina -> pemutar mpv untuk "Audio saja untuk video". Memakai kunci yang sama dengan
 * Setelan pemutar (audio_only_video, bawaan mati); MpvPrefs membacanya saat pemutar dimulai, jadi perubahan
 * berlaku saat pemutar dibuka berikutnya. Satu sumber kebenaran: dua layar menulis ke kunci yang sama.
 */
internal object AudioOnlyBridge {
    private const val KEY = com.molina.cinema.MpvPrefs.KEY_AUDIO_ONLY_VIDEO // [SSOT-AUDIO-KEY]

    private fun sp(ctx: Context) = com.molina.cinema.MpvPrefs.playerPrefs(ctx) // [SSOT-PREFS]

    fun enabled(ctx: Context): Boolean = try {
        sp(ctx).getBoolean(KEY, false)
    } catch (e: ClassCastException) {
        false
    }

    fun setEnabled(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY, v).apply()
    }
}
