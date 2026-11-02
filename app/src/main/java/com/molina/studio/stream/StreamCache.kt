package com.molina.studio.stream

import android.content.Context
import com.molina.core.common.YtdlpFetch
import java.io.File
import java.io.IOException

/**
 * Cache video hasil unduhan yt-dlp (mode unduh-ke-cache, lihat [YtdlpFetch]). Berada di dalam
 * rootfs Debian (tmp/molina-stream); jumlah dan ukurannya dibatasi agar tidak menumpuk.
 */
internal object StreamCache {
    private const val KEEP_FILES = 5
    private const val MAX_BYTES = 300L * 1024 * 1024
    private const val STALE_PARTIAL_MS = 10 * 60 * 1000L

    fun dir(ctx: Context): File = YtdlpLocator.hostFile(ctx, YtdlpFetch.CACHE_GUEST_DIR)

    /** Berkas host untuk jalur guest; null bila tidak ada atau (setelah symlink dipetakan) di luar [dir]. */
    fun resolveHost(ctx: Context, guestPath: String): File? {
        val f = YtdlpLocator.hostFile(ctx, guestPath)
        return try {
            val root = dir(ctx).canonicalPath + File.separator
            if (f.canonicalPath.startsWith(root) && f.isFile) f else null
        } catch (e: IOException) {
            null
        }
    }

    /** Hapus seluruh isi cache (dipakai Refresh Pemutar). Aman dipanggil setelah mpv berhenti; mengembalikan jumlah berkas terhapus. */
    fun clear(ctx: Context): Int {
        val files = dir(ctx).listFiles() ?: return 0
        var n = 0
        for (f in files) if (f.isFile && f.delete()) n++
        return n
    }

    /** Sisakan [KEEP_FILES] berkas terbaru dalam batas [MAX_BYTES]; sisa unduhan setengah jadi yang basi dibuang. */
    fun prune(ctx: Context) {
        val files = dir(ctx).listFiles()?.filter { it.isFile } ?: return
        val now = System.currentTimeMillis()
        var kept = 0
        var bytes = 0L
        for (f in files.sortedByDescending { it.lastModified() }) {
            val partial = f.name.endsWith(".part") || f.name.endsWith(".ytdl")
            val drop = if (partial) {
                now - f.lastModified() > STALE_PARTIAL_MS
            } else {
                kept >= KEEP_FILES || bytes + f.length() > MAX_BYTES
            }
            if (drop) {
                f.delete()
                continue
            }
            if (!partial) {
                kept++
                bytes += f.length()
            }
        }
    }
}
