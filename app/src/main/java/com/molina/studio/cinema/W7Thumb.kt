package com.molina.studio.cinema

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import com.molina.core.common.MediaSidecar
import com.molina.core.common.ThumbMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [FILES-THUMB] Thumbnail kecil untuk panel Penjelajah: frame video, atau cover art tertanam pada audio.
 * [SIDE3-THUMB-POSTER] Video yang punya poster sidecar (Film.jpg/.png/.webp dari molina_dl --sidecar) memakai poster itu;
 * poster rusak atau terlalu besar -> kembali ke frame video.
 * Dekode selalu di Dispatchers.IO, hasil disimpan di LruCache (32 entri, maks. 160x90 / 120x120)
 * sehingga thread utama tidak terblokir. Kegagalan dicatat agar tidak dicoba berulang.
 */
internal object W7Thumb {
    private val VIDEO = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "ts", "m2ts", "mts", "flv", "wmv", "mpg", "mpeg", "ogv", "vob", "rmvb"
    )
    private val AUDIO = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "ac3", "dts", "mka")
    private const val MAX_W = 160
    private const val MAX_H = 90
    private const val MAX_COVER = 120
    private const val MAX_POSTER_BYTES = 8L * 1024 * 1024
    private val cache = LruCache<String, Bitmap>(32)
    private val failed: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())

    fun isVideo(f: File): Boolean = f.isFile && f.extension.lowercase() in VIDEO
    fun isAudio(f: File): Boolean = f.isFile && f.extension.lowercase() in AUDIO
    fun hasThumb(f: File): Boolean = isVideo(f) || isAudio(f)

    /**
     * Semua kerja berkas (stat poster, cache, dekode) di Dispatchers.IO. Kunci cache memuat jalur + waktu ubah poster,
     * jadi poster yang baru muncul/berubah menggantikan thumbnail frame lama tanpa menunggu video diubah.
     */
    suspend fun load(f: File): Bitmap? = withContext(Dispatchers.IO) {
        val poster = if (isVideo(f)) MediaSidecar.find(f).poster else null
        val key = f.path + "|" + f.lastModified() + (poster?.let { "|" + it.path + "|" + it.lastModified() } ?: "")
        cache.get(key)?.let { return@withContext it }
        if (key in failed) return@withContext null
        val bmp = if (isAudio(f)) cover(f) else (poster?.let { poster(it) } ?: frame(f))
        if (bmp != null) cache.put(key, bmp) else failed.add(key)
        bmp
    }

    /** [ZIP-PREVIEW] Pratinjau gambar langsung dari entri ZIP (tanpa ekstrak ke disk); maks. 12 MB, sisi terpanjang <= 640 px. */
    suspend fun zipImage(zip: File, entryName: String, password: String?): Bitmap? = withContext(Dispatchers.IO) {
        val bytes = FileOps.readEntryBytes(zip, entryName, password, 12 * 1024 * 1024) ?: return@withContext null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 640 || bounds.outHeight / (sample * 2) >= 640) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            null
        }
    }

    /** [SIDE3-THUMB-POSTER] Poster sidecar didekode hemat memori (inSampleSize); null bila rusak/terlalu besar sehingga pemanggil jatuh ke frame. */
    private fun poster(p: File): Bitmap? {
        return try {
            if (p.length() !in 1L..MAX_POSTER_BYTES) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(p.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply { inSampleSize = ThumbMath.sampleSize(bounds.outWidth, bounds.outHeight, MAX_W, MAX_H) }
            val raw = BitmapFactory.decodeFile(p.path, opts) ?: return null
            fit(raw, MAX_W, MAX_H)
        } catch (e: Exception) {
            null
        }
    }

    private fun frame(f: File): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val atUs = if (durMs > 0L) durMs * 100L else 0L // 10% dari durasi
            val fr = r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            fit(fr, MAX_W, MAX_H)
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }

    /** Cover art tertanam (ID3/MP4/FLAC). Didekode dengan inSampleSize agar cover besar tidak membebani memori. */
    private fun cover(f: File): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            val bytes = r.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_COVER && bounds.outHeight / (sample * 2) >= MAX_COVER) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            fit(raw, MAX_COVER, MAX_COVER)
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }

    private fun fit(src: Bitmap, maxW: Int, maxH: Int): Bitmap? {
        val fw = src.width
        val fh = src.height
        if (fw <= 0 || fh <= 0) return null
        val s = minOf(maxW.toFloat() / fw, maxH.toFloat() / fh, 1f)
        if (s >= 1f) return src
        val out = Bitmap.createScaledBitmap(src, maxOf(1, (fw * s).toInt()), maxOf(1, (fh * s).toInt()), true)
        if (out !== src) src.recycle()
        return out
    }
}
