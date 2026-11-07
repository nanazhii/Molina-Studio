package com.molina.studio.cinema

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.molina.core.common.ThumbMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * [RESULTS-THUMB] Pemuat thumbnail hasil pencarian tanpa pustaka tambahan.
 * Hanya https ke host YouTube (ALLOWED), maks. 4 unduhan paralel, batas 1,5 MB per gambar, timeout pendek,
 * dekode RGB_565 dengan inSampleSize (maks. 320x180), LruCache berbasis byte (6 MB), kegagalan dicatat agar tidak diulang.
 */
internal object SearchThumbs {
    private val ALLOWED = listOf("ytimg.com", "ggpht.com")
    private const val MAX_W = 320
    private const val MAX_H = 180
    private const val MAX_BYTES = 1_500_000
    private val gate = Semaphore(4)
    private val failed: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())
    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun allowed(url: String): Boolean = try {
        val u = URL(url)
        u.protocol == "https" && ALLOWED.any { u.host == it || u.host.endsWith(".$it") }
    } catch (e: Exception) {
        false
    }

    suspend fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        if (url in failed || !allowed(url)) return null
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                cache.get(url)?.let { return@withPermit it }
                val bmp = fetch(url)
                if (bmp != null) cache.put(url, bmp) else failed.add(url)
                bmp
            }
        }
    }

    private fun fetch(url: String): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 6_000
                instanceFollowRedirects = false
            }
            if (conn.responseCode != 200) return null
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
            val bytes = out.toByteArray()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = ThumbMath.sampleSize(bounds.outWidth, bounds.outHeight, MAX_W, MAX_H)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
        }
    }
}
