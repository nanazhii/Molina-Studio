package com.molina.cinema

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Bingkai pratinjau untuk bilah seek. Hanya berkas lokal (stream tidak didukung). Semua akses
 * MediaMetadataRetriever berjalan di satu thread latar; permintaan lama dilewati bila sudah ada yang lebih baru.
 */
class SeekThumbnailer {
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "molina-seek-thumb").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private var retriever: MediaMetadataRetriever? = null // hanya diakses di worker
    private var openPath: String? = null // hanya diakses di worker
    private var wantedPath: String? = null // hanya diakses di main thread
    @Volatile private var generation = 0
    @Volatile private var released = false

    /** True bila media saat ini berkas lokal yang bisa diambil bingkainya. */
    @Volatile
    var available = false
        private set

    fun prepare(path: String?) {
        if (released) return
        available = path != null
        if (path == wantedPath) return
        wantedPath = path
        worker.execute {
            if (openPath == path) return@execute
            closeNow()
            if (path == null) return@execute
            val r = MediaMetadataRetriever()
            retriever = try {
                r.setDataSource(path)
                r
            } catch (e: Exception) {
                try {
                    r.release()
                } catch (ignored: Exception) {
                }
                available = false
                null
            }
            openPath = path
        }
    }

    fun request(sec: Double, cb: (Bitmap?) -> Unit) {
        if (released) return
        val gen = ++generation
        worker.execute {
            if (gen != generation) return@execute
            val r = retriever
            var out: Bitmap? = null
            if (r != null) {
                try {
                    val frame = r.getFrameAtTime((sec * 1_000_000.0).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frame != null) {
                        val w = 192
                        val h = (frame.height * w / frame.width.toFloat()).toInt().coerceAtLeast(1)
                        out = Bitmap.createScaledBitmap(frame, w, h, true)
                        if (out !== frame) frame.recycle()
                    }
                } catch (e: Exception) {
                    out = null
                }
            }
            if (gen == generation) main.post { if (gen == generation) cb(out) }
        }
    }

    fun cancel() {
        generation++
    }

    fun release() {
        if (released) return
        released = true
        generation++
        worker.execute { closeNow() }
        worker.shutdown()
    }

    private fun closeNow() {
        try {
            retriever?.release()
        } catch (ignored: Exception) {
        }
        retriever = null
        openPath = null
    }
}
