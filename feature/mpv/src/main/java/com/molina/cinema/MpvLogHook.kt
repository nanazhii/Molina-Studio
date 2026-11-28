package com.molina.cinema

/**
 * [MPV-LOG-FILE] Titik sambung publik: :app memasang sink berkas di sini. Sengaja BUKAN MPVLib, karena
 * MPVLib memuat libmpv/libplayer di init-nya dan tidak boleh tersentuh saat Application.onCreate.
 * Sink dipanggil dari thread event mpv; pemanggilnya (PlaybackLog) menelan exception.
 */
object MpvLogHook {
    @Volatile var sink: ((prefix: String, level: Int, text: String) -> Unit)? = null

    /** [MPV-LOG-SHARE] true bila mpv.log bisa dibagikan (penyimpanan log mpv menyala). Dipasang :app. */
    @Volatile var canShare: () -> Boolean = { false }

    /** [MPV-LOG-SHARE] Bagikan mpv.log lewat chooser sistem; parameter kedua = catatan teks (ID sesi, penyebab). Dipasang :app. */
    @Volatile var share: ((android.app.Activity, String) -> Unit)? = null
}
