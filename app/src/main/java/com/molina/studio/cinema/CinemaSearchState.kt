package com.molina.studio.cinema

import androidx.compose.runtime.mutableStateOf
import com.molina.core.stream.DateFilter
import com.molina.core.stream.DurationFilter
import com.molina.core.stream.StreamSearchItem

/**
 * Penyimpan state pencarian Cinema di level proses.
 * MainActivity hanya me-render tab yang dipilih, jadi CinemaHome keluar dari composition
 * saat pindah tab dan `remember` biasa ikut hilang. State di sini bertahan selama proses hidup.
 */
internal object CinemaSearchState {
    val input = mutableStateOf("")
    val results = mutableStateOf<List<StreamSearchItem>>(emptyList())
    val lastQuery = mutableStateOf("")
    val showResults = mutableStateOf(false)

    // [RESULTS-MINIMIZE] Hasil dikecilkan: daftar tetap ada, chip kecil di Cinema membukanya lagi tanpa mencari ulang.
    val minimized = mutableStateOf(false)
    var selected = 1
    var scroll = 0
    var chosen: Set<String> = emptySet()
    var dur: DurationFilter = DurationFilter.ALL
    var date: DateFilter = DateFilter.ALL
    var related: List<StreamSearchItem>? = null

    /** Pencarian baru: posisi, pilihan, saringan, dan kanal terkait dari daftar lama tidak berlaku. */
    fun resetView() {
        selected = 1; scroll = 0; chosen = emptySet()
        dur = DurationFilter.ALL; date = DateFilter.ALL; related = null
    }
}
