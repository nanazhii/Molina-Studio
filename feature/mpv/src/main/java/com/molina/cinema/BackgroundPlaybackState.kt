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

    fun setActive(value: Boolean) {
        _active.value = value
    }
}
