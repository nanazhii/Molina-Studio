package com.molina.studio.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Jembatan ke tab Unduhan dari layar lain (mis. kartu hasil pencarian Cinema). [request] menaruh URL di
 * [pendingUrls]; MainActivity pindah ke tab Unduhan dan UnduhanScreen memindahkannya ke kolom URL lalu
 * mengosongkannya. Tidak memulai unduhan: pengguna masih memilih mode/kualitas lalu menekan Mulai.
 */
internal object DownloadNav {
    var pendingUrls by mutableStateOf<List<String>>(emptyList())

    /** Diset true (mis. ketuk notifikasi Unduhan); MainActivity pindah ke tab Unduhan lalu mengembalikannya ke false. */
    var openTab by mutableStateOf(false)

    /** Ada unduhan yang selesai saat tab Unduhan tidak dibuka: menyalakan lencana hijau sampai tab dibuka. */
    var unseen by mutableStateOf(false)

    /** True selama halaman Unduhan di Settings tampil (dipakai MainActivity untuk lencana). */
    var visible by mutableStateOf(false)

    fun request(urls: List<String>) {
        pendingUrls = (pendingUrls + urls).distinct()
    }
}
