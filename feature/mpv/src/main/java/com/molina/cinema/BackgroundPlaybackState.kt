package com.molina.cinema

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * true selama BackgroundPlaybackService berjalan (mpv aktif, baik di depan maupun latar belakang).
 * Dipakai Cinema Home (:app) untuk badge "LIVE". Hanya BackgroundPlaybackService yang menulis.
 */
object BackgroundPlaybackState {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** [AUDIO-TAG-NOTIF] true saat mode audio saja aktif; ditulis MPVActivity, dibaca BackgroundPlaybackService. */
    @Volatile var audioOnly = false

    private val _mode = MutableStateFlow<com.molina.core.common.VideoQuality?>(null)

    /** [QUALITY-NOW] Mode yang benar-benar diputar (AUDIO bila tanpa gambar video); null = tidak ada pemutaran. Ditulis MPVActivity. */
    val mode: StateFlow<com.molina.core.common.VideoQuality?> = _mode.asStateFlow()

    fun setMode(v: com.molina.core.common.VideoQuality?) {
        _mode.value = v
    }

    /** [QUALITY-NOW] Diisi MPVActivity: terapkan resolusi pada item yang sedang diputar (sama seperti memilih di sheet). */
    @Volatile var applyQuality: ((com.molina.core.common.VideoQuality) -> Unit)? = null

    fun setActive(value: Boolean) {
        _active.value = value
        if (!value) _mode.value = null
    }
}
