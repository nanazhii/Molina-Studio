package com.molina.studio.cinema

import android.media.MediaMetadataRetriever
import com.molina.core.common.MediaSidecar
import com.molina.core.common.SidecarRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [FILES-MEDIAINFO] Detail media untuk dialog Properti: durasi, resolusi, bitrate, format, judul/artis/album.
 * Dibaca di Dispatchers.IO lewat MediaMetadataRetriever; berkas yang tidak terbaca menghasilkan daftar kosong.
 */
internal object W7MediaInfo {
    suspend fun read(f: File): List<Pair<String, String>> = withContext(Dispatchers.IO) { extract(f) }

    /** Baris tambahan dari `.info.json` sidecar (`molina_dl --sidecar`); kosong bila tidak ada atau rusak. */
    suspend fun sidecar(f: File): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        SidecarRows.of(MediaSidecar.readInfo(MediaSidecar.find(f).info))
    }

    private fun extract(f: File): List<Pair<String, String>> {
        val r = MediaMetadataRetriever()
        val out = ArrayList<Pair<String, String>>()
        try {
            r.setDataSource(f.path)
            fun m(k: Int): String? = r.extractMetadata(k)?.trim()?.takeIf { it.isNotEmpty() }
            m(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { out.add("Judul" to it) }
            m(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { out.add("Artis" to it) }
            m(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { out.add("Album" to it) }
            m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0L }?.let { out.add("Durasi" to duration(it)) }
            val w = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (w > 0 && h > 0) out.add("Resolusi" to w.toString() + " \u00D7 " + h)
            m(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.takeIf { it > 0L }?.let { out.add("Bitrate" to (it / 1000L).toString() + " kb/dtk") }
            m(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { out.add("Format" to it) }
        } catch (e: Exception) {
            // berkas tidak didukung MediaMetadataRetriever (mis. sebagian mkv/ac3): tampilkan apa adanya
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
        return out
    }

    private fun duration(ms: Long): String {
        val total = ms / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        val mm = if (h > 0L && m < 10L) "0" + m else m.toString()
        val ss = if (s < 10L) "0" + s else s.toString()
        return if (h > 0L) h.toString() + ":" + mm + ":" + ss else mm + ":" + ss
    }
}
