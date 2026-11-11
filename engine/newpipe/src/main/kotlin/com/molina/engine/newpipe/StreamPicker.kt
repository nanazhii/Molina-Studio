package com.molina.engine.newpipe

import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/** Hasil pemilihan satu URL siap putar dari [StreamInfo]. [kind] hanya untuk log. */
internal data class Pick(val url: String, val kind: String, val height: Int = 0, val audioUrl: String? = null)

/**
 * Memilih satu URL yang bisa langsung dimuat mpv (v1 tanpa penggabungan video-only + audio):
 * live -> HLS; selain itu video progresif ber-audio tertinggi; lalu audio progresif terbaik;
 * terakhir HLS bila ada. Fungsi murni terhadap [StreamInfo], tanpa jaringan.
 */
internal object StreamPicker {

    fun pick(info: StreamInfo, quality: com.molina.core.common.VideoQuality = com.molina.core.common.VideoQuality.AUTO): Pick? {
        val hls = info.hlsUrl
        val live = info.streamType == StreamType.LIVE_STREAM ||
            info.streamType == StreamType.AUDIO_LIVE_STREAM
        if (live && !hls.isNullOrBlank()) return Pick(hls, "hls-live")

        // [AUDIO-ONLY] hanya audio: URL audio terbaik, video tidak diunduh sama sekali
        if (quality == com.molina.core.common.VideoQuality.AUDIO) {
            bestAudio(info.audioStreams.orEmpty())?.let { a -> return Pick(a.content, "audio") }
        }
        if (quality != com.molina.core.common.VideoQuality.AUTO && quality != com.molina.core.common.VideoQuality.AUDIO) {
            pickAdaptive(info, quality)?.let { return it } // [QUALITY]
        }

        bestMuxed(info.videoStreams.orEmpty())?.let { v ->
            val miss = quality != com.molina.core.common.VideoQuality.AUTO && quality != com.molina.core.common.VideoQuality.AUDIO // [QUALITY-LOCK]
            return Pick(v.content, if (miss) "progresif-fallback" else "progresif", v.height)
        }
        bestAudio(info.audioStreams.orEmpty())?.let { a ->
            return Pick(a.content, "audio")
        }
        if (!hls.isNullOrBlank()) return Pick(hls, "hls")
        return null
    }

    /** [QUALITY] Video-only (H.264 diutamakan, tanpa AV1) <= batas + audio terbaik; audio digabung mpv lewat audio-add. */
    private fun pickAdaptive(info: StreamInfo, quality: com.molina.core.common.VideoQuality): Pick? {
        val cands = info.videoOnlyStreams.orEmpty().filter {
            it.isUrl() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                it.codec?.startsWith("av01", ignoreCase = true) != true
        }
        val avc = cands.filter { it.codec?.startsWith("avc", ignoreCase = true) == true }
        val pool = if (avc.isNotEmpty()) avc else cands
        val h = quality.chooseHeight(pool.map { it.height }) ?: return null
        val v = pool.filter { it.height == h }.maxByOrNull { it.bitrate } ?: return null
        val a = bestAudio(info.audioStreams.orEmpty()) ?: return null
        return Pick(v.content, "adaptif", v.height, a.content)
    }

    private fun bestMuxed(list: List<VideoStream>): VideoStream? =
        list.asSequence()
            .filter { it.isUrl() && !it.isVideoOnly() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxWithOrNull(compareBy<VideoStream>({ it.height }, { it.bitrate }))

    private fun bestAudio(list: List<AudioStream>): AudioStream? =
        list.asSequence()
            .filter { it.isUrl() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxByOrNull { it.averageBitrate }

    /**
     * Perkiraan kedaluwarsa (epoch ms) dari parameter `expire` (detik) pada query atau segmen
     * path `/expire/<detik>`. Null bila tidak ditemukan atau tidak masuk akal.
     */
    fun expiresAtMs(url: String): Long? {
        val m = EXPIRE.find(url) ?: return null
        val sec = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toLongOrNull() ?: return null
        return if (sec in 1_000_000_000L..99_999_999_999L) sec * 1000 else null
    }

    private val EXPIRE = Regex("[?&]expire=(\\d{9,11})|/expire/(\\d{9,11})")
}
