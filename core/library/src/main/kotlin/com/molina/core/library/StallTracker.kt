package com.molina.core.library

/**
 * [E-3b-3b] Keputusan "kapan menurunkan kualitas otomatis" untuk mode AUTO (lanjutan [AUTO-NET-2]). Murni JVM:
 * waktu disuntikkan pemanggil (elapsedRealtime), tanpa Android, tanpa efek samping. Pemanggil tetap memeriksa
 * isFinishing dan mode kualitas, lalu menjalankan efek (AdaptiveTier.downshift, muat ulang).
 *
 * Aturan (sama dengan perilaku sebelumnya di MPVActivity):
 * - macet dihitung dalam jendela [windowMs]; mencapai [threshold] macet memicu penurunan;
 * - setelah penurunan berhasil ada jeda [cooldownMs]; saat satu penurunan sedang berjalan, yang lain ditolak;
 * - penurunan berhasil mengosongkan hitungan macet; penurunan gagal tidak mengubah hitungan maupun jeda.
 */
class StallTracker(
    private val windowMs: Long = WINDOW_MS,
    private val threshold: Int = THRESHOLD,
    private val cooldownMs: Long = COOLDOWN_MS,
) {
    private val stalls = ArrayDeque<Long>()
    private var lastShiftAt: Long? = null
    private var shifting = false
    private var shiftStartedAt = 0L

    /** Jumlah macet dalam jendela saat ini (untuk log dan tes). */
    @get:Synchronized
    val stallCount: Int get() = stalls.size

    @get:Synchronized
    val isShifting: Boolean get() = shifting

    /** Catat satu macet pada [nowMs]; true bila ambang tercapai dan pemanggil sebaiknya mencoba menurunkan kualitas. */
    @Synchronized
    fun recordStall(nowMs: Long): Boolean {
        stalls.addLast(nowMs)
        while (stalls.isNotEmpty() && nowMs - stalls.first() > windowMs) stalls.removeFirst()
        return stalls.size >= threshold
    }

    /** Minta izin memulai penurunan: false bila masih dalam jeda atau penurunan lain sedang berjalan. */
    @Synchronized
    fun tryBeginShift(nowMs: Long): Boolean {
        if (shifting) return false
        val last = lastShiftAt
        if (last != null && nowMs - last < cooldownMs) return false
        shifting = true
        shiftStartedAt = nowMs
        return true
    }

    /** Penurunan tidak jadi (tidak ada yang bisa diturunkan, sedang seek, dll): hitungan dan jeda tidak berubah. */
    @Synchronized
    fun abortShift() {
        shifting = false
    }

    /** Penurunan berhasil: mulai jeda dari waktu permulaan, kosongkan hitungan macet. Diabaikan bila tidak ada yang berjalan. */
    @Synchronized
    fun commitShift() {
        if (!shifting) return
        lastShiftAt = shiftStartedAt
        stalls.clear()
        shifting = false
    }

    companion object {
        const val WINDOW_MS = 60_000L
        const val THRESHOLD = 3
        const val COOLDOWN_MS = 30_000L

        /** Buffering tanpa putus selama ini memicu percobaan penurunan tanpa menunggu hitungan macet. */
        const val LONG_STALL_MS = 8_000L
    }
}
