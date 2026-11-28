package com.molina.cinema

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * [SPECTRUM-LIVE] Visualizer untuk stream live (radio/IPTV audio). Satu filter `af` lavfi di mpv membagi audio menjadi
 * 32 pita (bandpass), menggabungkannya sebagai kanal ekstra 3..34 di samping stereo asli, mengukur RMS tiap kanal
 * dengan astats (metadata=1), lalu mengembalikan stereo asli lewat pan. Metadata ikut frame, jadi dibaca lewat
 * properti `af-metadata/<label>/lavfi.astats.N.RMS_level` tanpa berkas, FIFO, atau JNI/native baru.
 * Hasil ditulis ke SpectrumTrack berbasis jam (1 frame per HOP_MS) agar SpectrumView dipakai apa adanya.
 */
internal class SpectrumLive(
    private val active: () -> Boolean,
    private val onTrack: (SpectrumTrack) -> Unit,
    private val onUnsupported: () -> Unit,
) {
    companion object {
        const val LABEL = "mspec"
        const val HOP_MS = 33
        private const val FIRST_CH = 3 // kanal 1-2 = stereo asli
        private const val MAX_MISSES = 90 // ~3 dtk tanpa metadata = filter tidak bekerja
        private const val MAX_FRAMES = 200_000 // < batas SpectrumTrack (216000); ganti trek tiap ~110 menit
        private const val MUTE_DB = -120.0
        private const val BACKFILL = 4

        private fun edges(): DoubleArray {
            val n = SpectrumMath.BANDS
            val lo = SpectrumMath.F_MIN_HZ
            val hi = SpectrumMath.F_MAX_HZ
            return DoubleArray(n + 1) { lo * (hi / lo).pow(it.toDouble() / n) }
        }

        internal fun graph(): String {
            val n = SpectrumMath.BANDS
            val e = edges()
            val sb = StringBuilder()
            sb.append("aformat=channel_layouts=stereo,asplit=2[m][s];")
            sb.append("[s]pan=mono|c0=0.5*c0+0.5*c1,asplit=").append(n)
            for (i in 0 until n) sb.append("[b").append(i).append(']')
            sb.append(';')
            for (i in 0 until n) {
                val f = sqrt(e[i] * e[i + 1])
                val w = e[i + 1] - e[i]
                sb.append(String.format(Locale.ROOT, "[b%d]bandpass=f=%.1f:width_type=h:w=%.1f[c%d];", i, f, w, i))
            }
            for (i in 0 until n) sb.append("[c").append(i).append(']')
            sb.append("amerge=inputs=").append(n).append("[bands];")
            sb.append("[m][bands]amerge=inputs=2,astats=metadata=1:reset=1:measure_perchannel=RMS_level:measure_overall=none,")
            sb.append("pan=stereo|c0=c0|c1=c1")
            return sb.toString()
        }

        /** Argumen `af add`; graf dibungkus %panjang% agar karakter ; [ ] , | = aman dari parser opsi mpv. */
        internal fun filterArg(): String {
            val g = graph()
            return "@$LABEL:lavfi=graph=%${g.toByteArray(Charsets.UTF_8).size}%$g"
        }

        /** RMS pita (dBFS) -> 0..255 dengan skala dan kemiringan yang sama seperti SpectrumMath. */
        internal fun byteFor(rmsDb: Double, centerHz: Double): Byte {
            val tilt = SpectrumMath.TILT_DB_PER_OCTAVE * ln(centerHz.coerceAtLeast(20.0) / 1000.0) / ln(2.0)
            val db = rmsDb + 3.0 + tilt // sinus skala penuh = -3 dB RMS per pita
            val v = ((db - SpectrumMath.MIN_DB) / (SpectrumMath.MAX_DB - SpectrumMath.MIN_DB)).coerceIn(0.0, 1.0)
            return (v * 255.0 + 0.5).toInt().toByte()
        }

        internal fun parseDb(s: String?): Double? {
            val t = s?.trim() ?: return null
            if (t.isEmpty()) return null
            t.toDoubleOrNull()?.let { return if (it.isNaN()) MUTE_DB else it.coerceAtLeast(MUTE_DB) }
            return if (t.contains("inf", ignoreCase = true) || t.contains("nan", ignoreCase = true)) MUTE_DB else null
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var t0 = SystemClock.elapsedRealtime()

    /** Jam trek live (ms sejak trek dimulai); dipasang sebagai SpectrumView.positionMs. */
    fun positionMs(): Long = SystemClock.elapsedRealtime() - t0

    fun start() {
        scope.launch {
            val tag = "SpectrumLive"
            try {
                MPVLib.command(arrayOf("af", "add", filterArg()))
            } catch (e: Exception) {
                MpvLog.e(tag, "af add gagal", e); onUnsupported(); return@launch
            }
            val centers = edges().let { e -> DoubleArray(SpectrumMath.BANDS) { sqrt(e[it] * e[it + 1]) } }
            fun newTrack(): SpectrumTrack {
                t0 = SystemClock.elapsedRealtime()
                return SpectrumTrack(sampleRate = 1000, hopSamples = HOP_MS, windowSamples = 0).also { onTrack(it) }
            }
            var track = newTrack()
            val frame = ByteArray(SpectrumMath.BANDS)
            var last = -1
            var misses = 0
            var logged = false
            while (isActive) {
                if (!active()) { delay(150); continue }
                val idx = (positionMs() / HOP_MS).toInt()
                if (idx >= MAX_FRAMES) { track = newTrack(); last = -1; continue }
                var got = 0
                for (b in 0 until SpectrumMath.BANDS) {
                    val raw = try { MPVLib.getPropertyString("af-metadata/$LABEL/lavfi.astats.${FIRST_CH + b}.RMS_level") } catch (e: Exception) { null }
                    val db = parseDb(raw)
                    if (db != null) got++
                    frame[b] = byteFor(db ?: MUTE_DB, centers[b])
                }
                if (got == 0) {
                    if (++misses > MAX_MISSES) {
                        MpvLog.w(tag, "tidak ada metadata af selama ~3 dtk; visualizer live dimatikan")
                        onUnsupported(); return@launch
                    }
                } else {
                    misses = 0
                    if (!logged) { MpvLog.i(tag, "metadata ok: $got/${SpectrumMath.BANDS} pita"); logged = true }
                    for (i in maxOf(last + 1, idx - BACKFILL)..idx) track.put(i, frame)
                    last = idx
                }
                delay(HOP_MS.toLong())
            }
        }
    }

    fun cancel() {
        scope.cancel()
        try { MPVLib.command(arrayOf("af", "remove", "@$LABEL")) } catch (_: Exception) {}
    }
}
