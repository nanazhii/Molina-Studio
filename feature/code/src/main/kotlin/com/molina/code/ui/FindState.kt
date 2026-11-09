package com.molina.code.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Status find bar, dipakai bersama semua tab (satu find bar untuk editor aktif).
 * Default opsi mengikuti desain lama (blueprint bagian 15.2): sensitif huruf besar/kecil ON, blok kata utuh ON,
 * regex OFF, membungkus di sekitar ON, ganti teks OFF.
 */
class FindState {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    var replaceText by mutableStateOf("")
    var replaceMode by mutableStateOf(false)
    var matchCase by mutableStateOf(true)
    var wholeWord by mutableStateOf(true)
    var useRegex by mutableStateOf(false)
    var wrapAround by mutableStateOf(true)
    var regexError by mutableStateOf(false)

    /** Naik setiap find bar diminta fokus (ketuk Cari saat sudah terbuka). */
    var focusTick by mutableIntStateOf(0)
}
