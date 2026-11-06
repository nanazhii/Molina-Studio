import com.molina.cinema.*
import kotlin.math.PI
import kotlin.math.sin

private var failed = 0
private fun check(name: String, cond: Boolean) {
    if (cond) println("OK   $name") else { println("GAGAL $name"); failed++ }
}

private fun signal(sr: Int, seconds: Int): FloatArray = FloatArray(sr * seconds) { i ->
    val t = i.toDouble() / sr
    (0.4 * sin(2 * PI * (200 + 40 * t) * t) + 0.25 * sin(2 * PI * 3000 * t) + 0.15 * sin(2 * PI * 9000 * (1 + 0.1 * sin(t)) * t)).toFloat()
}

private fun frameBytes(t: SpectrumTrack, i: Int): List<Byte> = (0 until t.bands).map { t.data[i * t.bands + it] }


/**
 * Simulasi alur dekoder (cermin SpectrumDecoder): sumber sampel dengan seek, waktu virtual, playhead berskrip.
 * Memeriksa terminasi (tanpa putaran lompat tak berujung), cakupan penuh, dan kesamaan dengan analisis berurutan.
 */
private class SimResult(val track: SpectrumTrack, val steps: Int, val jumps: Int, val finished: Boolean, val firstCoveredAtMs: Map<Long, Long>)

private fun simulate(x: FloatArray, sr: Int, playhead: (Long) -> Long, probeAtMs: List<Long>, decodeSpeed: Double = 8.0): SimResult {
    val hop = sr / SpectrumMath.FPS
    val track = SpectrumTrack(sr, hop, initialFrames = 64)
    val b = SpectrumFrameBuilder(sr, track)
    val chunk = 1024
    var pos = 0                  // posisi sampel sumber (mutlak) yang akan dibaca berikutnya
    var pending = false
    var eos = false
    var knownEnd = -1
    var segStart = 0
    var lastJumpPlay = Int.MIN_VALUE / 2
    var wantSince = 0L
    var lastPoll = -1_000L
    val tried = HashSet<Int>()
    var now = 0L                 // ms virtual
    var jumps = 0
    var steps = 0
    val firstCovered = HashMap<Long, Long>() // probe (posisi ms) -> waktu virtual pertama kali frame di posisi itu tersedia

    fun jumpTo(frame: Int) {
        val tUs = frame.toLong() * hop * 1_000_000L / sr
        val landUs = maxOf(0L, tUs - 200_000L)
        pos = ((landUs * sr / 1_000_000L).toInt() / chunk) * chunk // mendarat di batas chunk (sebelum target)
        segStart = frame; eos = false; pending = true; jumps++
    }

    while (steps++ < 400_000) {
        if (now - lastPoll >= 120 && b.frameIndex >= 0 && !pending && track.maxEnd() > 0) {
            lastPoll = now
            val pf = track.frameAt(playhead(now))
            when (val st = SpectrumPlanner.whileRunning(track, b.frameIndex, segStart, pf, lastJumpPlay, knownEnd, tried)) {
                is SpectrumStep.Jump -> if (st.forPlayhead) {
                    if (wantSince == 0L) wantSince = now
                    if (now - wantSince >= 350) { lastJumpPlay = pf; wantSince = 0L; jumpTo(st.frame) }
                } else { tried.add(st.frame); wantSince = 0L; jumpTo(st.frame) }
                SpectrumStep.Done -> { track.complete = true; return SimResult(track, steps, jumps, true, firstCovered) }
                SpectrumStep.Continue -> wantSince = 0L
            }
        }
        if (!eos) {
            if (pending) { b.restart(pos.toLong()); pending = false }
            val end = minOf(pos + chunk, x.size)
            for (i in pos until end) b.push(x[i])
            now += (chunk * 1000.0 / sr / decodeSpeed).toLong().coerceAtLeast(1)
            pos = end
            if (pos >= x.size) eos = true
            for (pm in probeAtMs) if (!firstCovered.containsKey(pm) && now >= 0 && track.has(track.frameAt(pm))) firstCovered[pm] = now
        } else {
            knownEnd = track.maxEnd()
            when (val st = SpectrumPlanner.atEnd(track, knownEnd, tried)) {
                is SpectrumStep.Jump -> { tried.add(st.frame); jumpTo(st.frame) }
                else -> { track.complete = true; return SimResult(track, steps, jumps, true, firstCovered) }
            }
        }
    }
    return SimResult(track, steps, jumps, false, firstCovered)
}

private fun verifyAgainst(name: String, ref: SpectrumTrack, r: SimResult, maxHole: Int = 6) {
    check("$name: selesai (tanpa putaran tak berujung, ${r.jumps} lompatan)", r.finished && r.jumps < 40)
    var holes = 0; var mismatch = 0
    for (f in 0 until ref.ready) {
        if (!r.track.has(f)) holes++ else if (frameBytes(ref, f) != frameBytes(r.track, f)) mismatch++
    }
    check("$name: celah <= $maxHole frame (ada $holes), tidak ada beda isi ($mismatch)", holes <= maxHole && mismatch == 0)
}

fun main() {
    val fr = ByteArray(32) { 7 }

    // --- rentang frame: tulis tak berurutan, gabung, celah
    val t = SpectrumTrack(44100, 1470, initialFrames = 4, maxFrames = 100)
    check("kosong: tidak ada frame", !t.has(0) && t.ready == 0 && t.maxEnd() == 0 && t.firstGap(0) == 0)
    t.writeAt(10, fr); t.writeAt(11, fr)
    check("lompat ke 10: ready tetap 0, has(10)", t.ready == 0 && t.has(10) && t.has(11) && !t.has(9) && !t.has(12))
    check("firstGap melewati rentang", t.firstGap(0) == 0 && t.firstGap(10) == 12 && t.maxEnd() == 12)
    t.writeAt(0, fr); t.writeAt(1, fr)
    check("rentang awal: ready = 2", t.ready == 2 && t.firstGap(0) == 2)
    for (i in 2..8) t.writeAt(i, fr)
    check("celah 1 frame tersisa (9)", t.firstGap(0) == 9 && t.ready == 9)
    t.writeAt(9, fr)
    check("rentang menyatu: ready = 12, tanpa celah", t.ready == 12 && t.firstGap(0) == 12)
    t.writeAt(10, fr)
    check("tulis ulang frame terisi tidak mengubah rentang", t.ready == 12 && t.maxEnd() == 12)
    check("di luar batas ditolak", !t.writeAt(100, fr) && !t.writeAt(-1, fr))
    check("array tumbuh untuk lompatan jauh", t.writeAt(60, fr) && t.has(60) && t.data.size >= 61 * 32)

    // --- sample() hanya pada frame terisi
    val s = SpectrumTrack(44100, 1470, initialFrames = 4)
    s.writeAt(100, ByteArray(32) { 255.toByte() }); s.writeAt(101, ByteArray(32) { 255.toByte() })
    val out = FloatArray(32)
    val msAt100 = ((100 * 1470 + 2048) * 1000L / 44100) + 1
    check("sample di rentang terisi true", s.sample(msAt100, out) && out[0] > 0.99f)
    check("sample di celah (sebelum) false", !s.sample(1_000, out))
    check("sample setelah rentang false", !s.sample(10_000, out))
    check("frameAt konsisten dengan sample", s.frameAt(msAt100) == 100)

    // --- builder: analisis terputus-putus (restart) = analisis berurutan, frame demi frame
    val sr = 44100
    val x = signal(sr, 14)
    val ref = SpectrumTrack(sr, sr / SpectrumMath.FPS, initialFrames = 64)
    SpectrumFrameBuilder(sr, ref).also { b -> for (v in x) b.push(v) }
    val total = ref.ready
    check("referensi berurutan menghasilkan frame", total > 380)

    val seg = SpectrumTrack(sr, sr / SpectrumMath.FPS, initialFrames = 64)
    val b2 = SpectrumFrameBuilder(sr, seg)
    val jumpAt = 8L * sr + 123 // sengaja tidak selaras dengan batas hop
    b2.restart(jumpAt)
    val k = b2.frameIndex
    check("restart: frame pertama setelah posisi + lookahead", k > 0 && k.toLong() * seg.hopSamples - 2048 >= jumpAt && (k - 1).toLong() * seg.hopSamples - 2048 < jumpAt)
    for (i in jumpAt.toInt() until x.size) b2.push(x[i])
    check("setelah lompat: rentang di ekor, ready 0", seg.ready == 0 && seg.has(k) && !seg.has(k - 1) && seg.maxEnd() == total)
    b2.restart(0L)
    check("restart(0) kembali ke frame 0", b2.frameIndex == 0)
    var i0 = 0
    while (b2.frameIndex < k + 3 && i0 < x.size) b2.push(x[i0++])
    check("celah terisi: seluruh trek tertutup", seg.ready == total && seg.firstGap(0) == total)
    var same = 0; var diff = 0
    for (f in 0 until total) if (frameBytes(ref, f) == frameBytes(seg, f)) same++ else diff++
    check("frame hasil lompat identik dengan berurutan (semua $total)", diff == 0 && same == total)

    // posisi awal hampir 0 dianggap awal trek
    val near = SpectrumTrack(sr, sr / SpectrumMath.FPS, initialFrames = 64)
    val bn = SpectrumFrameBuilder(sr, near)
    bn.restart(500L)
    check("restart di dekat 0 -> frame 0", bn.frameIndex == 0)
    bn.restart(-5L)
    check("restart negatif aman", bn.frameIndex == 0)

    // --- perencana
    val p = SpectrumTrack(44100, 1470, initialFrames = 4)
    for (i in 0 until 100) p.writeAt(i, fr) // dekoder sudah sampai frame 100
    var step = SpectrumPlanner.whileRunning(p, 100, 0, 50, Int.MIN_VALUE / 2, -1, emptySet())
    check("playhead di area terisi: lanjut", step is SpectrumStep.Continue)
    step = SpectrumPlanner.whileRunning(p, 100, 0, 130, Int.MIN_VALUE / 2, -1, emptySet())
    check("playhead hanya <2 dtk di depan: lanjut (dekoder menyusul)", step is SpectrumStep.Continue)
    step = SpectrumPlanner.whileRunning(p, 100, 0, 900, Int.MIN_VALUE / 2, -1, emptySet())
    check("playhead jauh di depan: lompat (mundur 1 dtk)", step is SpectrumStep.Jump && step.frame == 870 && step.forPlayhead)
    step = SpectrumPlanner.whileRunning(p, 100, 0, 900, 880, -1, emptySet())
    check("lompatan serupa baru saja dilakukan: tahan (guard)", step is SpectrumStep.Continue)
    step = SpectrumPlanner.whileRunning(p, 100, 0, 20, Int.MIN_VALUE / 2, -1, emptySet())
    check("playhead di area terisi di belakang: lanjut", step is SpectrumStep.Continue)

    // setelah lompat: frontier di 870.., celah di 100..870; playhead mundur ke celah
    for (i in 870 until 950) p.writeAt(i, fr)
    step = SpectrumPlanner.whileRunning(p, 950, 0, 400, 900, -1, emptySet())
    check("geser mundur ke celah: lompat ke celah", step is SpectrumStep.Jump && step.frame == 370 && step.forPlayhead)

    // dekoder menabrak rentang terisi
    step = SpectrumPlanner.whileRunning(p, 870, 0, 880, 900, -1, emptySet())
    check("menabrak rentang terisi (akhir belum diketahui): lompat ke ujung rentang", step is SpectrumStep.Jump && step.frame == 950 && !step.forPlayhead)
    step = SpectrumPlanner.whileRunning(p, 870, 0, 880, 900, 950, emptySet())
    check("menabrak rentang terisi (akhir diketahui): isi celah 100", step is SpectrumStep.Jump && step.frame == 100 && !step.forPlayhead)

    // setelah lompat ke celah (segStart=100): mendarat di frame 94 yang sudah terisi = bukan tabrakan
    val gp = SpectrumTrack(44100, 1470, initialFrames = 4)
    for (i in 0 until 100) gp.writeAt(i, fr); for (i in 300 until 400) gp.writeAt(i, fr)
    check("mendarat sebelum segStart di area terisi: lanjut (bukan tabrakan)", SpectrumPlanner.whileRunning(gp, 94, 100, 94, Int.MIN_VALUE / 2, 400, setOf(100)) is SpectrumStep.Continue)
    for (i in 100 until 300) gp.writeAt(i, fr)
    check("mencapai segStart lalu menabrak rentang berikutnya: berhenti/isi celah", SpectrumPlanner.whileRunning(gp, 300, 100, 300, Int.MIN_VALUE / 2, 400, setOf(100)) is SpectrumStep.Done)

    // playhead melewati akhir trek yang sudah diketahui: jangan melompat ke area yang tidak punya frame
    step = SpectrumPlanner.whileRunning(p, 960, 0, 5_000, Int.MIN_VALUE / 2, 950, emptySet())
    check("playhead melewati akhir trek: tidak melompat", step is SpectrumStep.Continue)

    // atEnd
    step = SpectrumPlanner.atEnd(p, 950, emptySet())
    check("atEnd: celah pertama = 100", step is SpectrumStep.Jump && step.frame == 100)
    step = SpectrumPlanner.atEnd(p, 950, setOf(100))
    check("atEnd: celah yang sudah dicoba dilewati -> selesai", step is SpectrumStep.Done)
    check("nextStart: awal rentang berikutnya", p.nextStart(100) == 870 && p.nextStart(870) == Int.MAX_VALUE)
    // celah kedua setelah yang pertama dicoba: harus ditemukan, tidak berputar di celah yang sama
    val q = SpectrumTrack(44100, 1470, initialFrames = 4)
    for (i in 0 until 50) q.writeAt(i, fr); for (i in 200 until 250) q.writeAt(i, fr); for (i in 400 until 450) q.writeAt(i, fr)
    check("atEnd: celah pertama dicoba -> pindah ke celah kedua", (SpectrumPlanner.atEnd(q, 450, setOf(50)) as? SpectrumStep.Jump)?.frame == 250)
    check("atEnd: semua celah dicoba -> selesai", SpectrumPlanner.atEnd(q, 450, setOf(50, 250)) is SpectrumStep.Done)
    for (i in 100 until 870) p.writeAt(i, fr)
    check("atEnd: tidak ada celah -> selesai", SpectrumPlanner.atEnd(p, 950, emptySet()) is SpectrumStep.Done)


    // --- simulasi alur dekoder dengan seek
    val sr2 = 22050
    val x2 = signal(sr2, 24)
    val ref2 = SpectrumTrack(sr2, sr2 / SpectrumMath.FPS, initialFrames = 64)
    SpectrumFrameBuilder(sr2, ref2).also { b -> for (v in x2) b.push(v) }

    verifyAgainst("tanpa seek", ref2, simulate(x2, sr2, { it }, emptyList()))

    // pemutaran 1x dari 0; di detik virtual 1.0 pengguna menggeser ke 18 dtk lalu lanjut diputar
    val seekFwd = simulate(x2, sr2, { t -> if (t < 1_000) t else 18_000 + (t - 1_000) }, listOf(18_500L))
    verifyAgainst("seek jauh ke depan", ref2, seekFwd)
    val tCovered = seekFwd.firstCoveredAtMs[18_500L]
    check("spektrum di posisi seek tersedia cepat (virtual ${tCovered} ms < 2500)", tCovered != null && tCovered < 2_500)

    // geser ke depan, lalu mundur ke area yang belum terisi (celah), lalu ke depan lagi
    val back = simulate(x2, sr2, { t -> when { t < 800 -> t; t < 1_800 -> 15_000 + (t - 800); t < 3_000 -> 6_000 + (t - 1_800); else -> 21_000 + (t - 3_000) } }, listOf(15_500L, 6_500L))
    verifyAgainst("seek maju-mundur-maju", ref2, back, maxHole = 12)

    // seek hingga hampir akhir trek
    verifyAgainst("seek ke ujung trek", ref2, simulate(x2, sr2, { t -> if (t < 500) t else 23_200 }, emptyList()), maxHole = 12)

    // menggeser terus-menerus (drag): banyak posisi acak cepat, lalu berhenti
    val drag = simulate(x2, sr2, { t -> if (t < 600) t else if (t < 1_500) (t * 7919 % 20_000) else 9_000 }, emptyList())
    verifyAgainst("drag seekbar lalu berhenti", ref2, drag, maxHole = 12)

    // dekoder lambat (1,2x waktu nyata, mis. perangkat tua): lompatan benar-benar dibutuhkan
    val slow = simulate(x2, sr2, { t -> if (t < 1_000) t else minOf(18_000 + (t - 1_000), 23_900L) }, listOf(18_500L), decodeSpeed = 1.2)
    verifyAgainst("[lambat] seek jauh ke depan", ref2, slow, maxHole = 12)
    val slowAt = slow.firstCoveredAtMs[18_500L]
    check("[lambat] spektrum di posisi seek tersedia < 4 dtk virtual (${slowAt} ms), bukan menunggu dekoder menyusul", slowAt != null && slowAt < 4_000 && slow.jumps >= 1)
    verifyAgainst("[lambat] maju-mundur-maju", ref2, simulate(x2, sr2, { t -> when { t < 800 -> t; t < 1_800 -> 15_000 + (t - 800); t < 3_000 -> 6_000 + (t - 1_800); else -> 21_000 + (t - 3_000) } }, listOf(15_500L, 6_500L), decodeSpeed = 1.2), maxHole = 12)
    verifyAgainst("[lambat] drag seekbar", ref2, simulate(x2, sr2, { t -> if (t < 600) t else if (t < 1_500) (t * 7919 % 20_000) else 9_000 }, emptyList(), decodeSpeed = 1.2), maxHole = 12)

    if (failed > 0) { println("$failed tes GAGAL"); System.exit(1) } else println("Semua tes lulus")
}
