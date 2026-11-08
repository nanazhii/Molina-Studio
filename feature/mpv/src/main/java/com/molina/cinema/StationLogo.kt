package com.molina.cinema

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.molina.core.common.UrlPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * [STATION-LOGO] Logo stasiun untuk layar Now Playing stream live. Logo berasal dari atribut tvg-logo di playlist M3U
 * (dibawa lewat memory:// oleh IptvPlayback, atau dibaca dari berkas .m3u lokal). Hanya https; tiap lompatan
 * pengalihan diperiksa ulang UrlPolicy; tanpa cookie/identitas; maks. 600 KB per gambar; hasil disimpan di cache
 * aplikasi agar stasiun yang sama tidak mengunduh ulang. Semua kegagalan diam-diam jatuh ke inisial stasiun.
 */
internal object StationLogo {
    private const val PREFS = "molina_station_logo"
    private const val KEY_ENABLED = "enabled"
    private const val MAX_BYTES = 600_000
    private const val MAX_PX = 512
    private const val MAX_REDIRECTS = 3
    private const val MAX_FILES = 64
    private val LOGO_ATTR = Regex("tvg-logo=\"([^\"]*)\"")
    private val mem = object : LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val failed: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())

    fun enabled(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
    fun setEnabled(ctx: Context, on: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()

    private fun acceptable(u: String): Boolean =
        u.length <= 512 && UrlPolicy.scheme(u) == "https" && UrlPolicy.check(u) is UrlPolicy.Verdict.Allowed

    /** Peta url stream -> url logo dari isi playlist; entri tanpa logo atau ditolak kebijakan dilewati. */
    fun parse(memoryPlaylist: String): Map<String, String> {
        val out = HashMap<String, String>()
        var pending: String? = null
        for (raw in memoryPlaylist.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                if (line.startsWith("#EXTINF", ignoreCase = true)) {
                    pending = LOGO_ATTR.find(line)?.groupValues?.get(1)?.takeIf { acceptable(it) }
                }
                continue
            }
            pending?.let { out[line] = it }
            pending = null
        }
        return out
    }

    /** Satu atau dua huruf awal kata nama stasiun (cadangan bila logo tidak ada). */
    fun initials(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { w -> w.any { it.isLetterOrDigit() } }
        val letters = words.take(2).mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() } }
        return letters.joinToString("").uppercase()
    }

    suspend fun load(ctx: Context, url: String): Bitmap? {
        mem.get(url)?.let { return it }
        if (url in failed || !acceptable(url)) return null
        return withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "station_logos").apply { mkdirs() }
            val f = File(dir, sha1(url))
            val bytes = if (f.isFile && f.length() in 1..MAX_BYTES) f.readBytes() else fetch(url)?.also {
                try { f.writeBytes(it); trim(dir) } catch (_: Exception) {}
            }
            val bmp = bytes?.let { decode(it) }
            if (bmp != null) mem.put(url, bmp) else failed.add(url)
            bmp
        }
    }

    private fun fetch(start: String): ByteArray? {
        var cur = start
        for (hop in 0..MAX_REDIRECTS) {
            if (!acceptable(cur)) return null
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(cur).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000; readTimeout = 6_000; instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "MolinaCinema")
                }
                val code = conn.responseCode
                if (code in 301..303 || code == 307 || code == 308) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    cur = URL(URL(cur), loc).toString()
                    continue
                }
                if (code != 200) return null
                val out = ByteArrayOutputStream()
                conn.inputStream.use { ins ->
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_BYTES) return null
                    }
                }
                return out.toByteArray()
            } catch (e: Exception) {
                return null
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_PX || bounds.outHeight / sample > MAX_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun trim(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
