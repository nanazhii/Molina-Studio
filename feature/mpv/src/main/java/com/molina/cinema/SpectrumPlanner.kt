package com.molina.cinema

import kotlin.math.abs
import kotlin.math.max

/*
 * [SPECTRUM] Perencana analisis: kapan dekoder melompat. Dekoder menganalisis berurutan dari awal; bila pemakai
 * menggeser seek ke posisi yang belum teranalisis, dekoder harus melompat ke playhead (bukan menunggu sampai
 * menyusul), lalu kembali mengisi celah di belakangnya. Kotlin murni agar bisa diuji di scripts/mpv-tests.
 */
internal sealed class SpectrumStep {
    object Continue : SpectrumStep()
    object Done : SpectrumStep()

    /** Lompat ke [frame]. [forPlayhead] true = karena playhead keluar dari area terisi (perlu ditunda agar tidak bolak-balik saat menggeser). */
    class Jump(val frame: Int, val forPlayhead: Boolean) : SpectrumStep()
}

internal object SpectrumPlanner {
    /** Playhead harus lebih jauh dari frontier sebanyak ini (2 detik) baru dianggap perlu melompat. */
    const val JUMP_AHEAD_FRAMES = 2 * SpectrumMath.FPS
    /** Tidak melompat lagi untuk playhead yang berdekatan (1,5 detik) dengan lompatan sebelumnya. */
    const val GUARD_FRAMES = 45
    /** Mulai sedikit sebelum playhead (1 detik) supaya geser mundur kecil tetap terisi. */
    const val REWIND_FRAMES = 30

    /**
     * Dipanggil berkala saat dekoder berjalan. [cur] = frame berikutnya yang akan ditulis, [segStart] = frame tujuan
     * lompatan terakhir (0 untuk pass awal): dekoder mendarat sedikit sebelum tujuan, jadi frame terisi di bawah
     * [segStart] bukan tabrakan. [lastJumpPlay] = playhead saat lompatan playhead terakhir, [knownEnd] = akhir trek
     * (eksklusif) bila EOS sudah pernah tercapai, else -1.
     */
    fun whileRunning(track: SpectrumTrack, cur: Int, segStart: Int, playFrame: Int, lastJumpPlay: Int, knownEnd: Int, tried: Set<Int>): SpectrumStep {
        if (cur >= segStart && track.has(cur)) { // dekoder menabrak rentang yang sudah terisi: lanjutkan ke celah berikutnya
            if (knownEnd >= 0) return atEnd(track, knownEnd, tried)
            return SpectrumStep.Jump(track.firstGap(cur), false)
        }
        val wants = !track.has(playFrame) && (knownEnd < 0 || playFrame < knownEnd) && abs(playFrame - lastJumpPlay) > GUARD_FRAMES &&
            (playFrame >= cur + JUMP_AHEAD_FRAMES || playFrame < cur)
        return if (wants) SpectrumStep.Jump(max(0, playFrame - REWIND_FRAMES), true) else SpectrumStep.Continue
    }

    /** Saat EOS tercapai: isi celah yang belum pernah dicoba, atau selesai. [tried] mencegah putaran tak berujung. */
    fun atEnd(track: SpectrumTrack, knownEnd: Int, tried: Set<Int>): SpectrumStep {
        var g = track.firstGap(0)
        while (g < knownEnd) {
            if (g !in tried) return SpectrumStep.Jump(g, false)
            val ns = track.nextStart(g) // lewati seluruh celah yang sudah dicoba, bukan hanya satu frame
            if (ns == Int.MAX_VALUE) break
            g = track.firstGap(ns)
        }
        return SpectrumStep.Done
    }
}
