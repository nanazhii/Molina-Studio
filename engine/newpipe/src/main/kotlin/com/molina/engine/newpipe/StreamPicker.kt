package com.molina.engine.newpipe

import com.molina.core.common.AudioChoice
import com.molina.core.common.StreamAudioLang
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.Locale

/** Hasil pemilihan satu URL siap putar dari [StreamInfo]. [kind] hanya untuk log. [audioLang] = bahasa audio terpilih ("" = stream gabungan/asli). */
internal data class Pick(val url: String, val kind: String, val height: Int = 0, val audioUrl: String? = null, val audioLang: String = "")

/**
 * Memilih satu URL yang bisa langsung dimuat mpv:
 * live -> HLS; selain itu video progresif ber-audio tertinggi; lalu audio progresif terbaik;
 * terakhir HLS bila ada. [AUDIO-LANG] Bila pengguna memilih bahasa audio yang BUKAN trek asli,
 * dipakai jalur adaptif (video-only + audio bahasa itu, digabung mpv lewat audio-add), karena
 * stream gabungan YouTube hanya memuat trek asli. Fungsi murni terhadap [StreamInfo], tanpa jaringan.
 */
internal object StreamPicker {

    fun pick(
        info: StreamInfo,
        quality: com.molina.core.common.VideoQuality = com.molina.core.common.VideoQuality.AUTO,
        audioLang: String = "", // [AUDIO-LANG]
    ): Pick? {
        val hls = info.hlsUrl
        val live = info.streamType == StreamType.LIVE_STREAM ||
            info.streamType == StreamType.AUDIO_LIVE_STREAM
        if (live && !hls.isNullOrBlank()) return Pick(hls, "hls-live")

        val lang = StreamAudioLang.normalize(audioLang)
        val audios = info.audioStreams.orEmpty()

        // [AUDIO-ONLY] hanya audio: URL audio terbaik, video tidak diunduh sama sekali
        if (quality == com.molina.core.common.VideoQuality.AUDIO) {
            bestAudio(audios, lang)?.let { a -> return Pick(a.content, "audio", audioLang = langKey(a)) }
        }
        // [AUDIO-LANG] bahasa non-asli hanya ada di audio terpisah: AUTO dibatasi 720p (aman untuk Android 8)
        val needSeparate = lang.isNotEmpty() && needsSeparateAudio(audios, lang)
        // [AUDIO-LANG-AUTO] tanpa batas 720p: AUTO + audio non-asli -> adaptif resolusi tertinggi tersedia (H.264 diutamakan)
        val q = quality
        if (q != com.molina.core.common.VideoQuality.AUDIO &&
            (q != com.molina.core.common.VideoQuality.AUTO || needSeparate)) {
            pickAdaptive(info, q, lang)?.let { return it } // [QUALITY]
        }

        bestMuxed(info.videoStreams.orEmpty())?.let { v ->
            val miss = quality != com.molina.core.common.VideoQuality.AUTO && quality != com.molina.core.common.VideoQuality.AUDIO // [QUALITY-LOCK]
            return Pick(v.content, if (miss) "progresif-fallback" else "progresif", v.height)
        }
        bestAudio(audios, lang)?.let { a ->
            return Pick(a.content, "audio", audioLang = langKey(a))
        }
        if (!hls.isNullOrBlank()) return Pick(hls, "hls")
        return null
    }

    /**
     * [QUALITY-RETRY] Kunci resolusi eksplisit jatuh ke stream gabungan (-fallback) karena tidak ada stream adaptif.
     * Extractor menelan galat klien visionOS (satu-satunya sumber adaptif/HLS saat klien Android hanya memberi itag 18),
     * jadi hasil kosong sering sementara dan layak diulang.
     */
    fun shouldRetry(pick: Pick, quality: com.molina.core.common.VideoQuality): Boolean =
        quality != com.molina.core.common.VideoQuality.AUTO &&
            quality != com.molina.core.common.VideoQuality.AUDIO &&
            pick.kind == "progresif-fallback"

    /** [QUALITY] Video-only (H.264 diutamakan, tanpa AV1) <= batas + audio terbaik (bahasa [lang]); audio digabung mpv lewat audio-add. */
    private fun pickAdaptive(info: StreamInfo, quality: com.molina.core.common.VideoQuality, lang: String): Pick? {
        val cands = info.videoOnlyStreams.orEmpty().filter {
            it.isUrl() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                it.codec?.startsWith("av01", ignoreCase = true) != true
        }
        val avc = cands.filter { it.codec?.startsWith("avc", ignoreCase = true) == true }
        val pool = if (avc.isNotEmpty()) avc else cands
        val h = quality.chooseHeight(pool.map { it.height }) ?: return null
        val v = pool.filter { it.height == h }.maxByOrNull { it.bitrate } ?: return null
        val a = bestAudio(info.audioStreams.orEmpty(), lang) ?: return null
        return Pick(v.content, "adaptif", v.height, a.content, langKey(a))
    }

    private fun bestMuxed(list: List<VideoStream>): VideoStream? =
        list.asSequence()
            .filter { it.isUrl() && !it.isVideoOnly() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxWithOrNull(compareBy<VideoStream>({ it.height }, { it.bitrate }))

    // ---- [AUDIO-LANG] ----

    private fun isOriginal(a: AudioStream): Boolean = a.audioTrackType?.name == "ORIGINAL"

    /** Kode bahasa trek ("" bila stream tidak punya metadata trek). */
    internal fun langKey(a: AudioStream): String {
        val fromLocale = StreamAudioLang.normalize(a.audioLocale?.language)
        if (fromLocale.isNotEmpty()) return fromLocale
        return StreamAudioLang.normalize(a.audioTrackId?.substringBefore('.'))
    }

    private fun usable(list: List<AudioStream>): List<AudioStream> =
        list.filter { it.isUrl() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }

    /** Trek asli: bertanda ORIGINAL; bila tidak ada tanda, semua trek tanpa metadata; bila tetap kosong, semuanya. */
    private fun originals(u: List<AudioStream>): List<AudioStream> =
        u.filter { isOriginal(it) }
            .ifEmpty { u.filter { it.audioTrackId.isNullOrEmpty() } }
            .ifEmpty { u }

    /** True bila [lang] ada di daftar dan bukan trek asli (jadi stream gabungan tidak cukup). */
    fun needsSeparateAudio(list: List<AudioStream>, lang: String): Boolean {
        val u = usable(list)
        val match = u.filter { langKey(it) == lang }
        if (match.isEmpty()) return false
        return match.none { isOriginal(it) }
    }

    /**
     * Audio terbaik. [lang] kosong atau tidak tersedia -> trek ASLI (bitrate tertinggi di dalamnya),
     * bukan sekadar bitrate tertinggi di seluruh trek (yang bisa jatuh ke trek dubbing acak).
     */
    private fun bestAudio(list: List<AudioStream>, lang: String): AudioStream? {
        val u = usable(list)
        if (u.isEmpty()) return null
        val pool = if (lang.isNotEmpty()) u.filter { langKey(it) == lang }.ifEmpty { originals(u) } else originals(u)
        return pool.maxByOrNull { it.averageBitrate }
    }

    /** Daftar bahasa yang tersedia untuk UI. Kosong bila video hanya punya satu trek. */
    fun audioChoices(info: StreamInfo): List<AudioChoice> {
        val u = usable(info.audioStreams.orEmpty())
        val groups = u.filter { langKey(it).isNotEmpty() }.groupBy { langKey(it) }
        if (groups.size < 2) return emptyList()
        val id = Locale.forLanguageTag("id")
        return groups.map { (key, list) ->
            val name = Locale.forLanguageTag(key).getDisplayLanguage(id)
                .ifBlank { key }.replaceFirstChar { it.uppercase() }
            AudioChoice(key, if (list.any { isOriginal(it) }) "$name (asli)" else name)
        }.sortedBy { it.label }
    }

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
