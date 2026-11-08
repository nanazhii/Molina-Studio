package com.molina.cinema

import android.os.SystemClock
import com.molina.core.common.LogRedact
import java.util.Locale

/**
 * Riwayat pemutaran untuk tab Log (sumber "mpv", tag "playback").
 * Semua target disamarkan lewat LogRedact: URL hanya skema+host, path lokal
 * hanya nama berkas. Aman dipanggil dari thread event mpv.
 *
 * Batasan: JNI hanya meneruskan id event (tanpa alasan end-file). Karena itu
 * "berakhir sebelum dimuat" dicatat sebagai kemungkinan gagal buka, bukan
 * kepastian galat.
 */
internal object PlaybackLog {
    private const val TAG = "playback"

    @Volatile private var target = ""
    @Volatile private var startedAt = 0L
    @Volatile private var loaded = false
    @Volatile private var lastError = ""
    @Volatile private var errorCount = 0
    private var attached = false

    private const val MAX_ERRORS_PER_FILE = 20

    /**
     * Menangkap log mpv level ERROR/FATAL (misalnya gagal membuka sumber) untuk
     * melengkapi batasan JNI yang tidak meneruskan alasan end-file. Semua teks
     * disamarkan lewat LogRedact sebelum dicatat.
     */
    private val errorObserver = object : MPVLib.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            if (level <= 0 || level > MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR) return
            val msg = text.trim()
            if (msg.isEmpty()) return
            val line = "[$prefix] ${LogRedact.text(msg)}"
            if (line == lastError) return
            lastError = line
            if (++errorCount > MAX_ERRORS_PER_FILE) return
            MpvLog.e(TAG, "Galat mpv $line")
        }
    }

    /** Pasang sebelum player.initialize(). Aman dipanggil berulang. */
    @Synchronized
    fun attach() {
        if (attached) return
        MPVLib.addLogObserver(errorObserver)
        attached = true
    }

    /** Panggil di onDestroy. Aman dipanggil berulang. */
    @Synchronized
    fun detach() {
        if (!attached) return
        MPVLib.removeLogObserver(errorObserver)
        attached = false
    }

    /** Permintaan putar baru dari intent (onNewIntent). */
    fun onRequest(raw: String) {
        MpvLog.i(TAG, "Permintaan putar: ${LogRedact.target(raw)}")
    }

    /** MPV_EVENT_START_FILE. */
    fun onStart() {
        target = prop("path")?.let { LogRedact.target(it) } ?: "(tidak diketahui)"
        startedAt = SystemClock.elapsedRealtime()
        loaded = false
        lastError = ""
        errorCount = 0
        MpvLog.i(TAG, "Mulai: $target")
    }

    /** MPV_EVENT_FILE_LOADED. */
    fun onLoaded() {
        loaded = true
        val title = prop("media-title")?.let { LogRedact.text(it) }.orEmpty()
        val duration = runCatching { MPVLib.getPropertyDouble("duration") }.getOrNull()
        val sb = StringBuilder("Dimuat: ").append(target)
        if (duration != null && duration > 0)
            sb.append(" | durasi ").append(clock((duration * 1000).toLong()))
        if (title.isNotEmpty() && title != target)
            sb.append(" | judul ").append(title)
        MpvLog.i(TAG, sb.toString())
    }

    /** Playlist pencarian diterima: [total] item, mulai dari nomor [start] (1-based). */
    fun onPlaylist(total: Int, start: Int) {
        MpvLog.i(TAG, "Playlist pencarian: $total item, mulai dari #$start")
    }

    /** Item nomor [number] (1-based) berhasil disiapkan dan ditambahkan ke antrean mpv. */
    fun onPrefetched(number: Int, title: String) {
        val t = LogRedact.text(title)
        MpvLog.i(TAG, "Antrean +#$number" + (if (t.isNotEmpty()) " | judul $t" else ""))
    }

    /** Item nomor [number] gagal disiapkan dan dilewati; [reason] nama StreamError. */
    fun onPrefetchSkipped(number: Int, reason: String) {
        MpvLog.w(TAG, "Antrean lewati #$number | penyebab $reason")
    }

    /** MPV_EVENT_END_FILE. Panggil SEBELUM psc.eof(). */
    fun onEnd(positionMs: Long, durationMs: Long) {
        // Tanpa START_FILE yang cocok (END_FILE ganda/sisa), jangan mencatat apa pun.
        if (startedAt == 0L) return
        val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000
        val wasLoaded = loaded
        val shownTarget = target
        val shownError = lastError
        // Reset dulu agar END_FILE berikutnya tidak tercatat dua kali.
        startedAt = 0L
        loaded = false
        if (!wasLoaded) {
            MpvLog.w(
                TAG,
                "Berakhir sebelum dimuat (kemungkinan gagal dibuka): " +
                    "$shownTarget setelah ${elapsed}s" +
                    (if (shownError.isNotEmpty()) " | penyebab $shownError" else "")
            )
            return
        }
        MpvLog.i(
            TAG,
            "Selesai: $shownTarget | posisi ${clock(positionMs)} dari ${clock(durationMs)}"
        )
    }

    private fun prop(name: String): String? =
        runCatching { MPVLib.getPropertyString(name) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }

    private fun clock(ms: Long): String {
        if (ms < 0) return "?"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s)
        else "%d:%02d".format(Locale.ROOT, m, s)
    }
}
