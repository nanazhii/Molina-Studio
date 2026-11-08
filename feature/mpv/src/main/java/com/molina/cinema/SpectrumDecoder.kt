package com.molina.cinema

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.nio.ByteOrder

/**
 * [SPECTRUM] Menganalisis audio berkas LOKAL menjadi frame [SpectrumTrack] memakai MediaExtractor + MediaCodec, lebih
 * cepat dari waktu nyata. Tidak menyentuh pemutaran mpv: hasilnya dibaca renderer menurut posisi putar.
 *
 * [SPECTRUM-JUMP] Satu pekerja ([fillFrom]) mengisi frame mulai dari [startFrame] (seek ke posisi itu, dengan pra-gulir 1 dtk)
 * sampai berkas habis atau bertemu frame yang sudah terisi oleh pekerja lain. [SpectrumAnalyzer] menjalankan satu pekerja
 * "kepala" (dari awal) dan, setelah seek jauh, satu pekerja "lompat" di posisi baru. Format yang tidak didukung dekoder
 * perangkat (mis. APE/DSD) mengembalikan [End.FAILED].
 */
internal object SpectrumDecoder {
    private const val TIMEOUT_US = 10_000L
    private const val PREROLL_US = 1_000_000L
    private const val DIAG = "spectrum"

    enum class End { EOF, MET_FILLED, CANCELLED, FULL, FAILED }

    /**
     * Blocking; panggil dari thread latar. [existing] = trek yang sudah ada (null = pekerja pertama, trek dibuat lewat
     * [createTrack] saat keluaran pertama diketahui). [active] dicek berkala. Prioritas thread dipulihkan di akhir.
     */
    fun fillFrom(
        path: String,
        existing: SpectrumTrack?,
        startFrame: Int,
        priority: Int,
        label: String,
        active: () -> Boolean,
        createTrack: (Int, Int) -> SpectrumTrack?,
    ): End {
        val t0 = SystemClock.elapsedRealtime()
        val oldPrio = runCatching { Process.getThreadPriority(Process.myTid()) }.getOrDefault(Process.THREAD_PRIORITY_DEFAULT)
        runCatching { Process.setThreadPriority(priority) }
        val res = try {
            decode(path, existing, startFrame, label, active, createTrack)
        } catch (e: Exception) {
            MpvLog.w(DIAG, "$label gagal: ${e.javaClass.simpleName}: ${e.message}")
            End.FAILED
        } finally {
            runCatching { Process.setThreadPriority(oldPrio) }
        }
        MpvLog.i(DIAG, "$label selesai: $res dalam ${(SystemClock.elapsedRealtime() - t0) / 1000.0}s")
        return res
    }

    private fun decode(
        path: String,
        existing: SpectrumTrack?,
        startFrame: Int,
        label: String,
        active: () -> Boolean,
        createTrack: (Int, Int) -> SpectrumTrack?,
    ): End {
        val t0 = SystemClock.elapsedRealtime()
        var lastProg = t0
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var builder: SpectrumFrameBuilder? = null
        try {
            extractor.setDataSource(path)
            val idx = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return End.FAILED
            extractor.selectTrack(idx)
            val inFmt = extractor.getTrackFormat(idx)
            val mime = inFmt.getString(MediaFormat.KEY_MIME) ?: return End.FAILED
            var sampleRate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var floatPcm = false
            val estFrames = if (inFmt.containsKey(MediaFormat.KEY_DURATION))
                (inFmt.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0 * SpectrumMath.FPS).toInt() + 64 else 4_096

            var seeked = false
            if (existing != null && startFrame > 0) {
                val us = startFrame.toLong() * existing.hopSamples * 1_000_000L / existing.sampleRate - PREROLL_US
                if (us > 0) { extractor.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC); seeked = true }
            }

            codec = MediaCodec.createDecoderByType(mime).also {
                it.configure(inFmt, null, null, 0)
                it.start()
            }
            MpvLog.i(DIAG, "$label mulai: frame=$startFrame seek=$seeked mime=$mime sr=$sampleRate ch=$channels durasi~${estFrames / SpectrumMath.FPS}s")
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            fun ensureBuilder(ptsUs: Long): SpectrumFrameBuilder? {
                builder?.let { return it }
                val t = existing ?: createTrack(sampleRate, estFrames) ?: return null
                if (t.sampleRate != sampleRate) return null
                val base = if (seeked) Math.round(ptsUs * sampleRate / 1_000_000.0) else 0L
                return SpectrumFrameBuilder(sampleRate, t, base).also { builder = it }
            }

            while (!outputDone) {
                if (!active()) {
                    MpvLog.d(DIAG, "$label dibatalkan (frame=${builder?.frame ?: startFrame})")
                    return End.CANCELLED
                }
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - lastProg >= 10_000L) {
                    lastProg = nowMs
                    val made = ((builder?.frame ?: startFrame) - startFrame).coerceAtLeast(0).toDouble() / SpectrumMath.FPS
                    val wall = (nowMs - t0) / 1000.0
                    MpvLog.d(DIAG, "$label progres: frame=${builder?.frame ?: startFrame} kecepatan=${Math.round(made / wall * 10) / 10.0}x waktu nyata")
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
                        if (b != null && b.sampleRate != sampleRate) {
                            MpvLog.w(DIAG, "$label gagal: laju sampel berubah ${b.sampleRate} -> $sampleRate") // tidak didukung di tengah trek
                            return End.FAILED
                        }
                    }
                    ob >= 0 -> {
                        val out = codec.getOutputBuffer(ob)
                        if (out != null && info.size > 0) {
                            val b = ensureBuilder(info.presentationTimeUs)
                            if (b == null) {
                                codec.releaseOutputBuffer(ob, false)
                                MpvLog.w(DIAG, "$label gagal: trek tidak dapat dibuat/laju sampel berbeda ($sampleRate)")
                                return End.FAILED
                            }
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
                            if (b.full) { codec.releaseOutputBuffer(ob, false); return End.FULL }
                            if (b.metFilled) { codec.releaseOutputBuffer(ob, false); return End.MET_FILLED }
                        }
                        codec.releaseOutputBuffer(ob, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            val b = builder
            (b?.track ?: existing)?.markEnd(b?.frame ?: startFrame)
            return End.EOF
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
