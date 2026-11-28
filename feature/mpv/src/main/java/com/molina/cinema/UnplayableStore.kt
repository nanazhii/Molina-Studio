package com.molina.cinema

import android.content.Context
import com.molina.core.net.UnplayableUrls

/**
 * [UNPLAYABLE-CHIP] Penyimpanan kecil (SharedPreferences molina_unplayable) untuk URL channel yang membalas
 * manifest XML. Ditulis pemutar saat [JUNK-GUARD] menyapu, dibaca Cinema untuk chip DRM dan antrean.
 */
object UnplayableStore {
    private const val PREFS = "molina_unplayable"
    private const val KEY = "v1"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context): Set<String> =
        UnplayableUrls.parse(prefs(ctx).getString(KEY, null), System.currentTimeMillis()).keys.toSet()

    /** Mengembalikan "ke-N kali" (0 bila URL tidak valid). [ERR-COUNT] */
    @Synchronized
    fun add(ctx: Context, url: String): Int {
        val now = System.currentTimeMillis()
        val map = UnplayableUrls.parse(prefs(ctx).getString(KEY, null), now)
        val n = UnplayableUrls.add(map, url, now)
        if (n > 0) prefs(ctx).edit().putString(KEY, UnplayableUrls.serialize(map)).apply()
        return n
    }

    /** [ERR-CLEAR-OK] Hapus tanda ERR satu URL (mis. setelah berhasil dimuat/dipulihkan). True bila ada yang dihapus. */
    @Synchronized
    fun remove(ctx: Context, url: String): Boolean {
        val now = System.currentTimeMillis()
        val raw = prefs(ctx).getString(KEY, null)
        if (raw.isNullOrEmpty()) return false
        val map = UnplayableUrls.parse(raw, now)
        if (map.remove(url) == null) return false
        prefs(ctx).edit().putString(KEY, UnplayableUrls.serialize(map)).apply()
        return true
    }

    @Synchronized
    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }
}
