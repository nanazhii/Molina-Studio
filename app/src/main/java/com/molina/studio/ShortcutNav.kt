package com.molina.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** Permintaan pindah tab dari jalan pintas ikon (urutan Tab); -1 = tidak ada. Dikonsumsi MainActivity. */
internal object ShortcutNav {
    var tab by mutableIntStateOf(-1)
}
