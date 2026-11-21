package com.molina.cinema

import android.util.Log
import com.molina.core.api.AppLog
import com.molina.core.api.LogLevel
import com.molina.core.diag.LogRedact
import com.molina.core.diag.PlaybackSessions

/**
 * Pengganti android.util.Log untuk modul mpv. Pesan disamarkan (URL, token)
 * lalu dikirim ke logcat dan ke AppLog (LogGrouping) dengan sumber "mpv".
 */
internal object MpvLog {
    private const val SOURCE = "mpv"

    /** Tag logcat seragam; tag asal (mis. "playback") jadi awalan pesan. AppLog tetap memakai tag asal. */
    const val LOGCAT_TAG = "Molina/Mpv"

    /** [DIAG-SESSION] Awalan "[tag sNNN]" memuat ID sesi pemutaran aktif; tanpa sesi bentuk lama dipertahankan. */
    private fun tagged(tag: String, msg: String): String {
        val sid = PlaybackSessions.current()?.label
        val head = when {
            tag.isEmpty() || tag == SOURCE -> sid
            sid == null -> tag
            else -> "$tag $sid"
        }
        return if (head == null) msg else "[$head] $msg"
    }

    fun v(tag: String, msg: String, tr: Throwable? = null) =
        emit(Log.VERBOSE, LogLevel.Debug, tag, msg, tr)

    fun d(tag: String, msg: String, tr: Throwable? = null) =
        emit(Log.DEBUG, LogLevel.Debug, tag, msg, tr)

    fun i(tag: String, msg: String, tr: Throwable? = null) =
        emit(Log.INFO, LogLevel.Info, tag, msg, tr)

    fun w(tag: String, msg: String, tr: Throwable? = null) =
        emit(Log.WARN, LogLevel.Warn, tag, msg, tr)

    fun w(tag: String, tr: Throwable) =
        emit(Log.WARN, LogLevel.Warn, tag, "", tr)

    fun e(tag: String, msg: String, tr: Throwable? = null) =
        emit(Log.ERROR, LogLevel.Error, tag, msg, tr)

    fun e(tag: String, tr: Throwable) =
        emit(Log.ERROR, LogLevel.Error, tag, "", tr)

    private fun emit(
        prio: Int,
        level: LogLevel,
        tag: String,
        msg: String,
        tr: Throwable?
    ) {
        val safe = LogRedact.text(msg)
        val trace = if (tr == null) "" else
            "\n" + LogRedact.text(Log.getStackTraceString(tr))
        Log.println(prio, LOGCAT_TAG, tagged(tag, safe) + trace)
        val summary = if (tr == null) safe else
            listOf(safe, LogRedact.text(tr.toString()))
                .filter { it.isNotEmpty() }
                .joinToString(": ")
        // [DIAG-SESSION] ringkasan: ID sesi ikut ke tab Log (pengelompokan baris identik tetap berlaku per sesi)
        AppLog.log(SOURCE, level, tag, PlaybackSessions.prefix() + summary)
    }
}
