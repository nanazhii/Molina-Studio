package com.molina.cinema

import com.molina.core.common.ExitHooks

/**
 * Penjaga status libmpv. MPVLib.destroy() tanpa create() (atau dua kali) bisa crash di native,
 * jadi semua pemanggil memakai destroyIfAlive() dan menandai create() dengan markCreated().
 *
 * Objek ini sengaja tidak menyentuh MPVLib sebelum mpv benar-benar hidup, supaya hook keluar
 * tidak memuat pustaka native pada aplikasi yang tidak pernah memutar media.
 */
object MpvLifecycle {
    private const val TAG = "mpv"
    private const val EXIT_TIMEOUT_MS = 1500L

    private val lock = Any()
    private var alive = false

    /** Panggil tepat setelah MPVLib.create(). */
    fun markCreated() {
        synchronized(lock) { alive = true }
        MpvLog.i(TAG, "libmpv dibuat")
    }

    /** Jalankan MPVLib.destroy() sekali saja. Mengembalikan false bila mpv tidak hidup. */
    fun destroyIfAlive(): Boolean {
        synchronized(lock) {
            if (!alive) return false
            alive = false
            MPVLib.destroy()
        }
        MpvLog.i(TAG, "libmpv dihentikan")
        return true
    }

    /**
     * Daftarkan pembersihan mpv pada AppExit. Dijalankan di thread terpisah dengan batas
     * waktu agar destroy yang macet tidak menahan keluar aplikasi. Aman dipanggil berulang.
     */
    fun installExitHook() {
        ExitHooks.register("mpv") {
            val t = Thread({
                try { destroyIfAlive() } catch (e: Throwable) {}
            }, "molina-mpv-exit")
            t.isDaemon = true
            t.start()
            t.join(EXIT_TIMEOUT_MS)
        }
    }
}
