package com.molina.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** Permintaan pindah tab dari jalan pintas ikon (urutan Tab); -1 = tidak ada. Dikonsumsi MainActivity. */
internal object ShortcutNav {
    // Urutan enum Tab di MainActivity: Mpv=0, Files=1, Terminal=2, Settings=3. Editor bukan tab (lihat CodeNav.openEditor).
    var tab by mutableIntStateOf(-1)
}
