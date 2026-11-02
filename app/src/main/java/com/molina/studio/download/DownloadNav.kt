package com.molina.studio.download

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.molina.core.common.DownloadChoices

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

/**
 * Isi formulir Unduhan. Disimpan di sini (bukan rememberSaveable) karena tab hanya dikomposisi saat dipilih:
 * pindah ke tab lain tidak boleh menghapus URL dan pilihan yang sudah diisi.
 */
internal object DownloadForm {
    val urls = mutableStateOf("")
    val video = mutableStateOf(true)
    val height = mutableStateOf(0)
    val videoExt = mutableStateOf("mp4")
    val audioFmt = mutableStateOf("mp3")
    val bitrate = mutableStateOf("192k")
    val subs = mutableStateOf<List<String>>(emptyList())
    val subsMode = mutableStateOf("embed")
    val cover = mutableStateOf(true)
    val limitRate = mutableStateOf("")
    val sidecar = mutableStateOf(false)
    val perChannel = mutableStateOf(false)

    /** [WIFI-ONLY] Hanya mulai unduhan di jaringan tidak berkuota. Disimpan lewat DownloadPrefs.setWifiOnly. */
    val wifiOnly = mutableStateOf(false)

    fun apply(c: DownloadChoices) {
        video.value = c.video; height.value = c.height; videoExt.value = c.videoExt
        audioFmt.value = c.audioFmt; bitrate.value = c.bitrate
        subs.value = c.subs; subsMode.value = c.subsMode; cover.value = c.cover; limitRate.value = c.limitRate
        sidecar.value = c.sidecar
        perChannel.value = c.perChannel
    }

    fun choices() = DownloadChoices(
        video.value, height.value, videoExt.value, audioFmt.value, bitrate.value,
        subs.value, subsMode.value, cover.value, limitRate.value, sidecar.value, perChannel.value,
    )
}
