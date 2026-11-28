package com.molina.studio.cinema

import android.content.Context
import com.molina.cinema.MpvPrefs

/**
 * [PREF-LANG] Jembatan Settings Molina -> pemutar mpv untuk bahasa suara dan subtitle utama file lokal.
 * Kunci sama dengan yang dibaca MpvPrefs; berlaku saat video berikutnya dibuka. Nilai berupa kode bahasa
 * dipisah koma (misalnya "id,en"); kosong berarti ikuti pilihan bawaan file.
 */
internal object LanguageBridge {
    fun audio(ctx: Context): String = MpvPrefs.languageValue(ctx, MpvPrefs.KEY_DEFAULT_AUDIO_LANGUAGE)

    fun subtitle(ctx: Context): String = MpvPrefs.languageValue(ctx, MpvPrefs.KEY_DEFAULT_SUBTITLE_LANGUAGE)

    fun setAudio(ctx: Context, v: String) = save(ctx, MpvPrefs.KEY_DEFAULT_AUDIO_LANGUAGE, v)

    fun setSubtitle(ctx: Context, v: String) = save(ctx, MpvPrefs.KEY_DEFAULT_SUBTITLE_LANGUAGE, v)

    /** Rapikan masukan: tanpa spasi, huruf kecil, tanpa segmen kosong. */
    fun normalize(raw: String): String =
        raw.lowercase().replace(" ", "").split(',').filter { it.isNotEmpty() }.joinToString(",")

    private fun save(ctx: Context, key: String, v: String) {
        MpvPrefs.playerPrefs(ctx).edit().putString(key, normalize(v)).apply()
    }
}
