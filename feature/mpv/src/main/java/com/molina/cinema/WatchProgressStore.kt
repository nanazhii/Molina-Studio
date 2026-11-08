package com.molina.cinema

import android.content.Context
import com.molina.core.common.VideoKey
import com.molina.core.common.WatchProgress

/**
 * [WATCH-PROGRESS] Penyimpanan lokal persentase tontonan video YouTube (kunci = VideoKey dari tautan asal).
 * SharedPreferences molina_ui, kunci watch_progress. Best-effort: kegagalan tidak boleh mengganggu pemutaran.
 */
object WatchProgressStore {
    private const val PREFS = "molina_ui"
    private const val KEY = "watch_progress"
    private val lock = Any()

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(ctx: Context, pageUrl: String?, positionMs: Long, durationMs: Long) {
        val key = pageUrl?.let { VideoKey.of(it) } ?: return
        val pct = WatchProgress.percent(positionMs, durationMs) ?: return
        try {
            val p = sp(ctx)
            synchronized(lock) {
                val cur = WatchProgress.decode(p.getString(KEY, null))
                p.edit().putString(KEY, WatchProgress.encode(WatchProgress.push(cur, key, pct))).apply()
            }
        } catch (e: Exception) {
            // best-effort
        }
    }

    /** Peta kunci video -> persen (1..100). */
    fun snapshot(ctx: Context): Map<String, Int> = try {
        synchronized(lock) { WatchProgress.decode(sp(ctx).getString(KEY, null)).toMap() }
    } catch (e: Exception) {
        emptyMap()
    }
}
