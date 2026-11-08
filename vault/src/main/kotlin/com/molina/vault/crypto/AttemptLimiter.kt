package com.molina.vault.crypto

// MOLINA-H1
import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/** Pembungkus Android untuk [AttemptLogic]: SharedPreferences + elapsedRealtime + BOOT_COUNT. */
class AttemptLimiter(context: Context) {
    private val logic = AttemptLogic(
        PrefsAttemptStore(context.applicationContext),
        { SystemClock.elapsedRealtime() },
        { bootCount(context.applicationContext) },
    )

    fun remainingLockMs(): Long = logic.remainingLockMs()
    fun recordFailure() = logic.recordFailure()
    fun recordSuccess() = logic.recordSuccess()

    private fun bootCount(c: Context): Long = try {
        Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1).toLong()
    } catch (e: Exception) {
        AttemptLogic.UNKNOWN_BOOT
    }
}

private class PrefsAttemptStore(context: Context) : AttemptStore {
    private val sp = context.getSharedPreferences("bunker_attempts", Context.MODE_PRIVATE)

    override fun long(key: String, def: Long): Long = sp.getLong(key, def)
    override fun int(key: String, def: Int): Int = sp.getInt(key, def)

    override fun commit(longs: Map<String, Long>, ints: Map<String, Int>) {
        val e = sp.edit()
        for ((k, v) in longs) e.putLong(k, v)
        for ((k, v) in ints) e.putInt(k, v)
        e.commit()
    }

    override fun clear() {
        sp.edit().clear().commit()
    }
}
