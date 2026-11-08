package com.molina.cinema

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.util.LruCache
import java.io.File

/**
 * [COVER-ART] Pemuat sampul album untuk layar "Now Playing" Molina Cinema.
 * Urutan sumber: gambar yang disematkan di berkas (mis. oleh molina_dl + mutagen), lalu berkas
 * cover/folder/front/album/albumart (.jpg/.jpeg/.png/.webp) di folder yang sama.
 * Hanya berkas lokal. Blokir: panggil dari thread IO, bukan thread utama.
 */
internal object CoverArt {
    /** [art] kartu sampul (sudut membulat), [backdrop] versi kecil untuk latar buram, [thumb] untuk notifikasi/sesi media. */
    class Cover(val art: Bitmap, val backdrop: Bitmap, val thumb: Bitmap)

    internal class Entry(val cover: Cover?)

    private const val MAX_ART_PX = 1024
    private const val BACKDROP_PX = 24
    private const val THUMB_PX = 384
    private const val MIN_PX = 64
    private const val CACHE_BYTES = 12 * 1024 * 1024
    private val NAMES = listOf("cover", "folder", "front", "album", "albumart")
    private val EXTS = setOf("jpg", "jpeg", "png", "webp")

    // Cache berbasis ukuran byte (juga menyimpan "tidak ada sampul" agar tidak memindai ulang).
    private val cache = object : LruCache<String, Entry>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Entry): Int {
            val c = value.cover ?: return 1
            return maxOf(1, c.art.byteCount + c.backdrop.byteCount + c.thumb.byteCount)
        }
    }

    @Volatile private var latestPath: String? = null
    @Volatile private var latestThumb: Bitmap? = null

    fun normalize(path: String): String = path.removePrefix("file://")

    /** Thumbnail sampul untuk [path] bila sampul trek itu baru saja dimuat; null bila tidak cocok. */
    fun latestThumbFor(path: String): Bitmap? =
        if (normalize(path) == latestPath) latestThumb else null

    fun load(rawPath: String): Cover? {
        val path = normalize(rawPath)
        cache.get(path)?.let { remember(path, it.cover); return it.cover }
        val cover = try {
            build(path)
        } catch (e: OutOfMemoryError) {
            MpvLog.w(TAG, "Sampul dilewati: memori tidak cukup ($path)")
            null
        } catch (e: Exception) {
            MpvLog.w(TAG, "Sampul gagal dimuat ($path): ${e.message}")
            null
        }
        cache.put(path, Entry(cover))
        remember(path, cover)
        MpvLog.v(TAG, "Sampul ${if (cover != null) "ditemukan" else "tidak ada"}: $path")
        return cover
    }

    private fun remember(path: String, cover: Cover?) {
        latestPath = path
        latestThumb = cover?.thumb
    }

    private fun build(path: String): Cover? {
        val src = embedded(path)?.let { decode(it) } ?: sibling(path)?.let { decode(it) } ?: return null
        if (maxOf(src.width, src.height) < MIN_PX) return null
        val backdrop = backdrop(src)
        val thumb = scaleDown(src, THUMB_PX)
        return Cover(rounded(src), backdrop, thumb)
    }

    private fun embedded(path: String): ByteArray? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            r.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    private fun sibling(path: String): File? {
        val files = File(path).parentFile?.listFiles() ?: return null
        for (name in NAMES) {
            files.firstOrNull {
                it.isFile && it.nameWithoutExtension.equals(name, ignoreCase = true) &&
                    it.extension.lowercase() in EXTS
            }?.let { return it }
        }
        return null
    }

    private fun sampleFor(w: Int, h: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= MAX_ART_PX) s *= 2
        return s
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        if (b.outWidth <= 0 || b.outHeight <= 0) return null
        val o = BitmapFactory.Options().apply { inSampleSize = sampleFor(b.outWidth, b.outHeight) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    }

    private fun decode(file: File): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, b)
        if (b.outWidth <= 0 || b.outHeight <= 0) return null
        val o = BitmapFactory.Options().apply { inSampleSize = sampleFor(b.outWidth, b.outHeight) }
        return BitmapFactory.decodeFile(file.path, o)
    }

    private fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val m = maxOf(src.width, src.height)
        if (m <= maxDim) return src.copy(Bitmap.Config.ARGB_8888, false)!! // null hanya saat OOM; ditangkap di load()
        val s = maxDim.toFloat() / m
        return Bitmap.createScaledBitmap(src, maxOf(1, (src.width * s).toInt()), maxOf(1, (src.height * s).toInt()), true)
    }

    /** Latar buram murah (tanpa RenderEffect, API 26): kecilkan dua tahap lalu biarkan ImageView memperbesarnya dengan filter. */
    private fun backdrop(src: Bitmap): Bitmap {
        val mid = scaleDown(src, BACKDROP_PX * 4)
        val out = scaleDown(mid, BACKDROP_PX)
        if (mid !== out && mid !== src) mid.recycle()
        return out
    }

    private fun rounded(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val r = minOf(src.width, src.height) * 0.04f
        Canvas(out).drawRoundRect(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), r, r, paint)
        src.recycle()
        return out
    }

    private const val TAG = "cover"
}
