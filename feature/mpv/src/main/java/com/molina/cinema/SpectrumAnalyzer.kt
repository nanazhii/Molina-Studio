package com.molina.cinema

import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [SPECTRUM-JUMP] Mengatur analisis spektrum satu berkas: satu pekerja "kepala" mengisi trek berurutan dari awal
 * (prioritas latar), dan setelah seek jauh ke area yang belum dianalisis, satu pekerja "lompat" mengisi dari posisi baru
 * (prioritas normal). Seek beruntun (menggeser seekbar) didebounce. Pekerja berhenti begitu bertemu frame yang sudah
 * terisi; kepala lalu melanjutkan ke lubang berikutnya sampai trek lengkap. Aman dari thread mana pun.
 */
internal class SpectrumAnalyzer(
    private val path: String,
    private val positionMs: () -> Long, // posisi putar mpv (ms); < 0 = belum diketahui
    private val onTrack: (SpectrumTrack) -> Unit,
    private val onUnsupported: () -> Unit,
) {
    private companion object {
        const val DIAG = "spectrum"
        const val DEBOUNCE_MS = 120L
        const val LATENCY_MS = 70L // sama dengan SpectrumView.latencyMs
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    @Volatile private var track: SpectrumTrack? = null
    @Volatile private var cancelled = false
    @Volatile private var pendingSeek = false
    private var debounceJob: Job? = null
    private var jumpJob: Job? = null
    private var jumpStart = -1

    fun start() {
        scope.launch {
            var lastGap = -1
            var stuck = 0
            while (isActive && !cancelled) {
                val t = track
                val gap = t?.firstGap() ?: 0
                if (t != null && t.endFrame > 0 && gap >= t.endFrame) {
                    t.complete = true
                    MpvLog.i(DIAG, "analisis lengkap: ${t.ready} frame")
                    break
                }
                if (gap == lastGap) {
                    if (++stuck >= 3) { MpvLog.w(DIAG, "kepala macet di frame $gap, dihentikan"); break }
                } else { stuck = 0; lastGap = gap }
                val r = SpectrumDecoder.fillFrom(path, t, gap, Process.THREAD_PRIORITY_BACKGROUND, "kepala", { isActive && !cancelled }, ::createTrack)
                if (r == SpectrumDecoder.End.FAILED) { if (track == null) onUnsupported(); break }
                if (r == SpectrumDecoder.End.CANCELLED || r == SpectrumDecoder.End.FULL) break
            }
        }
    }

    fun cancel() {
        cancelled = true
        scope.cancel()
    }

    /** Dipanggil pada tiap event seek/restart mpv (sering saat scrubbing); evaluasi didebounce. */
    fun onSeek() {
        if (cancelled) return
        if (track == null) { pendingSeek = true; return }
        scheduleEvaluate()
    }

    private fun scheduleEvaluate() {
        synchronized(lock) {
            debounceJob?.cancel()
            debounceJob = scope.launch { delay(DEBOUNCE_MS); evaluate() }
        }
    }

    private fun evaluate() {
        val t = track ?: return
        val pos = positionMs()
        if (pos < 0) return
        val frame = t.frameFor(pos - LATENCY_MS)
        synchronized(lock) {
            val running = jumpJob?.isActive == true
            val start = SpectrumJump.plan(t, frame, running, jumpStart)
            if (start < 0) return
            jumpJob?.cancel()
            jumpStart = start
            MpvLog.i(DIAG, "lompat: pos=${pos}ms frame=$frame mulai=$start kepala=${t.firstGap()}")
            jumpJob = scope.launch {
                SpectrumDecoder.fillFrom(path, t, start, Process.THREAD_PRIORITY_DEFAULT, "lompat", { isActive && !cancelled }, ::createTrack)
            }
        }
    }

    private fun createTrack(sampleRate: Int, estFrames: Int): SpectrumTrack? {
        var created = false
        val t = synchronized(lock) {
            val cur = track
            if (cur != null) cur else {
                val hop = (sampleRate / SpectrumMath.FPS).coerceAtLeast(1)
                SpectrumTrack(sampleRate, hop, initialFrames = estFrames.coerceAtLeast(64)).also { track = it; created = true }
            }
        }
        if (t.sampleRate != sampleRate) return null
        if (created) {
            onTrack(t)
            if (pendingSeek) { pendingSeek = false; scheduleEvaluate() }
        }
        return t
    }
}
