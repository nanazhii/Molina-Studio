package com.molina.cinema

import android.util.Log
import com.molina.core.common.AppLog
import com.molina.core.common.LogLevel
import com.molina.core.common.LogRedact

/**
 * Pengganti android.util.Log untuk modul mpv. Pesan disamarkan (URL, token)
 * lalu dikirim ke logcat dan ke AppLog (LogGrouping) dengan sumber "mpv".
 */
internal object MpvLog {
    private const val SOURCE = "mpv"

    /** Tag logcat seragam; tag asal (mis. "playback") jadi awalan pesan. AppLog tetap memakai tag asal. */
    const val LOGCAT_TAG = "Molina/Mpv"

    private fun tagged(tag: String, msg: String) =
        if (tag.isEmpty() || tag == SOURCE) msg else "[$tag] $msg"

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
        AppLog.log(SOURCE, level, tag, summary)
    }
}
