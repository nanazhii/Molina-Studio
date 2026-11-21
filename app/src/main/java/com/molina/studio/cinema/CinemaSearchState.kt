package com.molina.studio.cinema

import androidx.compose.runtime.mutableStateOf
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
}
