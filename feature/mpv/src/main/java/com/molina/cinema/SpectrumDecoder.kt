package com.molina.cinema

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import java.nio.ByteOrder

/**
 * [SPECTRUM] Menganalisis audio berkas LOKAL menjadi [SpectrumTrack] memakai MediaExtractor + MediaCodec, lebih
 * cepat dari waktu nyata, di thread latar berprioritas rendah. Tidak menyentuh pemutaran mpv: hasilnya dibaca
 * renderer menurut posisi putar, sehingga selalu sinkron (juga setelah seek). Format yang tidak didukung dekoder
 * perangkat (mis. APE/DSD) mengembalikan false dan visualizer disembunyikan.
 */
internal object SpectrumDecoder {
    private const val TIMEOUT_US = 10_000L

    /** Pembangun frame: menerima sampel mono berurutan, menghasilkan satu frame tiap [hop] sampel setelah jendela penuh. */
    private class FrameBuilder(val sampleRate: Int, val track: SpectrumTrack) {
        private val n = SpectrumMath.FFT_SIZE
        private val ring = FloatArray(n)
        private val window = FloatArray(n)
        private val re = DoubleArray(n)
        private val im = DoubleArray(n)
        private val out = ByteArray(track.bands)
        private val edges = SpectrumMath.bandEdges(sampleRate)
        private var total = 0L
        private var nextEnd = n.toLong()
        var full = false
            private set

        fun push(s: Float) {
            ring[(total % n).toInt()] = s
            total++
            if (total == nextEnd) {
                val start = (total % n).toInt() // sampel tertua
                for (i in 0 until n) window[i] = ring[(start + i) % n]
                SpectrumMath.frame(window, sampleRate, edges, re, im, out, 0)
                if (!track.append(out)) full = true
                nextEnd += track.hopSamples
            }
        }
    }

    /**
     * Analisis [path]. Blocking; panggil dari thread latar. [active] dicek berkala (false = berhenti segera).
     * [onTrack] dipanggil sekali begitu track dibuat; frame berikutnya terus ditambahkan ke objek yang sama.
     * Mengembalikan true bila selesai penuh (atau mencapai batas panjang), false bila gagal/dibatalkan.
     */
    fun analyze(path: String, active: () -> Boolean, onTrack: (SpectrumTrack) -> Unit): Boolean {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var builder: FrameBuilder? = null
        try {
            extractor.setDataSource(path)
            val idx = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            extractor.selectTrack(idx)
            val inFmt = extractor.getTrackFormat(idx)
            val mime = inFmt.getString(MediaFormat.KEY_MIME) ?: return false
            var sampleRate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var floatPcm = false
            val estFrames = if (inFmt.containsKey(MediaFormat.KEY_DURATION))
                (inFmt.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0 * SpectrumMath.FPS).toInt() + 64 else 4_096

            codec = MediaCodec.createDecoderByType(mime).also {
                it.configure(inFmt, null, null, 0)
                it.start()
            }
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            fun ensureBuilder(): FrameBuilder {
                builder?.let { return it }
                val hop = (sampleRate / SpectrumMath.FPS).coerceAtLeast(1)
                val t = SpectrumTrack(sampleRate, hop, initialFrames = estFrames.coerceAtLeast(64))
                return FrameBuilder(sampleRate, t).also { builder = it; onTrack(t) }
            }

            while (!outputDone) {
                if (!active()) return false
                if (!inputDone) {
                    val ib = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (ib >= 0) {
                        val buf = codec.getInputBuffer(ib)
                        val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        if (size < 0) {
                            codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ib, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val ob = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        floatPcm = Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4 /* ENCODING_PCM_FLOAT */
                        val b = builder
                        if (b != null && b.sampleRate != sampleRate) return false // laju sampel berubah di tengah trek: tidak didukung
                    }
                    ob >= 0 -> {
                        val out = codec.getOutputBuffer(ob)
                        if (out != null && info.size > 0) {
                            val b = ensureBuilder()
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            out.order(ByteOrder.nativeOrder())
                            if (floatPcm) {
                                val fb = out.asFloatBuffer()
                                while (fb.remaining() >= channels) {
                                    var sum = 0f
                                    for (c in 0 until channels) sum += fb.get()
                                    b.push(sum / channels)
                                }
                            } else {
                                val sb = out.asShortBuffer()
                                while (sb.remaining() >= channels) {
                                    var sum = 0
                                    for (c in 0 until channels) sum += sb.get().toInt()
                                    b.push(sum / (channels * 32768f))
                                }
                            }
                            if (b.full) { codec.releaseOutputBuffer(ob, false); builder?.track?.complete = true; return true }
                        }
                        codec.releaseOutputBuffer(ob, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            builder?.track?.complete = true
            return builder != null
        } catch (e: Exception) {
            return false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
