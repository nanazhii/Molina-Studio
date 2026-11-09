package com.molina.studio.cinema

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Riwayat putar Molina Cinema (maks. 8, terbaru di atas). SharedPreferences molina_ui/cinema_recent. */
internal object CinemaRecent {
    class Item(val ref: String, val title: String, val video: Boolean) {
        val subtitle: String
            get() = if (ref.startsWith("/")) (File(ref).parent ?: "") else (runCatching { Uri.parse(ref).host }.getOrNull() ?: "")
    }

    /** [RECENT-PLAYLIST] Daftar putar yang berlaku saat entri dipilih (saluran M3U atau hasil pencarian). */
    class Snapshot(val kind: String, val urls: List<String>, val titles: List<String>, val logos: List<String>)

    private fun plDir(ctx: Context) = File(ctx.applicationContext.filesDir, "cinema_recent_pl")

    private fun plFile(ctx: Context, ref: String): File {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(ref.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
        return File(plDir(ctx), "$h.json")
    }

    /** Simpan daftar untuk [ref]; abaikan bila kurang dari 2 item. [titles]/[logos] harus sepanjang [urls]. */
    fun saveSnapshot(ctx: Context, ref: String, kind: String, urls: List<String>, titles: List<String>, logos: List<String> = emptyList()) {
        if (urls.size < 2 || titles.size != urls.size) return
        try {
            plDir(ctx).mkdirs()
            val lg = if (logos.size == urls.size) logos else List(urls.size) { "" }
            val o = JSONObject().put("k", kind).put("u", JSONArray(urls)).put("t", JSONArray(titles)).put("l", JSONArray(lg))
            plFile(ctx, ref).writeText(o.toString())
        } catch (e: Exception) { }
    }

    fun snapshot(ctx: Context, ref: String): Snapshot? = try {
        val f = plFile(ctx, ref)
        if (!f.isFile) null else {
            val o = JSONObject(f.readText())
            fun list(k: String) = o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }
            val u = list("u")
            val t = list("t")
            val l = list("l")
            if (u.size != t.size || u.size != l.size) null else Snapshot(o.getString("k"), u, t, l)
        }
    } catch (e: Exception) {
        null
    }

    private fun prunePlaylists(ctx: Context, items: List<Item>) {
        val keep = items.map { plFile(ctx, it.ref).name }.toSet()
        plDir(ctx).listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    private const val PREFS = "molina_ui"
    private const val KEY = "cinema_recent"
    private const val MAX = 8
    private val AUDIO = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma")

    /** Naik setiap riwayat berubah; dibaca Compose agar daftar ikut tersegarkan. */
    var version by mutableIntStateOf(0)
        private set

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Item> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Item(o.getString("r"), o.getString("t"), o.optBoolean("v", true))
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** [title] null = pakai judul lama bila ada, jika tidak turunkan dari path/URL. Entri sama dipindah ke atas. */
    fun add(ctx: Context, ref: String, title: String?) {
        if (ref.isBlank()) return
        val old = load(ctx)
        val prev = old.firstOrNull { it.ref == ref }
        val name = title?.takeIf { it.isNotBlank() } ?: prev?.title ?: titleOf(ref)
        val item = Item(ref, name, prev?.video ?: !isAudio(ref))
        save(ctx, (listOf(item) + old.filter { it.ref != ref }).take(MAX))
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
        plDir(ctx).deleteRecursively()
        version++
        com.molina.studio.MolinaShortcuts.refresh(ctx)
    }

    private fun save(ctx: Context, items: List<Item>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("r", it.ref).put("t", it.title).put("v", it.video)) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
        prunePlaylists(ctx, items)
        com.molina.studio.MolinaShortcuts.refresh(ctx)
        version++
    }

    private fun isAudio(ref: String): Boolean =
        ref.substringBefore('?').substringAfterLast('.', "").lowercase() in AUDIO

    private fun titleOf(ref: String): String {
        if (ref.startsWith("/")) return File(ref).name
        val u = runCatching { Uri.parse(ref) }.getOrNull()
        val last = u?.lastPathSegment.orEmpty()
        return (u?.host.orEmpty() + (if (last.isNotEmpty()) " / $last" else "")).ifEmpty { ref }
    }
}
