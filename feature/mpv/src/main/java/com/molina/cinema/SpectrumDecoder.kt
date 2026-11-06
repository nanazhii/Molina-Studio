package com.molina.cinema

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.nio.ByteOrder

/**
 * [SPECTRUM] Menganalisis audio berkas LOKAL menjadi [SpectrumTrack] memakai MediaExtractor + MediaCodec, lebih
 * cepat dari waktu nyata, di thread latar berprioritas rendah. Tidak menyentuh pemutaran mpv: hasilnya dibaca
 * renderer menurut posisi putar, sehingga selalu sinkron (juga setelah seek). Format yang tidak didukung dekoder
 * perangkat (mis. APE/DSD) mengembalikan false dan visualizer disembunyikan.
 */
internal object SpectrumDecoder {
    private const val TIMEOUT_US = 10_000L
    private const val DIAG = "spectrum" // [SPECTRUM-DIAG]

    /**
     * Analisis [path]. Blocking; panggil dari thread latar. [active] dicek berkala (false = berhenti segera).
     * [onTrack] dipanggil sekali begitu track dibuat; frame berikutnya terus ditambahkan ke objek yang sama.
     * Mengembalikan true bila selesai penuh (atau mencapai batas panjang), false bila gagal/dibatalkan.
     */
    fun analyze(path: String, active: () -> Boolean, onTrack: (SpectrumTrack) -> Unit): Boolean {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        val t0 = SystemClock.elapsedRealtime() // [SPECTRUM-DIAG]
        var lastProg = t0
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var builder: SpectrumFrameBuilder? = null
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
            MpvLog.i(DIAG, "analisis mulai: mime=$mime sr=$sampleRate ch=$channels durasi~${estFrames / SpectrumMath.FPS}s")
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            fun ensureBuilder(): SpectrumFrameBuilder {
                builder?.let { return it }
                val hop = (sampleRate / SpectrumMath.FPS).coerceAtLeast(1)
                val t = SpectrumTrack(sampleRate, hop, initialFrames = estFrames.coerceAtLeast(64))
                return SpectrumFrameBuilder(sampleRate, t).also { builder = it; onTrack(t) }
            }

            while (!outputDone) {
                if (!active()) { MpvLog.d(DIAG, "analisis dibatalkan (decode=${(builder?.track?.ready ?: 0) / SpectrumMath.FPS}s)"); return false }
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - lastProg >= 10_000L) {
                    lastProg = nowMs
                    val dec = (builder?.track?.ready ?: 0).toDouble() / SpectrumMath.FPS
                    val wall = (nowMs - t0) / 1000.0
                    MpvLog.d(DIAG, "progres: decode=${Math.round(dec)}s dari ~${estFrames / SpectrumMath.FPS}s, kecepatan=${Math.round(dec / wall * 10) / 10.0}x waktu nyata")
                }
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
                        if (b != null && b.sampleRate != sampleRate) { MpvLog.w(DIAG, "analisis gagal: laju sampel berubah ${b.sampleRate} -> $sampleRate"); return false } // laju sampel berubah di tengah trek: tidak didukung
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
                            if (b.full) { codec.releaseOutputBuffer(ob, false); builder?.track?.complete = true; MpvLog.i(DIAG, "analisis selesai (batas panjang) dalam ${(SystemClock.elapsedRealtime() - t0) / 1000.0}s"); return true }
                        }
                        codec.releaseOutputBuffer(ob, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            builder?.track?.complete = true
            MpvLog.i(DIAG, "analisis selesai: ${(builder?.track?.ready ?: 0) / SpectrumMath.FPS}s audio dalam ${(SystemClock.elapsedRealtime() - t0) / 1000.0}s")
            return builder != null
        } catch (e: Exception) {
            MpvLog.w(DIAG, "analisis gagal: ${e.javaClass.simpleName}: ${e.message}")
            return false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
