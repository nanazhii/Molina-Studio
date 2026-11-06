package com.molina.cinema

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * [SPECTRUM] Matematika murni (tanpa Android) untuk visualizer spektrum di layar "Now Playing" audio:
 * FFT radix-2, pita frekuensi logaritmik, peta dB -> 0..255, penyimpanan frame hasil analisis
 * ([SpectrumTrack]) dan penghalus gerak batang ([SpectrumSmoother]). Analisis PCM dilakukan terpisah oleh
 * SpectrumDecoder (MediaCodec), bukan dari audio mpv, sehingga tidak menyentuh JNI/native mpv dan selalu
 * sinkron dengan posisi putar (termasuk setelah seek). Diuji di scripts/mpv-tests.
 */
internal object SpectrumMath {
    const val BANDS = 32
    const val FFT_SIZE = 4096 // resolusi ~10,8 Hz @44,1 kHz: pita bass selaras dengan nada
    /** FFT beresolusi tinggi (~5,4 Hz/bin @44,1 kHz) khusus pita bass (pusat < LOW_CUTOFF_HZ). */
    const val FFT_LOW_SIZE = 8192
    const val LOW_CUTOFF_HZ = 250.0
    const val F_MIN_HZ = 40.0
    const val F_MAX_HZ = 16_000.0
    const val FPS = 30
    const val MIN_DB = -75.0
    const val MAX_DB = -12.0
    /** Kompensasi kemiringan spektrum musik: batang tinggi diberi +dB per oktaf relatif terhadap 1 kHz. */
    const val TILT_DB_PER_OCTAVE = 3.0

    private val hannCache = HashMap<Int, FloatArray>()

    @Synchronized
    fun hann(n: Int): FloatArray = hannCache.getOrPut(n) { FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / n)).toFloat() } }

    fun isPowerOfTwo(n: Int) = n > 1 && (n and (n - 1)) == 0

    /** FFT radix-2 in-place. [re] dan [im] sama panjang, pangkat dua. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size && isPowerOfTwo(n)) { "ukuran harus pangkat dua dan sama" }
        var j = 0
        for (i in 1 until n) { // permutasi bit-reversal
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Batas bin untuk [bands] pita log antara [fMin] dan [fMax] (ukuran bands+1), naik ketat dan <= fftSize/2. */
    fun bandEdges(sampleRate: Int, bands: Int = BANDS, fftSize: Int = FFT_SIZE, fMin: Double = F_MIN_HZ, fMaxWanted: Double = F_MAX_HZ): IntArray {
        val maxBin = fftSize / 2
        val fMax = minOf(fMaxWanted, sampleRate / 2.0 * 0.95)
        val binHz = sampleRate.toDouble() / fftSize
        val edges = IntArray(bands + 1)
        for (i in 0..bands) {
            val f = fMin * (fMax / fMin).pow(i.toDouble() / bands)
            edges[i] = (f / binHz).roundToInt().coerceIn(1, maxBin) // dibulatkan, bukan dipotong: tepi pita tidak bias ke bawah
        }
        for (i in 1..bands) if (edges[i] <= edges[i - 1]) edges[i] = minOf(edges[i - 1] + 1, maxBin)
        return edges
    }

    /** Jumlah pita dari bawah yang memakai FFT_LOW_SIZE: pita yang pusat geometrisnya < LOW_CUTOFF_HZ. */
    fun lowBandCount(sampleRate: Int, bands: Int = BANDS): Int {
        val fMax = minOf(F_MAX_HZ, sampleRate / 2.0 * 0.95)
        var count = 0
        for (b in 0 until bands) {
            val lo = F_MIN_HZ * (fMax / F_MIN_HZ).pow(b.toDouble() / bands)
            val hi = F_MIN_HZ * (fMax / F_MIN_HZ).pow((b + 1).toDouble() / bands)
            if (sqrt(lo * hi) < LOW_CUTOFF_HZ) count++ else break
        }
        return count
    }

    /**
     * Satu frame: [samples] (mono, FFT_SIZE) -> [out] (BANDS byte tak bertanda 0..255) mulai [outOffset].
     * [re]/[im] adalah buffer kerja berukuran FFT_SIZE (dipakai ulang oleh pemanggil).
     */
    fun frame(samples: FloatArray, sampleRate: Int, edges: IntArray, re: DoubleArray, im: DoubleArray, out: ByteArray, outOffset: Int) =
        analyze(FFT_SIZE, samples, sampleRate, edges, edges.size - 1, re, im, out, outOffset)

    /**
     * Resolusi bass: menimpa [bandCount] pita terbawah di [out] memakai FFT_LOW_SIZE. [samples]/[re]/[im] berukuran
     * FFT_LOW_SIZE; [edges] dari bandEdges(sampleRate, fftSize = FFT_LOW_SIZE).
     */
    fun frameLow(samples: FloatArray, sampleRate: Int, edges: IntArray, bandCount: Int, re: DoubleArray, im: DoubleArray, out: ByteArray, outOffset: Int) =
        analyze(FFT_LOW_SIZE, samples, sampleRate, edges, bandCount.coerceIn(0, edges.size - 1), re, im, out, outOffset)

    private fun analyze(n: Int, samples: FloatArray, sampleRate: Int, edges: IntArray, bandCount: Int, re: DoubleArray, im: DoubleArray, out: ByteArray, outOffset: Int) {
        val w = hann(n)
        for (i in 0 until n) { re[i] = (samples[i] * w[i]).toDouble(); im[i] = 0.0 }
        fft(re, im)
        val norm = n / 4.0 // sinus skala penuh dengan jendela Hann menghasilkan puncak ~ N/4
        val binHz = sampleRate.toDouble() / n
        val maxBin = n / 2
        for (b in 0 until bandCount) {
            val hi = maxOf(edges[b + 1], edges[b] + 1).coerceAtMost(maxBin)
            val lo = edges[b].coerceAtMost(hi - 1)
            var peak = 0.0
            for (k in lo until hi) {
                val m = sqrt(re[k] * re[k] + im[k] * im[k])
                if (m > peak) peak = m
            }
            val centerHz = ((lo + hi - 1) / 2.0 + 0.5) * binHz
            val tilt = TILT_DB_PER_OCTAVE * ln(centerHz.coerceAtLeast(20.0) / 1_000.0) / ln(2.0)
            val db = 20.0 * log10(peak / norm + 1e-9) + tilt
            val v = ((db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0.0, 1.0)
            out[outOffset + b] = (v * 255.0 + 0.5).toInt().toByte()
        }
    }
}

/**
 * Hasil analisis seluruh trek: [bands] byte per frame. Ditulis bertahap oleh dekoder (thread latar) dan dibaca
 * renderer (thread UI); [ready] = jumlah frame lengkap yang sudah boleh dibaca.
 */
internal class SpectrumTrack(
    val sampleRate: Int,
    val hopSamples: Int,
    val windowSamples: Int = SpectrumMath.FFT_SIZE,
    val bands: Int = SpectrumMath.BANDS,
    initialFrames: Int = 4_096,
    private val maxFrames: Int = 216_000, // 2 jam pada 30 fps = ~6,9 MB
) {
    @Volatile var data: ByteArray = ByteArray(initialFrames.coerceIn(1, maxFrames) * bands)
        private set
    @Volatile var ready: Int = 0
        private set
    @Volatile var complete: Boolean = false

    /** Tambah satu frame. false bila sudah mencapai batas. Hanya satu penulis. */
    fun append(frame: ByteArray, offset: Int = 0): Boolean {
        val n = ready
        if (n >= maxFrames) return false
        var d = data
        if ((n + 1) * bands > d.size) {
            d = d.copyOf((minOf(maxFrames, (d.size / bands) * 2)) * bands)
            data = d
        }
        System.arraycopy(frame, offset, d, n * bands, bands)
        ready = n + 1
        return true
    }

    /**
     * Isi [out] (BANDS float 0..1) untuk waktu [timeMs] dengan interpolasi linear antar frame. Waktu dipetakan
     * ke pusat jendela analisis. false bila frame belum tersedia (dekoder belum sampai).
     */
    fun sample(timeMs: Long, out: FloatArray): Boolean {
        val count = ready
        if (count == 0 || timeMs < 0) return false
        val f = (timeMs * sampleRate / 1000.0 - windowSamples / 2.0) / hopSamples
        val i0 = Math.floor(f).toInt().coerceAtLeast(0)
        if (i0 >= count) return false
        val i1 = minOf(i0 + 1, count - 1)
        val t = (f - i0).coerceIn(0.0, 1.0).toFloat()
        val d = data
        for (b in 0 until bands) {
            val a = (d[i0 * bands + b].toInt() and 0xFF) / 255f
            val c = (d[i1 * bands + b].toInt() and 0xFF) / 255f
            out[b] = a + (c - a) * t
        }
        return true
    }
}

/** Penghalus gerak batang: naik cepat, turun lambat, plus penanda puncak yang tertahan lalu jatuh. */
internal class SpectrumSmoother(val bands: Int, private val attackMs: Float = 35f, private val releaseMs: Float = 200f,
                                private val peakHoldMs: Float = 220f, private val peakFallPerSec: Float = 0.7f) {
    val level = FloatArray(bands)
    val peak = FloatArray(bands)
    private val hold = FloatArray(bands)

    fun step(target: FloatArray?, dtMs: Float) {
        val dt = dtMs.coerceIn(0f, 100f)
        for (b in 0 until bands) {
            val t = target?.get(b) ?: 0f
            val tau = if (t > level[b]) attackMs else releaseMs
            level[b] += (t - level[b]) * (1f - exp(-dt / tau))
            if (level[b] >= peak[b]) { peak[b] = level[b]; hold[b] = peakHoldMs }
            else if (hold[b] > 0f) hold[b] -= dt
            else peak[b] = maxOf(level[b], peak[b] - peakFallPerSec * dt / 1000f)
        }
    }

    fun reset() { level.fill(0f); peak.fill(0f); hold.fill(0f) }

    /** True bila semua batang praktis diam (renderer boleh berhenti menggambar ulang). */
    fun settled(): Boolean = level.all { it < 0.004f } && peak.all { it < 0.004f }
}

/**
 * Pembangun frame: menerima sampel mono berurutan, menghasilkan satu frame tiap hop sampel. Pita bass (pusat <
 * LOW_CUTOFF_HZ) dianalisis dengan FFT_LOW_SIZE; pusat jendelanya diselaraskan dengan jendela utama (FFT_SIZE)
 * lewat lookahead (FFT_LOW_SIZE - FFT_SIZE) / 2 sampel, sehingga SpectrumTrack.sample() tetap benar untuk semua pita.
 */
internal class SpectrumFrameBuilder(val sampleRate: Int, val track: SpectrumTrack) {
    private val n = SpectrumMath.FFT_SIZE
    private val nl = SpectrumMath.FFT_LOW_SIZE
    private val lookahead = ((nl - n) / 2).toLong()
    private val ring = FloatArray(nl)
    private val window = FloatArray(n)
    private val windowLow = FloatArray(nl)
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private val reLow = DoubleArray(nl)
    private val imLow = DoubleArray(nl)
    private val out = ByteArray(track.bands)
    private val edges = SpectrumMath.bandEdges(sampleRate)
    private val edgesLow = SpectrumMath.bandEdges(sampleRate, fftSize = nl)
    private val lowCount = SpectrumMath.lowBandCount(sampleRate)
    private var total = 0L
    private var nextEnd = n.toLong() + lookahead
    var full = false
        private set

    private fun slot(p: Long) = (((p % nl) + nl) % nl).toInt() // posisi < 0 (sebelum trek) = slot belum terisi = 0

    fun push(s: Float) {
        ring[slot(total)] = s
        total++
        if (total != nextEnd) return
        val endMain = total - lookahead
        for (i in 0 until n) window[i] = ring[slot(endMain - n + i)]
        SpectrumMath.frame(window, sampleRate, edges, re, im, out, 0)
        if (lowCount > 0) {
            for (i in 0 until nl) windowLow[i] = ring[slot(total - nl + i)]
            SpectrumMath.frameLow(windowLow, sampleRate, edgesLow, lowCount, reLow, imLow, out, 0)
        }
        if (!track.append(out)) full = true
        nextEnd += track.hopSamples
    }
}

/** Mode warna visualizer spektrum (dipilih pengguna, disimpan di SpectrumPrefs). */
internal enum class SpectrumColorMode {
    ACCENT, FREQUENCY, CINEMA;

    /** Putaran mode: ACCENT -> FREQUENCY -> CINEMA -> ACCENT. */
    fun next(): SpectrumColorMode = values()[(ordinal + 1) % values().size]

    companion object {
        fun fromName(name: String?): SpectrumColorMode? = values().firstOrNull { it.name == name }
    }
}

/** Palet warna batang spektrum (ARGB Int murni, tanpa Android agar bisa diuji di JVM). */
internal object SpectrumColors {
    val WARM = 0xFFFF8A3D.toInt()   // bass (mode FREQUENCY)
    val COOL = 0xFF3FA9F5.toInt()   // treble (mode FREQUENCY)
    val MAROON = 0xFF9B1B30.toInt() // dasar batang (mode CINEMA)
    val GOLD = 0xFFFFC94D.toInt()   // ujung batang dan penanda puncak (mode CINEMA)
    private const val WHITE = -1

    fun blend(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val x = (a ushr shift) and 0xFF
            val y = (b ushr shift) and 0xFF
            return (x + (y - x) * k).toInt()
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun byFrequency(b: Int, bands: Int, accent: Int): Int {
        val t = if (bands > 1) b / (bands - 1).toFloat() else 0.5f
        return if (t < 0.5f) blend(WARM, accent, t / 0.5f) else blend(accent, COOL, (t - 0.5f) / 0.5f)
    }

    /** Warna di dasar batang [b] (juga warna pantulan). */
    fun bottom(mode: SpectrumColorMode, b: Int, bands: Int, accent: Int): Int = when (mode) {
        SpectrumColorMode.ACCENT -> accent
        SpectrumColorMode.FREQUENCY -> byFrequency(b, bands, accent)
        SpectrumColorMode.CINEMA -> MAROON
    }

    /** Warna di ujung atas gradasi batang. */
    fun top(mode: SpectrumColorMode, b: Int, bands: Int, accent: Int): Int = when (mode) {
        SpectrumColorMode.CINEMA -> GOLD
        else -> blend(bottom(mode, b, bands, accent), WHITE, 0.35f)
    }

    /** Warna dasar penanda puncak (alpha diatur pemanggil). */
    fun cap(mode: SpectrumColorMode, b: Int, bands: Int, accent: Int): Int = when (mode) {
        SpectrumColorMode.CINEMA -> blend(GOLD, WHITE, 0.5f)
        else -> blend(bottom(mode, b, bands, accent), WHITE, 0.75f)
    }
}
