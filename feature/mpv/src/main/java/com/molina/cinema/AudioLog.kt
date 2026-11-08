package com.molina.cinema

import android.media.AudioManager

/** Catatan perubahan audio focus dan pencabutan output audio, dengan nama yang terbaca. */
internal object AudioLog {
    private const val TAG = "mpv"

    /**
     * @param type  konstanta AudioManager.AUDIOFOCUS_*
     * @param source "callback" (sistem), "noisy" (output audio dicabut), atau "request" (permintaan pemutar)
     * @param ignored true bila setelan "abaikan audio focus" aktif atau Activity sedang ditutup
     */
    fun focus(type: Int, source: String, ignored: Boolean) {
        // Permintaan focus ditolak: pemutar menjeda dirinya sendiri, ini patut jadi peringatan.
        if (type == AudioManager.AUDIOFOCUS_LOSS && source == "request") {
            MpvLog.w(TAG, "Audio focus ditolak sistem" + if (ignored) " (diabaikan)" else "; pemutaran dijeda")
            return
        }
        val name = when (type) {
            AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
            AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_CAN_DUCK"
            else -> "type=$type"
        }
        val from = when (source) {
            "noisy" -> "output audio dicabut"
            "callback" -> "sistem"
            else -> "permintaan pemutar"
        }
        val msg = "Audio focus $name dari $from" + if (ignored) " (diabaikan)" else ""
        // GAIN dari permintaan sendiri terjadi setiap pemutaran dilanjutkan: cukup Debug.
        if (type == AudioManager.AUDIOFOCUS_GAIN && source == "request")
            MpvLog.d(TAG, msg)
        else
            MpvLog.i(TAG, msg)
    }
}
