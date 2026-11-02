package com.molina.studio.cinema

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.molina.core.common.SeenMarks
import com.molina.core.common.VideoKey

/**
 * [SEEN-BADGES] Penanda video yang sudah diunduh atau pernah diputar (maks. [SeenMarks.MAX] per jenis, terbaru di atas),
 * dikunci menurut ID video YouTube. SharedPreferences molina_ui. Dipakai lencana di daftar hasil pencarian.
 * "Pernah diputar" = dipilih untuk diputar dari hasil pencarian atau tautan, bukan jaminan ditonton sampai habis.
 */
internal object SeenStore {
    private const val PREFS = "molina_ui"

    private class Bucket(val pref: String) { var list: List<String>? = null }

    private val playedB = Bucket("seen_played")
    private val downloadedB = Bucket("seen_downloaded")
    private val lock = Any()

    /** Naik setiap penanda berubah; dibaca Compose agar lencana ikut tersegarkan. */
    var version by mutableIntStateOf(0)
        private set

    class Snapshot(private val played: Set<String>, private val downloaded: Set<String>) {
        fun isPlayed(url: String): Boolean = VideoKey.of(url)?.let { it in played } == true
        fun isDownloaded(url: String): Boolean = VideoKey.of(url)?.let { it in downloaded } == true
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun items(ctx: Context, b: Bucket): List<String> =
        b.list ?: SeenMarks.decode(prefs(ctx).getString(b.pref, null)).also { b.list = it }

    private fun mark(ctx: Context, b: Bucket, urls: List<String>) {
        val keys = urls.mapNotNull { VideoKey.of(it) }
        if (keys.isEmpty()) return
        try {
            synchronized(lock) {
                var cur = items(ctx, b)
                for (k in keys.asReversed()) cur = SeenMarks.push(cur, k)
                b.list = cur
                prefs(ctx).edit().putString(b.pref, SeenMarks.encode(cur)).apply()
                version++
            }
        } catch (e: Exception) {
            // penanda bersifat best-effort: tidak boleh mengganggu pemutaran atau unduhan
        }
    }

    fun markPlayed(ctx: Context, url: String) = mark(ctx, playedB, listOf(url))

    fun markDownloaded(ctx: Context, urls: List<String>) = mark(ctx, downloadedB, urls)

    fun snapshot(ctx: Context): Snapshot = try {
        synchronized(lock) { Snapshot(items(ctx, playedB).toHashSet(), items(ctx, downloadedB).toHashSet()) }
    } catch (e: Exception) {
        Snapshot(emptySet(), emptySet())
    }
}
