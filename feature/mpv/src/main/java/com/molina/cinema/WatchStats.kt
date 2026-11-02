package com.molina.cinema

import android.content.Context
import android.os.SystemClock
import com.molina.core.common.LogRedact
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors

/**
 * Statistik tontonan Molina Cinema, 100% lokal (tanpa jaringan, tanpa telemetri): waktu putar AKTIF
 * (tidak termasuk saat dijeda) per hari dan per judul. Disimpan di filesDir/watch-stats.json (tulis atomik
 * di thread latar). Judul URL disamarkan lewat LogRedact (hanya skema+host). Dapat dimatikan dan dihapus
 * dari Settings > Statistik tontonan.
 *
 * Catatan akurasi: dihitung dengan jam dinding, jadi buffering jaringan ikut terhitung dan kecepatan putar
 * tidak memengaruhi angka. Segmen yang belum ditutup hilang bila proses dibunuh paksa.
 */
object WatchStats {
    const val MAX_DAYS = 120
    private const val MAX_TITLES = 200
    private const val MIN_FILE_SEC = 5L
    private const val FILE = "watch-stats.json"
    private const val PREFS = "molina_stats"
    private const val KEY_ON = "enabled"

    class TopItem(val title: String, val seconds: Long, val plays: Int)

    /** [days] = 7 hari terakhir (lama ke baru) sebagai pasangan (epochDay, detik). */
    class Summary(
        val totalSec: Long, val sessions: Int, val todaySec: Long, val weekSec: Long,
        val days: List<Pair<Long, Long>>, val top: List<TopItem>
    )

    private class Agg(var title: String, var sec: Long, var plays: Int, var last: Long)

    private val days = HashMap<Long, Long>()
    private val titles = HashMap<String, Agg>()
    private var totalSec = 0L
    private var sessions = 0
    private var file: File? = null
    private var prefsCtx: Context? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "molina-watch-stats").apply { isDaemon = true } }

    // Keadaan sesi putar saat ini
    private var loaded = false
    private var tracking = false
    private var paused = false
    private var since = 0L
    private var fileMs = 0L
    private var curTitle = ""

    /** Idempoten; panggil sebelum membaca/menulis. */
    @Synchronized
    fun attach(ctx: Context) {
        if (file != null) return
        val app = ctx.applicationContext
        prefsCtx = app
        file = File(app.filesDir, FILE).also { load(it) }
    }

    fun enabled(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
    }

    // ---- hook pemutar (thread event mpv) ----

    @Synchronized
    fun onStart() = finishFile()

    @Synchronized
    fun onLoaded() {
        finishFile()
        val ctx = prefsCtx ?: return
        tracking = enabled(ctx)
        loaded = true
        curTitle = readTitle()
        fileMs = 0L
        since = if (tracking && !paused) SystemClock.elapsedRealtime() else 0L
    }

    @Synchronized
    fun onPause(p: Boolean) {
        if (p == paused) return
        if (loaded && tracking) {
            if (p) closeSegment() else since = SystemClock.elapsedRealtime()
        }
        paused = p
    }

    @Synchronized
    fun onEnd() = finishFile()

    /** Panggil di onDestroy Activity agar segmen terakhir tersimpan. */
    @Synchronized
    fun flush() = finishFile()

    // ---- baca/hapus (UI) ----

    @Synchronized
    fun snapshot(): Summary {
        val today = LocalDate.now().toEpochDay()
        val last7 = (6 downTo 0).map { i -> (today - i) to (days[today - i] ?: 0L) }
        val top = titles.values.sortedByDescending { it.sec }.take(10).map { TopItem(it.title, it.sec, it.plays) }
        return Summary(totalSec, sessions, days[today] ?: 0L, last7.sumOf { it.second }, last7, top)
    }

    @Synchronized
    fun clear() {
        days.clear(); titles.clear(); totalSec = 0L; sessions = 0
        persist()
    }

    // ---- internal ----

    private fun closeSegment() {
        if (since > 0L) {
            fileMs += SystemClock.elapsedRealtime() - since
            since = 0L
        }
    }

    private fun finishFile() {
        if (!loaded) return
        closeSegment()
        val sec = fileMs / 1000
        if (tracking && sec >= MIN_FILE_SEC) commit(sec)
        loaded = false
        fileMs = 0L
        since = 0L
    }

    private fun commit(sec: Long) {
        val today = LocalDate.now().toEpochDay()
        days[today] = (days[today] ?: 0L) + sec
        days.keys.removeAll { it < today - MAX_DAYS }
        val key = curTitle.lowercase()
        val a = titles.getOrPut(key) { Agg(curTitle, 0L, 0, 0L) }
        a.sec += sec; a.plays++; a.last = System.currentTimeMillis()
        while (titles.size > MAX_TITLES) {
            val victim = titles.entries.minByOrNull { it.value.sec } ?: break
            titles.remove(victim.key)
        }
        totalSec += sec
        sessions++
        persist()
    }

    private fun readTitle(): String {
        val raw = runCatching { MPVLib.getPropertyString("media-title") }.getOrNull()?.trim().orEmpty()
        val t = when {
            raw.isEmpty() -> runCatching { MPVLib.getPropertyString("path") }.getOrNull()?.let { LogRedact.target(it) }.orEmpty()
            raw.contains("://") -> LogRedact.target(raw)
            else -> raw
        }
        return t.take(80).ifEmpty { "(tanpa judul)" }
    }

    private fun persist() {
        val f = file ?: return
        val json = JSONObject()
            .put("v", 1).put("total", totalSec).put("sessions", sessions)
            .put("days", JSONObject().also { o -> days.forEach { (d, s) -> o.put(d.toString(), s) } })
            .put("titles", JSONArray().also { arr ->
                titles.values.forEach { arr.put(JSONObject().put("t", it.title).put("s", it.sec).put("p", it.plays).put("l", it.last)) }
            }).toString()
        try {
            io.execute {
                try {
                    val tmp = File(f.parentFile, f.name + ".tmp")
                    tmp.writeText(json, Charsets.UTF_8)
                    if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                } catch (_: Exception) {
                    // gagal menulis: state di memori tetap benar, penulisan berikutnya menimpa
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // proses berhenti
        }
    }

    private fun load(f: File) {
        if (!f.isFile) return
        try {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            totalSec = o.optLong("total"); sessions = o.optInt("sessions")
            o.optJSONObject("days")?.let { d ->
                d.keys().forEach { k -> k.toLongOrNull()?.let { days[it] = d.optLong(k) } }
            }
            o.optJSONArray("titles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    val title = t.optString("t").take(80)
                    if (title.isEmpty()) continue
                    titles[title.lowercase()] = Agg(title, t.optLong("s"), t.optInt("p"), t.optLong("l"))
                }
            }
        } catch (e: Exception) {
            f.renameTo(File(f.parentFile, f.name + ".bad"))
            days.clear(); titles.clear(); totalSec = 0L; sessions = 0
        }
    }
}
