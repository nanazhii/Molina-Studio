import com.molina.cinema.*

private var failed = 0
private fun check(name: String, cond: Boolean) {
    if (cond) println("OK   $name") else { println("GAGAL $name"); failed++ }
}

private val base = listOf(SubCue(1_000, 3_000, "Satu"), SubCue(4_000, 6_000, "Dua"), SubCue(7_000, 9_000, "Tiga"))

fun main() {
    // --- TimeCode
    check("parse HH:MM:SS,mmm", TimeCode.parse("01:02:03,450") == 3_723_450L)
    check("parse MM:SS.m (1 digit = 100ms)", TimeCode.parse("02:03.5") == 123_500L)
    check("parse detik polos", TimeCode.parse("62.5") == 62_500L)
    check("parse H:MM:SS tanpa pecahan", TimeCode.parse("1:00:00") == 3_600_000L)
    check("menit > 59 ditolak", TimeCode.parse("00:75:00") == null)
    check("sampah ditolak", TimeCode.parse("abc") == null && TimeCode.parse("") == null && TimeCode.parse("1:2:3:4") == null)
    check("format dua arah", TimeCode.format(3_723_450L) == "01:02:03.450" && TimeCode.parse(TimeCode.format(3_723_450L)) == 3_723_450L)

    // --- replace: ubah teks/waktu, urut ulang, indeks baru
    val r1 = SubEdit.replace(base, 0, 5_000, 5_500, "Satu baru") as EditResult.Ok
    check("replace mengurutkan ulang", r1.cues.map { it.text } == listOf("Dua", "Satu baru", "Tiga") && r1.index == 1)
    check("replace tidak mengubah daftar lama", base[0].text == "Satu")
    check("replace: akhir < awal ditolak", (SubEdit.replace(base, 0, 2_000, 1_000, "x") as EditResult.Error).error == EditError.END_BEFORE_START)
    check("replace: teks kosong ditolak", (SubEdit.replace(base, 0, 1, 2, "  \n ") as EditResult.Error).error == EditError.EMPTY_TEXT)
    check("replace: indeks salah", (SubEdit.replace(base, 9, 1, 2, "x") as EditResult.Error).error == EditError.NO_SUCH_CUE)
    check("replace: waktu negatif", (SubEdit.replace(base, 0, -1, 2, "x") as EditResult.Error).error == EditError.BAD_TIME)
    check("replace: teks terlalu panjang", (SubEdit.replace(base, 0, 1, 2, "a".repeat(2_001)) as EditResult.Error).error == EditError.TOO_LONG)
    check("cleanText CRLF + tepi", SubEdit.cleanText("\r\nA\r\nB\r\n") == "A\nB")

    // --- delete / insert
    val d = SubEdit.delete(base, 1) as EditResult.Ok
    check("delete", d.cues.map { it.text } == listOf("Satu", "Tiga") && d.index == -1)
    val ins = SubEdit.insert(base, 3_500, "Sisip") as EditResult.Ok
    check("insert terurut + durasi bawaan", ins.cues[1].text == "Sisip" && ins.index == 1 && ins.cues[1].endMs == 5_500L)
    check("insert teks kosong ditolak", SubEdit.insert(base, 0, "") is EditResult.Error)

    // --- nudge dijepit
    val n = SubEdit.nudge(base, 0, -5_000, -5_000) as EditResult.Ok
    check("nudge dijepit ke 0", n.cues[0].startMs == 0L && n.cues[0].endMs == 0L)
    val n2 = SubEdit.nudge(base, 1, 500, -3_000) as EditResult.Ok
    check("nudge akhir tidak boleh < awal", n2.cues.first { it.text == "Dua" }.let { it.endMs >= it.startMs })

    // --- cueAt (biner) dan find
    check("cueAt dalam cue", SubEdit.cueAt(base, 4_500) == 1)
    check("cueAt di celah = cue sebelumnya", SubEdit.cueAt(base, 6_500) == 1)
    check("cueAt sebelum semua = 0", SubEdit.cueAt(base, 0) == 0)
    check("cueAt daftar kosong", SubEdit.cueAt(emptyList(), 100) == -1)
    check("find abaikan huruf besar", SubEdit.find(base, "dUA", 0) == 1)
    check("find memutar ke awal", SubEdit.find(base, "satu", 2) == 0)
    check("find tidak ketemu", SubEdit.find(base, "zzz", 0) == -1)

    // --- overlap
    val ov = listOf(SubCue(0, 5_000, "a"), SubCue(4_000, 6_000, "b"), SubCue(5_500, 7_000, "c"), SubCue(8_000, 9_000, "d"))
    check("overlap terdeteksi (b dan c)", SubEdit.overlaps(ov) == setOf(1, 2))
    check("tanpa overlap", SubEdit.overlaps(base).isEmpty())

    // --- sesi undo/redo/dirty
    val s = SubEditSession(base)
    check("awal tidak kotor", !s.dirty && !s.canUndo && !s.canRedo)
    s.apply(SubEdit.replace(s.cues, 0, 1_000, 3_000, "Ubah"))
    check("kotor setelah ubah", s.dirty && s.canUndo && s.cues[0].text == "Ubah")
    check("operasi gagal tidak mengubah apa pun", s.apply(SubEdit.replace(s.cues, 0, 5, 1, "x")) == null && s.lastError == EditError.END_BEFORE_START && s.cues[0].text == "Ubah")
    check("undo", s.undo() && s.cues == base && !s.dirty)
    s.redo(); s.markSaved()
    check("markSaved: tidak kotor, undo tetap ada", !s.dirty && s.canUndo)
    s.undo()
    check("undo setelah simpan = kotor lagi", s.dirty)
    s.redo(); s.undo()
    check("redo", s.redo() && s.cues[0].text == "Ubah")
    s.undo(); s.apply(SubEdit.delete(s.cues, 0))
    check("aksi baru membersihkan redo", !s.canRedo)
    val big = SubEditSession(base, maxHistory = 3)
    repeat(10) { big.apply(SubEdit.nudge(big.cues, 0, 10, 10)) }
    var steps = 0; while (big.undo()) steps++
    check("riwayat dibatasi", steps == 3)

    // --- round trip lewat codec (SRT dan ASS)
    val multi = listOf(SubCue(1_000, 2_500, "Baris 1\nBaris 2"), SubCue(3_000, 4_000, "Tes"))
    check("round trip SRT", SrtCodec.parse(SrtCodec.write(multi)) == multi)
    check("round trip ASS", AssCodec.parse(AssCodec.write(multi)) == multi)

    spectrumTests()

    println(if (failed == 0) "SEMUA LULUS" else "$failed GAGAL")
    if (failed > 0) kotlin.system.exitProcess(1)
}

private fun sine(freq: Double, sr: Int, amp: Double = 1.0): FloatArray =
    FloatArray(SpectrumMath.FFT_SIZE) { (amp * Math.sin(2.0 * Math.PI * freq * it / sr)).toFloat() }

private fun frameOf(x: FloatArray, sr: Int): ByteArray {
    val out = ByteArray(SpectrumMath.BANDS)
    SpectrumMath.frame(x, sr, SpectrumMath.bandEdges(sr), DoubleArray(SpectrumMath.FFT_SIZE), DoubleArray(SpectrumMath.FFT_SIZE), out, 0)
    return out
}

private fun u(b: Byte) = b.toInt() and 0xFF

private fun spectrumTests() {
    // --- FFT vs DFT naif
    val n = 64
    val re = DoubleArray(n) { Math.sin(it * 0.7) + 0.3 * Math.cos(it * 2.1) }
    val im = DoubleArray(n)
    val nr = DoubleArray(n); val ni = DoubleArray(n)
    for (k in 0 until n) for (t in 0 until n) { val a = -2.0 * Math.PI * k * t / n; nr[k] += re[t] * Math.cos(a); ni[k] += re[t] * Math.sin(a) }
    SpectrumMath.fft(re, im)
    check("FFT = DFT naif", (0 until n).all { Math.abs(re[it] - nr[it]) < 1e-9 && Math.abs(im[it] - ni[it]) < 1e-9 })
    check("FFT menolak ukuran bukan pangkat dua", runCatching { SpectrumMath.fft(DoubleArray(12), DoubleArray(12)) }.isFailure)

    // --- pita
    for (sr in intArrayOf(8_000, 22_050, 44_100, 48_000, 96_000)) {
        val e = SpectrumMath.bandEdges(sr)
        check("bandEdges naik ketat & <= N/2 @${sr}Hz", e.size == SpectrumMath.BANDS + 1 && (1 until e.size).all { e[it] > e[it - 1] || e[it] == SpectrumMath.FFT_SIZE / 2 } && e.last() <= SpectrumMath.FFT_SIZE / 2)
    }

    // --- frame: sinus 1 kHz harus puncak di pita yang memuat 1 kHz, jauh di atas pita lain
    val sr = 44_100
    val edges = SpectrumMath.bandEdges(sr)
    val binHz = sr.toDouble() / SpectrumMath.FFT_SIZE
    val f1k = frameOf(sine(1_000.0, sr), sr)
    val peakBand = (0 until SpectrumMath.BANDS).maxByOrNull { u(f1k[it]) }!!
    val expected = (0 until SpectrumMath.BANDS).first { 1_000.0 / binHz < edges[it + 1] }
    check("sinus 1 kHz: puncak di pita yang benar", peakBand == expected)
    check("sinus 1 kHz: puncak tinggi (>= 200)", u(f1k[peakBand]) >= 200)
    check("sinus 1 kHz: pita jauh redup (< 60)", u(f1k[0]) < 60 && u(f1k[SpectrumMath.BANDS - 1]) < 60)
    val f100 = frameOf(sine(100.0, sr), sr)
    val f8k = frameOf(sine(8_000.0, sr), sr)
    check("urutan frekuensi: 100 Hz < 1 kHz < 8 kHz (indeks pita puncak)",
        (0 until 32).maxByOrNull { u(f100[it]) }!! < peakBand && peakBand < (0 until 32).maxByOrNull { u(f8k[it]) }!!)
    val silent = frameOf(FloatArray(SpectrumMath.FFT_SIZE), sr)
    check("hening = 0 semua", silent.all { u(it) == 0 })
    val quiet = frameOf(sine(1_000.0, sr, 0.01), sr)
    check("amplitudo kecil jelas lebih rendah", u(quiet[peakBand]) < u(f1k[peakBand]) - 40)

    // --- resolusi bass: FFT 8192 untuk pita dengan pusat < 250 Hz
    val lowCountT = SpectrumMath.lowBandCount(sr)
    check("lowBandCount @44,1 kHz = 10 pita (pusat < 250 Hz)", lowCountT == 10)
    check("lowBandCount selalu dalam 0..BANDS", intArrayOf(8_000, 22_050, 48_000, 96_000).all { SpectrumMath.lowBandCount(it) in 0..SpectrumMath.BANDS })
    val edgesLowT = SpectrumMath.bandEdges(sr, fftSize = SpectrumMath.FFT_LOW_SIZE)
    fun lowFrame(freq: Double): ByteArray {
        val x = FloatArray(SpectrumMath.FFT_LOW_SIZE) { Math.sin(2.0 * Math.PI * freq * it / sr).toFloat() }
        val o = ByteArray(SpectrumMath.BANDS)
        SpectrumMath.frameLow(x, sr, edgesLowT, lowCountT, DoubleArray(SpectrumMath.FFT_LOW_SIZE), DoubleArray(SpectrumMath.FFT_LOW_SIZE), o, 0)
        return o
    }
    fun idealBand(freq: Double) = Math.floor(Math.log(freq / 40.0) / Math.log(400.0) * 32).toInt()
    for ((name, f) in listOf("A1" to 55.0, "A2" to 110.0, "A3" to 220.0)) {
        val o = lowFrame(f)
        check("frameLow $name (${f.toInt()} Hz): puncak di pita ideal ${idealBand(f)}", (0 until lowCountT).maxByOrNull { u(o[it]) } == idealBand(f))
    }
    check("frameLow tidak menyentuh pita di atas batas bass", (lowCountT until SpectrumMath.BANDS).all { u(lowFrame(110.0)[it]) == 0 })

    // --- penyelarasan waktu: pusat jendela bass (8192) = pusat jendela utama (4096)
    run {
        val hopA = sr / SpectrumMath.FPS
        val trk = SpectrumTrack(sr, hopA, initialFrames = 64)
        val fb = SpectrumFrameBuilder(sr, trk)
        val burstCenter = 20 * hopA + SpectrumMath.FFT_SIZE / 2 + 300 // sedikit setelah pusat frame 20
        for (i in 0 until 60_000) {
            val inBurst = Math.abs(i - burstCenter) <= 441
            fb.push(if (inBurst) (0.4 * Math.sin(2.0 * Math.PI * 100.0 * i / sr) + 0.4 * Math.sin(2.0 * Math.PI * 1_000.0 * i / sr)).toFloat() else 0f)
        }
        val d = trk.data
        fun best(range: IntRange): Int { // frame dengan nilai tertinggi pada pita terkuat di [range]
            var bf = -1; var bv = -1
            for (b in range) for (f in 0 until trk.ready) { val v = u(d[f * trk.bands + b]); if (v > bv) { bv = v; bf = f } }
            return bf
        }
        val fLow = best(0 until lowCountT)
        val fMain = best(lowCountT until SpectrumMath.BANDS)
        check("burst 100 Hz + 1 kHz: puncak bass = puncak utama (bass=$fLow, utama=$fMain)", fLow == fMain)
        check("puncak di frame 20 (pusat jendela terdekat burst)", fMain == 20)
    }

    // --- SpectrumTrack: tulis, tumbuh, interpolasi, batas
    val hop = sr / SpectrumMath.FPS
    val tr = SpectrumTrack(sr, hop, initialFrames = 2, maxFrames = 8)
    val fr0 = ByteArray(32) { 0 }; val fr1 = ByteArray(32) { 255.toByte() }
    check("belum ada frame = false", !tr.sample(100, FloatArray(32)))
    tr.append(fr0); tr.append(fr1); tr.append(fr1) // memicu pertumbuhan array (2 -> 4)
    check("array tumbuh, ready = 3", tr.ready == 3)
    val outF = FloatArray(32)
    val midMs = ((0.5 * hop + SpectrumMath.FFT_SIZE / 2.0) * 1000.0 / sr).toLong()
    check("interpolasi linear ~0.5", tr.sample(midMs, outF) && Math.abs(outF[0] - 0.5f) < 0.05f)
    check("di luar frame yang siap = false", !tr.sample(60_000, outF))
    repeat(10) { tr.append(fr1) }
    check("dibatasi maxFrames", tr.ready == 8 && !tr.append(fr1))

    // --- SpectrumSmoother: naik cepat, turun lambat, puncak tertahan
    val sm = SpectrumSmoother(4)
    val hi = FloatArray(4) { 1f }
    repeat(5) { sm.step(hi, 16f) }
    val rise = sm.level[0]
    check("naik cepat (> 0.7 dalam 80 ms)", rise > 0.7f)
    sm.step(null, 16f)
    check("turun lebih lambat dari naik", sm.level[0] > rise * 0.85f)
    check("puncak tertahan di atas level", sm.peak[0] >= sm.level[0])
    repeat(200) { sm.step(null, 16f) }
    check("akhirnya diam", sm.settled())
    sm.step(hi, 16f); sm.reset()
    check("reset", sm.level.all { it == 0f } && sm.peak.all { it == 0f })
}
