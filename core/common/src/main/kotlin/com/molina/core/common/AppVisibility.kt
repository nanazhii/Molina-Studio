package com.molina.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * true selama Activity terlihat; pekerjaan periodik (statistik, dll.) berhenti saat false.
 * Modul hanya membaca [foreground]; yang menulis (setForeground) hanya :app.
 */
object AppVisibility {
    private val _foreground = MutableStateFlow(true)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    fun setForeground(visible: Boolean) {
        _foreground.value = visible
    }
}
