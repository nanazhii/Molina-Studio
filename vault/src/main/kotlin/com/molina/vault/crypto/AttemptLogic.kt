package com.molina.vault.crypto

// MOLINA-H1

/** Penyimpanan kunci-nilai minimal agar logika limiter bisa diuji di JVM tanpa Android. */
interface AttemptStore {
    fun long(key: String, def: Long): Long
    fun int(key: String, def: Int): Int
    /** Tulis semua nilai sekaligus (atomik). */
    fun commit(longs: Map<String, Long>, ints: Map<String, Int>)
    fun clear()
}

/**
 * Logika murni pembatas percobaan unlock. Waktu memakai jam monoton (elapsedRealtime), bukan jam dinding
 * yang bisa diubah pengguna atau penyerang. Setelah reboot (BOOT_COUNT berubah, atau jam monoton mundur)
 * tunda terakhir diulang penuh dari awal: reboot tidak boleh menjadi jalan pintas menghapus penundaan.
 * Bila reboot terjadi setelah tunda sebenarnya habis, pengguna sah menunggu satu kali lagi (konservatif).
 * Tetap hanya penghambat: perangkat root bisa menghapus penyimpanan ini.
 */
class AttemptLogic(
    private val store: AttemptStore,
    private val clock: () -> Long,
    private val bootId: () -> Long,
) {
    companion object {
        const val FREE_TRIES = 5
        const val BASE_DELAY_MS = 30_000L
        const val MAX_DELAY_MS = 3_600_000L
        const val UNKNOWN_BOOT = -1L

        fun delayFor(failures: Int): Long =
            if (failures < FREE_TRIES) 0L
            else minOf(BASE_DELAY_MS shl minOf(failures - FREE_TRIES, 7), MAX_DELAY_MS)
    }

    /** Sisa waktu terkunci dalam milidetik (0 bila bebas). */
    fun remainingLockMs(): Long {
        sync()
        return (store.long("until_e", 0L) - clock()).coerceAtLeast(0L)
    }

    fun recordFailure() {
        val now = sync()
        val n = store.int("fails", 0) + 1
        val d = delayFor(n)
        store.commit(
            mapOf("until_e" to now + d, "delay" to d, "boot" to bootId(), "last_e" to now),
            mapOf("fails" to n),
        )
    }

    fun recordSuccess() = store.clear()

    /** Deteksi reboot dan, bila perlu, ulangi tunda penuh. Menulis hanya bila ada perubahan. */
    private fun sync(): Long {
        val now = clock()
        val boot = bootId()
        val savedBoot = store.long("boot", UNKNOWN_BOOT)
        val last = store.long("last_e", -1L)
        val bootChanged = boot != UNKNOWN_BOOT && savedBoot != UNKNOWN_BOOT && boot != savedBoot
        val clockWentBack = last >= 0L && now < last
        val rebooted = bootChanged || clockWentBack
        if (rebooted || savedBoot != boot) {
            val delay = store.long("delay", 0L)
            // Setelah reboot nilai until_e lama berasal dari jam monoton boot sebelumnya, jadi tidak boleh dibawa.
            val until = when {
                rebooted -> if (delay > 0L) now + delay else 0L
                else -> store.long("until_e", 0L)
            }
            store.commit(mapOf("until_e" to until, "boot" to boot, "last_e" to now), emptyMap())
        }
        return now
    }
}
