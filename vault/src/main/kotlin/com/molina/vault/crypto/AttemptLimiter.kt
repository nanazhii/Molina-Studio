package com.molina.vault.crypto

import android.content.Context

/** Backoff eksponensial setelah 5 gagal. Hanya penghambat; perlindungan utama tetap KDF. */
class AttemptLimiter(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("bunker_attempts", Context.MODE_PRIVATE)

    fun remainingLockMs(now: Long = System.currentTimeMillis()): Long =
        (sp.getLong("until", 0L) - now).coerceAtLeast(0L)

    fun recordFailure(now: Long = System.currentTimeMillis()) {
        val n = sp.getInt("fails", 0) + 1
        val delay = if (n < 5) 0L else minOf(30_000L shl minOf(n - 5, 7), 3_600_000L)
        sp.edit().putInt("fails", n).putLong("until", now + delay).commit()
    }

    fun recordSuccess() {
        sp.edit().clear().commit()
    }
}
