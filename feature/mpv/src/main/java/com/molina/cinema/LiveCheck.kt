package com.molina.cinema

/** [MOLINA-LIVE] Penentu "siaran langsung" untuk baris aktif di daftar putar. */
object LiveCheck {
    private const val TAG = "mpv"

    fun isLive(): Boolean {
        val duration = runCatching { MPVLib.getPropertyDouble("duration/full") }.getOrNull() ?: 0.0
        val seekable = runCatching { MPVLib.getPropertyBoolean("seekable") }.getOrNull() ?: true
        val live = duration <= 0.0 || !seekable
        MpvLog.v(TAG, "live-check duration=$duration seekable=$seekable -> live=$live")
        return live
    }
}
