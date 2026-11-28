package com.molina.studio.download

import androidx.compose.runtime.mutableStateOf
import com.molina.core.download.DownloadChoices

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
    val lyrics = mutableStateOf(false)

    /** [WIFI-ONLY] Hanya mulai unduhan di jaringan tidak berkuota. Disimpan lewat DownloadPrefs.setWifiOnly. */
    val wifiOnly = mutableStateOf(false)

    fun apply(c: DownloadChoices) {
        video.value = c.video; height.value = c.height; videoExt.value = c.videoExt
        audioFmt.value = c.audioFmt; bitrate.value = c.bitrate
        subs.value = c.subs; subsMode.value = c.subsMode; cover.value = c.cover; limitRate.value = c.limitRate
        sidecar.value = c.sidecar
        perChannel.value = c.perChannel
        lyrics.value = c.lyrics
    }

    fun choices() = DownloadChoices(
        video.value, height.value, videoExt.value, audioFmt.value, bitrate.value,
        subs.value, subsMode.value, cover.value, limitRate.value, sidecar.value, perChannel.value, lyrics.value,
    )
}
