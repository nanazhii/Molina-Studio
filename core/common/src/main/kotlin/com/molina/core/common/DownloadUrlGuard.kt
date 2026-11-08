package com.molina.core.common

/**
 * [DL-SSRF-GUARD] Unduhan (yt-dlp di Debian) tidak lewat Molina Shield, jadi URL yang menunjuk alamat lokal perangkat
 * atau jaringan rumah (SSRF lewat tautan jahat) harus ditolak sebelum sesi dimulai. Hanya alasan LOOPBACK dan
 * PRIVATE_ADDRESS dari [UrlPolicy] yang dipakai: userinfo dan host non-ASCII tetap boleh agar unduhan sah tidak patah.
 * Batas: redirect dari host publik ke alamat privat dan DNS rebinding tidak terdeteksi di sini. Murni, tanpa Android.
 */
object DownloadUrlGuard {
    private val BLOCKED = setOf(UrlPolicy.Reason.LOOPBACK, UrlPolicy.Reason.PRIVATE_ADDRESS)

    /** Pesan galat siap tampil (tanpa memuat URL) bila ada URL yang ditolak; null bila semua aman. */
    fun check(urls: List<String>, allowLan: Boolean = NetworkPolicy.allowLan): String? {
        val list = urls.map { it.trim() }.filter { it.isNotEmpty() }
        for ((i, u) in list.withIndex()) {
            val v = UrlPolicy.check(u, allowLan)
            if (v is UrlPolicy.Verdict.Blocked && v.reason in BLOCKED) {
                return "URL ke-${i + 1} ditolak: ${v.reason.message}"
            }
        }
        return null
    }
}
