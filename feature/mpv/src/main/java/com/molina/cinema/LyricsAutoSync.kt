package com.molina.cinema

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * [LYRICS-AUTO] Sinkronisasi otomatis lirik polos (tanpa timestamp). Kotlin murni (tanpa Android).
 *
 * Dua tahap:
 *  1. [estimate]: perkiraan kasar, waktu dibagi sebanding panjang teks (dipakai langsung bila audio belum dianalisis).
 *  2. [align]: penyelarasan berbasis spektrum. Energi pita vokal (200-3500 Hz) dari [SpectrumTrack] dipakai mencari
 *     awal frasa (lonjakan energi setelah celah/jeda); dynamic programming memilih titik awal tiap baris sehingga
 *     durasi baris tetap mendekati proporsi teksnya, tetapi "menempel" ke awal frasa yang nyata di audio.
 *
 * Batas: energi pita vokal tidak membedakan suara dari instrumen. Presisi penuh tetap lewat .lrc asli / tap-sync.
 */
internal object LyricsAutoSync {
    private const val MIN_INTRO_MS = 4_000L
    private const val MAX_INTRO_MS = 20_000L
    private const val MAX_OUTRO_MS = 12_000L
    private const val LINE_BASE = 8 // bobot dasar per baris (jeda napas) di luar panjang teks
    private const val LEAD_MS = 120L // lirik tampil sedikit lebih awal dari awal vokal
    private const val VOCAL_LO_HZ = 200.0
    private const val VOCAL_HI_HZ = 3_500.0
    private const val MU = 0.6f // bobot hadiah "awal frasa" terhadap biaya durasi
    private const val COST_CAP = 1.5f
    private const val INF = Float.MAX_VALUE / 4
    private val SPEED_FACTORS = doubleArrayOf(0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1)

    private fun weights(texts: List<String>) = texts.map { (it.length + LINE_BASE).toDouble() }

    /** Tahap 1: perkiraan proporsional. */
    fun estimate(plain: List<String>, durationMs: Long): List<LyricLine> {
        val texts = plain.map { it.trim() }.filter { it.isNotEmpty() }
        if (texts.isEmpty() || durationMs < 10_000L) return emptyList()
        val intro = (durationMs * 0.06).toLong().coerceIn(MIN_INTRO_MS, MAX_INTRO_MS)
        val outro = (durationMs * 0.04).toLong().coerceAtMost(MAX_OUTRO_MS)
        val span = durationMs - intro - outro
        if (span <= 0L) return emptyList()
        val w = weights(texts)
        val total = w.sum()
        var acc = 0.0
        return texts.mapIndexed { i, t ->
            val start = intro + (span * acc / total).toLong()
            acc += w[i]
            LyricLine(start, t)
        }
    }

    /** Envelope energi pita vokal per frame, ternormalisasi 0..1 (lantai = persentil 10, puncak = persentil 95). */
    fun vocalEnvelope(data: ByteArray, frames: Int, bands: Int, sampleRate: Int): FloatArray {
        if (frames <= 0) return FloatArray(0)
        val fMin = SpectrumMath.F_MIN_HZ
        val fMax = min(SpectrumMath.F_MAX_HZ, sampleRate / 2.0 * 0.95)
        val span = ln(fMax / fMin)
        fun band(f: Double) = (bands * ln(f.coerceIn(fMin, fMax) / fMin) / span).toInt().coerceIn(0, bands - 1)
        val lo = band(VOCAL_LO_HZ)
        val hi = max(lo, band(VOCAL_HI_HZ))
        val n = hi - lo + 1
        val raw = FloatArray(frames)
        for (i in 0 until frames) {
            var s = 0
            for (b in lo..hi) s += data[i * bands + b].toInt() and 0xFF
            raw[i] = s / (255f * n)
        }
        val sorted = raw.copyOf().also { it.sort() }
        val p10 = sorted[(frames * 0.10).toInt().coerceIn(0, frames - 1)]
        val p95 = sorted[(frames * 0.95).toInt().coerceIn(0, frames - 1)]
        val range = (p95 - p10).coerceAtLeast(0.05f)
        return FloatArray(frames) { ((raw[it] - p10) / range).coerceIn(0f, 1f) }
    }

    /** Tahap 2 dari [SpectrumTrack] yang sudah selesai dianalisis. Null bila data tak memadai. */
    fun alignFromTrack(plain: List<String>, track: SpectrumTrack, durationMs: Long): List<LyricLine>? {
        val frames = track.ready
        if (frames <= 0) return null
        val env = vocalEnvelope(track.data, frames, track.bands, track.sampleRate)
        val fps = track.sampleRate.toDouble() / track.hopSamples
        val offsetMs = (track.windowSamples / 2.0 * 1000.0 / track.sampleRate).toLong()
        return align(plain, env, fps, offsetMs, durationMs)
    }

    private fun smooth(e: FloatArray, w: Int): FloatArray {
        val n = e.size
        val pre = FloatArray(n + 1)
        for (i in 0 until n) pre[i + 1] = pre[i] + e[i]
        val h = w / 2
        return FloatArray(n) { i ->
            val a = max(0, i - h); val b = min(n, i + h + 1)
            (pre[b] - pre[a]) / (b - a)
        }
    }

    /** Hadiah "awal frasa" per frame: lonjakan energi setelah bagian yang lebih sepi, ditambah bonus bila didahului celah. */
    private fun onsetReward(e: FloatArray): FloatArray {
        val n = e.size
        val pre = FloatArray(n + 1)
        for (i in 0 until n) pre[i + 1] = pre[i] + e[i]
        fun mean(a0: Int, b0: Int): Float {
            val a = a0.coerceIn(0, n); val b = b0.coerceIn(0, n)
            return if (b > a) (pre[b] - pre[a]) / (b - a) else 0f
        }
        return FloatArray(n) { i ->
            val before = mean(i - 8, i)
            val after = mean(i + 1, i + 9)
            val rise = max(0f, after - before)
            var recentMax = 0f
            for (j in max(0, i - 60) until max(0, i - 8)) if (e[j] > recentMax) recentMax = e[j]
            val dip = max(0f, recentMax - before)
            min(1f, 3f * rise) + 0.5f * min(1f, 2f * dip) * min(1f, 4f * rise)
        }
    }

    private fun lowerBound(a: IntArray, v: Int): Int {
        var lo = 0; var hi = a.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (a[m] < v) lo = m + 1 else hi = m }
        return lo
    }

    private fun segCost(ratio: Double): Float = min(COST_CAP, abs(ln(ratio)).toFloat())

    /**
     * Tahap 2: [env] = envelope vokal per frame ([fps] frame/detik, frame i berpusat di i/fps + [frameOffsetMs]).
     * Mengembalikan waktu mulai tiap baris, atau null bila tak ada solusi yang masuk akal (pemanggil memakai [estimate]).
     */
    fun align(plain: List<String>, env: FloatArray, fps: Double, frameOffsetMs: Long, durationMs: Long): List<LyricLine>? {
        val texts = plain.map { it.trim() }.filter { it.isNotEmpty() }
        val n = texts.size
        if (n < 3 || durationMs < 20_000L || env.size < (fps * 10).toInt()) return null

        val e = smooth(env, 5)
        val nf = e.size
        val reward = onsetReward(e)

        // kandidat: grid ~200 ms + puncak hadiah
        val isCand = BooleanArray(nf)
        val step = max(1, (fps * 0.2).toInt())
        var g = 0
        while (g < nf) { isCand[g] = true; g += step }
        for (j in 0 until nf) {
            if (reward[j] < 0.25f) continue
            var peak = true
            for (k in max(0, j - 3)..min(nf - 1, j + 3)) if (reward[k] > reward[j]) { peak = false; break }
            if (peak) isCand[j] = true
        }
        val cand = (0 until nf).filter { isCand[it] }.toIntArray()
        val cn = cand.size
        if (cn < n) return null

        val w = weights(texts)
        val wSum = w.sum()
        val endF = min(nf, (durationMs * fps / 1000.0).toInt())
        val introF = (durationMs * 0.06).toLong().coerceIn(MIN_INTRO_MS, MAX_INTRO_MS) * fps / 1000.0
        val outroF = (durationMs * 0.04).toLong().coerceAtMost(MAX_OUTRO_MS) * fps / 1000.0
        val spanF = endF - introF - outroF
        if (spanF < n * fps * 0.5) return null
        val v0 = spanF / wSum // frame per satuan bobot bila lirik mengisi seluruh lagu (batas atas kecepatan)

        // awal baris pertama: jendela sekitar perkiraan intro
        val sLo = max(fps, introF * 0.2).toInt()
        val sHi = min(introF * 3.0, endF * 0.3).toInt()
        val minGap = (fps * 0.5).toInt().coerceAtLeast(1)
        val lastMax = endF - (2 * fps).toInt()

        var bestCost = INF
        var bestFrames: IntArray? = null
        // Lirik jarang mengisi seluruh lagu (ada intro, jeda instrumental, outro): cari kecepatan nyanyi terbaik.
        for (factor in SPEED_FACTORS) {
            val v = v0 * factor
            val exp = DoubleArray(n) { v * w[it] }
            val dp = Array(n) { FloatArray(cn) { INF } }
            val back = Array(n) { IntArray(cn) { -1 } }
            for (ci in 0 until cn) {
                val c = cand[ci]
                if (c < sLo || c > sHi) continue
                dp[0][ci] = -MU * reward[c] + 0.3f * min(1.0, abs(c - introF) / (15.0 * fps)).toFloat()
            }
            for (k in 0 until n - 1) {
                val lo = max(minGap.toDouble(), 0.35 * exp[k]).toInt()
                val hi = (4.0 * exp[k]).toInt().coerceAtLeast(lo + 1)
                for (ci in 0 until cn) {
                    val base = dp[k][ci]
                    if (base >= INF) continue
                    val c = cand[ci]
                    var cj = lowerBound(cand, c + lo)
                    while (cj < cn && cand[cj] - c <= hi) {
                        val cost = base + segCost((cand[cj] - c) / exp[k]) - MU * reward[cand[cj]]
                        if (cost < dp[k + 1][cj]) { dp[k + 1][cj] = cost; back[k + 1][cj] = ci }
                        cj++
                    }
                }
            }
            var fc = INF; var fci = -1
            for (ci in 0 until cn) {
                if (cand[ci] > lastMax) continue
                if (dp[n - 1][ci] < fc) { fc = dp[n - 1][ci]; fci = ci }
            }
            if (fci < 0 || fc >= bestCost) continue
            val fr = IntArray(n)
            var ci = fci
            var valid = true
            for (k in n - 1 downTo 0) {
                fr[k] = cand[ci]
                if (k > 0) { ci = back[k][ci]; if (ci < 0) { valid = false; break } }
            }
            if (valid) { bestCost = fc; bestFrames = fr }
        }
        val frames = bestFrames ?: return null
        return texts.mapIndexed { i, t ->
            val ms = (frames[i] * 1000.0 / fps).toLong() + frameOffsetMs - LEAD_MS
            LyricLine(max(0L, ms), t)
        }
    }
}
