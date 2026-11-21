package com.molina.core.api

/**
 * Kontrak peluncur media untuk fitur lain (terminal, ...): putar tautan di Molina Cinema tanpa mengimpor
 * paket cinema. Hanya tipe JVM murni (tanpa Context/Uri). Implementasi hidup di :app (paket cinema) dan
 * didaftarkan lewat [MolinaServices] saat aplikasi mulai.
 */
interface MediaGateway {
    /** Putar [url] (http, https, rtsp, rtmp) di pemutar. False bila tautan tidak didukung atau pemutar gagal dibuka. */
    fun playUrl(url: String): Boolean
}
