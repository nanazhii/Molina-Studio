package com.molina.studio.security

import android.content.Context
import com.molina.core.common.IntentToken
import java.security.SecureRandom

/**
 * [INTENT-TOKEN] Token acak per-pemasangan untuk intent pintasan milik Molina sendiri (putar terakhir, jalankan skrip).
 * Disimpan di SharedPreferences privat; dibuat saat pertama dipakai. Aplikasi lain tidak bisa menebaknya, jadi tidak
 * bisa memicu aksi itu lewat MainActivity yang exported. Hapus data aplikasi = token baru; pintasan diperbarui otomatis
 * saat aplikasi dibuka (MolinaShortcuts.refresh).
 */
object ShortcutToken {
    const val EXTRA = "molina_shortcut_token"
    private const val FILE = "molina_shortcut_token"
    private const val KEY = "token"

    @Synchronized
    fun get(ctx: Context): String {
        val sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val cur = sp.getString(KEY, null)
        if (cur != null && cur.length >= IntentToken.MIN_LENGTH) return cur
        val t = IntentToken.encode(ByteArray(16).also { SecureRandom().nextBytes(it) })
        sp.edit().putString(KEY, t).commit()
        return t
    }

    fun verify(ctx: Context, given: String?): Boolean = IntentToken.matches(get(ctx), given)
}
