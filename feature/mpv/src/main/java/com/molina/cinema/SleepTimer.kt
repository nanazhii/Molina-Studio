package com.molina.cinema

/**
 * Sleep timer pemutar: hitung mundur, fade-out volume mpv pada [FADE_MS] terakhir, lalu jeda.
 *
 * Tanpa dependensi Android (waktu dan penjadwalan disuntikkan) supaya logikanya bisa diuji di
 * JVM. Semua pemanggilan harus dari satu thread (main). Panggil [cancel] di onDestroy.
 */
class SleepTimer(
    private val clock: () -> Long,
    private val postDelayed: (Long, Runnable) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val getVolume: () -> Double?,
    private val setVolume: (Double) -> Unit,
    private val isPaused: () -> Boolean,
    private val pause: () -> Unit,
    private val onExpired: () -> Unit,
) {
    var active = false
        private set

    private var endAt = 0L
    private var baseVolume: Double? = null

    private val tick = Runnable { onTick() }
    private val restore = Runnable { restoreVolume() }

    fun remainingMs(): Long = if (active) (endAt - clock()).coerceAtLeast(0L) else 0L

    /** Mulai ulang dengan [minutes] menit; nilai <= 0 hanya membatalkan timer yang berjalan. */
    fun start(minutes: Int) {
        cancel()
        if (minutes <= 0) return
        active = true
        endAt = clock() + minutes * 60_000L
        scheduleNext()
    }

    /** Batalkan timer dan kembalikan volume bila sedang di tengah fade. */
    fun cancel() {
        remove(tick)
        remove(restore)
        restoreVolume()
        active = false
    }

    private fun scheduleNext() {
        val left = remainingMs()
        val delay = if (left > FADE_MS) minOf(left - FADE_MS, MAX_SLEEP_MS) else STEP_MS
        postDelayed(delay, tick)
    }

    private fun onTick() {
        if (!active) return
        val left = remainingMs()
        if (left <= 0L) {
            finish()
            return
        }
        if (isPaused()) {
            // jeda di tengah fade: volume kembali penuh, fade dilanjutkan saat diputar lagi
            restoreVolume()
        } else if (left <= FADE_MS) {
            val base = baseVolume ?: getVolume()?.also { baseVolume = it }
            if (base != null) setVolume(base * left / FADE_MS)
        }
        scheduleNext()
    }

    private fun finish() {
        active = false
        pause()
        // volume dikembalikan setelah jeda berlaku agar pemutaran berikutnya tidak senyap
        postDelayed(RESTORE_DELAY_MS, restore)
        onExpired()
    }

    private fun restoreVolume() {
        baseVolume?.let { setVolume(it) }
        baseVolume = null
    }

    companion object {
        const val FADE_MS = 10_000L
        const val STEP_MS = 500L
        const val MAX_SLEEP_MS = 60_000L
        const val RESTORE_DELAY_MS = 300L
    }
}
