package com.molina.core.common

/**
 * Teks galat unduhan untuk pengguna: ramah, bahasa Indonesia, tanpa istilah teknis (SSL, stderr, kode keluar).
 * Pesan mentah yt-dlp/skrip TIDAK ditampilkan; disimpan di errorRaw/fatalRaw dan hanya dikirim ke Log (bagian teknis).
 * Murni JVM tanpa dependensi lain agar bisa diuji dengan kotlinc.
 */
object DownloadErrorText {
    const val SSL = "Koneksi aman ke server gagal dibuat. Molina sudah mencoba memperbaikinya otomatis; coba unduh lagi. " +
        "Bila tetap gagal, jalankan Periksa lingkungan di halaman ini."
    const val NETWORK = "Koneksi internet bermasalah atau terlalu lambat. Periksa jaringan lalu coba lagi."
    const val RATE_LIMIT = "Server membatasi permintaan untuk sementara. Tunggu beberapa menit lalu coba lagi."
    const val RESTRICTED = "Video ini tidak bisa diunduh (dibatasi di wilayah Anda)."
    const val PRIVATE = "Video ini privat atau memerlukan login."
    const val AGE = "Video ini dibatasi usia sehingga tidak bisa diunduh."
    const val NOT_FOUND = "Video tidak ditemukan atau sudah dihapus."
    const val UNSUPPORTED = "Situs atau tautan ini belum didukung."
    const val NO_SPACE = "Penyimpanan penuh. Kosongkan ruang lalu coba lagi."
    const val NO_ENGINE = "yt-dlp belum terpasang. Buka Pengaturan > Mesin cadangan (yt-dlp) > Pasang."
    const val ENGINE_FAIL = "Mesin unduhan tidak bisa dijalankan. Pasang ulang lewat Pengaturan > Mesin cadangan (yt-dlp)."
    const val OUTDATED = "Mesin unduhan perlu diperbarui. Tekan Perbarui yt-dlp di halaman ini."
    const val STOPPED = "Unduhan terhenti di tengah jalan. Coba lagi; bila berulang, jalankan Periksa lingkungan di halaman ini."
    const val BLOCKED = "Tautan ini mengarah ke alamat jaringan lokal sehingga diblokir demi keamanan. " +
        "Aktifkan akses jaringan lokal di Keamanan dan privasi bila memang disengaja."
    const val GENERIC = "Unduhan gagal. Coba lagi beberapa saat lagi."

    /** [code] dari event `error` skrip, [raw] pesan mentahnya. Hasil selalu aman ditampilkan ke pengguna. */
    fun friendly(code: String?, raw: String?): String = when (code) {
        "ssl_cert" -> SSL
        "blocked_local" -> BLOCKED
        "timeout", "network" -> NETWORK
        "rate_limited" -> RATE_LIMIT
        "restricted" -> RESTRICTED
        "no_space" -> NO_SPACE
        "no_ytdlp" -> NO_ENGINE
        "spawn_failed" -> ENGINE_FAIL
        "cancelled" -> "Dibatalkan."
        "aborted" -> STOPPED
        // Pesan dari skrip sendiri sudah berbahasa Indonesia dan tanpa detail teknis.
        "playlist", "bad_url" -> raw?.takeIf { it.isNotBlank() } ?: GENERIC
        else -> byContent(raw)
    }

    private fun byContent(raw: String?): String {
        val e = raw.orEmpty().lowercase()
        fun has(vararg k: String) = k.any { it in e }
        return when {
            has("molina-netguard") -> BLOCKED
            has("certificate", "ssl:") -> SSL
            has("no space") -> NO_SPACE
            has("http error 429", "too many requests") -> RATE_LIMIT
            has("unsupported url") -> UNSUPPORTED
            has("confirm your age", "age-restricted", "age restricted", "inappropriate for some users") -> AGE
            has("private video", "this video is private", "sign in", "login required", "log in", "cookies") -> PRIVATE
            has("not available in your country", "geo restrict", "geo-restrict", "blocked it in your country",
                "not made this video available in your country") -> RESTRICTED
            has("video unavailable", "http error 404", "does not exist", "has been removed", "not found",
                "no video could be found", "isn't available", "is not available") -> NOT_FOUND
            has("unable to extract", "extractor error", "unable to find", "failed to parse", "update yt-dlp",
                "please report this issue") -> OUTDATED
            has("unable to download", "timed out", "temporary failure", "network is unreachable", "connection reset",
                "connection refused", "name or service not known", "http error 5", "http error 403") -> NETWORK
            else -> GENERIC
        }
    }
}
