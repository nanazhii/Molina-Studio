package com.molina.studio.cinema

import android.content.Context
import android.net.Uri
import com.molina.core.net.M3uResult
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

    /** [IPTV-BUNDLED] Feed Molina dibawa di APK (tertanda tangan bersama aplikasi), bukan diunduh. Tidak disimpan di prefs. */
    const val BUNDLED_FEED = "asset://molina-feed"
    const val BUNDLED_ASSET = "molina/iptv/id.verified.m3u"
    /** [RADIO-BUNDLED] Feed radio Jawa Timur dibawa di APK (aset), dikelompokkan per kota lewat group-title. Bukan bagian feed tepercaya IPTV. */
    const val RADIO_JATIM_FEED = "asset://molina-radio-jatim"
    const val RADIO_JATIM_ASSET = "molina/radio/jawa-timur.m3u"
    /** [IPTV-NAT-HTTP] Daftar opsional channel nasional HTTP. BUKAN bagian dari feed tepercaya: tidak pernah dimuat otomatis, hanya disalin ke folder IPTV atas konfirmasi pengguna. */
    const val NATIONAL_HTTP_ASSET = "molina/iptv/nasional-http.m3u"
    const val NATIONAL_HTTP_FILE = "Nasional-HTTP.m3u"

    /** Sumber lama di prefs dari versi sebelumnya (URL feed di repo); digantikan sumber bawaan dan tidak dimuat lagi. */
    private const val LEGACY_FEED_PREFIX = "https://raw.githubusercontent.com/nanazhii/Molina-Studio/iptv-data/"

    fun isBundled(ref: String?): Boolean = ref == BUNDLED_FEED || ref == RADIO_JATIM_FEED // [RADIO-BUNDLED] isBundled

    /** [RADIO-BUNDLED] Nama aset untuk sumber bawaan, atau null bila bukan sumber bawaan. */
    fun bundledAssetFor(ref: String?): String? = when (ref) {
        BUNDLED_FEED -> BUNDLED_ASSET
        RADIO_JATIM_FEED -> RADIO_JATIM_ASSET
        else -> null
    }

    private val cache = HashMap<String, M3uResult>()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Source> = listOf(Source("Molina Feed", BUNDLED_FEED), Source("Radio Jawa Timur", RADIO_JATIM_FEED)) + loadSaved(ctx) // [IPTV-BUNDLED] [RADIO-BUNDLED] load: selalu di depan, tidak bisa dihapus

    private fun loadSaved(ctx: Context): List<Source> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Source(o.getString("n"), o.getString("u"))
        }.filter { !isBundled(it.url) && !it.url.startsWith(LEGACY_FEED_PREFIX) } // [IPTV-BUNDLED] [RADIO-BUNDLED] filter
    } catch (e: Exception) {
        emptyList()
    }

    /** Tambah sumber (atau pindahkan ke depan bila sudah ada). Pemanggil sudah memeriksa URL. */
    fun add(ctx: Context, url: String) {
        val u = url.trim()
        if (isBundled(u) || u.startsWith(LEGACY_FEED_PREFIX)) return // [IPTV-BUNDLED]
        save(ctx, (listOf(Source(nameOf(u), u)) + loadSaved(ctx).filter { it.url != u }).take(MAX))
    }

    fun remove(ctx: Context, url: String) {
        if (isBundled(url)) return // [IPTV-BUNDLED]
        save(ctx, loadSaved(ctx).filter { it.url != url })
        cache.remove(url)
        if (last(ctx) == url) prefs(ctx).edit().remove(LAST).apply()
    }

    fun last(ctx: Context): String? = prefs(ctx).getString(LAST, null)

    fun setLast(ctx: Context, url: String) {
        prefs(ctx).edit().putString(LAST, url).apply()
    }

    fun cached(url: String): M3uResult? = cache[url]

    /** [ERR-PRUNE] Buang hasil baca tersimpan (berkas berubah di disk); sumber tetap ada. */
    fun invalidate(url: String) {
        cache.remove(url)
    }

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
