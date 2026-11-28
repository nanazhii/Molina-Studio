package com.molina.studio.cinema

import android.content.Context
import com.molina.cinema.MpvPrefs

/**
 * [PREF-CENTRAL] Jembatan Settings Molina -> pemutar mpv untuk setelan pemutaran di latar:
 * mode lanjut di latar (never | audio-only | always), hemat data di latar, dan "putar file baru langsung".
 * Kunci sama dengan yang dibaca MpvPrefs (SharedPreferences bawaan); pemutar membacanya saat dimulai/dilanjutkan,
 * jadi perubahan berlaku saat pemutar dibuka berikutnya. Nilai bawaan diambil dari MpvPrefs (satu sumber kebenaran).
 */
internal object PlaybackBridge {
    const val MODE_NEVER = "never"
    const val MODE_AUDIO_ONLY = "audio-only"
    const val MODE_ALWAYS = "always"

    fun backgroundPlay(ctx: Context): String = MpvPrefs.current(ctx).backgroundPlay

    fun setBackgroundPlay(ctx: Context, mode: String) {
        MpvPrefs.playerPrefs(ctx).edit().putString(MpvPrefs.KEY_BACKGROUND_PLAY, mode).apply()
    }

    fun bgVideoOff(ctx: Context): Boolean = MpvPrefs.current(ctx).bgVideoOff

    fun setBgVideoOff(ctx: Context, v: Boolean) {
        MpvPrefs.playerPrefs(ctx).edit().putBoolean(MpvPrefs.KEY_BG_VIDEO_OFF, v).apply()
    }

    fun newIntentReplace(ctx: Context): Boolean = MpvPrefs.current(ctx).newIntentReplace

    fun setNewIntentReplace(ctx: Context, v: Boolean) {
        MpvPrefs.playerPrefs(ctx).edit().putBoolean(MpvPrefs.KEY_NEW_INTENT_REPLACE, v).apply()
    }
}
