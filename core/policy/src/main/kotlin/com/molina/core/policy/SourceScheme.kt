package com.molina.core.policy

/** Jenis sumber putar menurut skema URI. */
enum class SourceKind {
    /** file://: path lokal, tetap wajib lolos pemeriksaan data privat. */
    LOCAL_FILE,

    /** content://: lewat ContentResolver. */
    CONTENT,

    /** Protokol jaringan yang boleh dibuka mpv. Kebijakan URL (UrlPolicy) diperiksa terpisah. */
    NETWORK,

    /** Skema lain (data, tcp, udp, ftp, mms, rtp, kosong): tidak dipakai dan tidak boleh dibuka. */
    REJECTED,
}

/**
 * [E-3b-3a] Satu-satunya tempat yang menentukan skema URI mana yang boleh jadi sumber putar. Murni JVM.
 * Pencocokan sengaja persis (peka huruf besar-kecil) seperti perilaku Uri.getScheme() sebelumnya: "HTTP" ditolak.
 */
object SourceScheme {
    /** Protokol jaringan yang juga diizinkan UrlPolicy. */
    val NETWORK_SCHEMES: Set<String> = setOf("http", "https", "rtmp", "rtmps", "rtsp")

    private val SAFE_LABEL = Regex("^[a-z][a-z0-9+.\\-]{0,15}$")

    /**
     * [E-3b-3c] Skema yang aman ditulis ke log: huruf kecil, pendek, hanya karakter skema sah. Skema dari aplikasi lain
     * tidak dipercaya (bisa berisi apa saja), jadi yang tidak memenuhi bentuk itu jadi "lain"; kosong/null jadi "kosong".
     */
    fun safeLabel(scheme: String?): String {
        val s = scheme?.lowercase().orEmpty()
        return when {
            s.isEmpty() -> "kosong"
            SAFE_LABEL.matches(s) -> s
            else -> "lain"
        }
    }

    fun classify(scheme: String?): SourceKind = when (scheme) {
        "file" -> SourceKind.LOCAL_FILE
        "content" -> SourceKind.CONTENT
        in NETWORK_SCHEMES -> SourceKind.NETWORK
        else -> SourceKind.REJECTED
    }
}
