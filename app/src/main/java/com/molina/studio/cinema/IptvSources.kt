package com.molina.studio.cinema

import android.content.Context
import android.net.Uri
import com.molina.core.common.M3uResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sumber playlist saluran (maks. 10) di SharedPreferences molina_ui/cinema_iptv, ditambah cache hasil
 * baca selama proses hidup (membuka dialog lagi tidak mengunduh ulang). URL bisa memuat token milik
 * penyedia, jadi hanya disimpan di prefs privat dan tidak pernah ditampilkan penuh: label = host.
 */
internal object IptvSources {
    class Source(val name: String, val url: String)

    private const val PREFS = "molina_ui"
    private const val KEY = "cinema_iptv"
    private const val LAST = "cinema_iptv_last"
    private const val MAX = 10

    private val cache = HashMap<String, M3uResult>()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Source> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Source(o.getString("n"), o.getString("u"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Tambah sumber (atau pindahkan ke depan bila sudah ada). Pemanggil sudah memeriksa URL. */
    fun add(ctx: Context, url: String) {
        val u = url.trim()
        save(ctx, (listOf(Source(nameOf(u), u)) + load(ctx).filter { it.url != u }).take(MAX))
    }

    fun remove(ctx: Context, url: String) {
        save(ctx, load(ctx).filter { it.url != url })
        cache.remove(url)
        if (last(ctx) == url) prefs(ctx).edit().remove(LAST).apply()
    }

    fun last(ctx: Context): String? = prefs(ctx).getString(LAST, null)

    fun setLast(ctx: Context, url: String) {
        prefs(ctx).edit().putString(LAST, url).apply()
    }

    fun cached(url: String): M3uResult? = cache[url]

    fun cachePut(url: String, r: M3uResult) {
        cache[url] = r
    }

    private fun save(ctx: Context, items: List<Source>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("n", it.name).put("u", it.url)) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** Label dari host + nama berkas terakhir (tanpa query/token). */
    private fun nameOf(url: String): String {
        if (url.startsWith("/")) return url.substringAfterLast('/').ifEmpty { "Playlist" }.take(40) // sumber berkas lokal
        val u = runCatching { Uri.parse(url) }.getOrNull()
        val host = u?.host.orEmpty().removePrefix("www.")
        val last = u?.lastPathSegment.orEmpty()
        return (host + (if (last.isNotEmpty()) " / $last" else "")).ifEmpty { "Playlist" }.take(40)
    }
}
