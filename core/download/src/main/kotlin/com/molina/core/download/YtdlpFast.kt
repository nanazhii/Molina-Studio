package com.molina.core.download

/**
 * [YTDL-FAST] yt-dlp ter-ekstrak. Zipapp dikompilasi ulang dari sumber di setiap proses (tidak bisa menyimpan .pyc), sekitar
 * 5-6 dtk per proses di PRoT; direktori ter-ekstrak dengan .pyc sekitar 3-4 dtk. Zipapp di /usr/local/bin tetap ada (Terminal,
 * molina_dl.py, pembaruan); hanya resolver aplikasi yang memakai direktori ini lewat `python3 <direktori>`. Murni JVM.
 */
object YtdlpFast {
    const val GUEST_DIR = "/usr/local/lib/molina-ytdlp"
    const val MARKER = ".molina-src"
    const val WARM_MARKER = ".molina-warm" // [YTDL-WARM] ada = .pyc sudah dihangatkan untuk zipapp bermarker sama
    const val WARM_TIMEOUT_MS = 240_000L // [YTDL-WARM]
    const val MAX_FILES = 6_000
    const val MAX_TOTAL_BYTES = 120L * 1024 * 1024

    /** Penanda asal: ukuran dan waktu ubah zipapp sumber. Berubah (pembaruan/pasang ulang) = direktori basi. */
    fun marker(size: Long, mtimeMs: Long): String = "v1 $size $mtimeMs"

    fun usable(markerText: String?, size: Long, mtimeMs: Long): Boolean =
        size > 0 && markerText?.trim() == marker(size, mtimeMs)

    /** [YTDL-WARM] Argumen `python3 -m compileall`: bytecode seluruh paket ditulis sekali, di latar, sebelum resolusi pertama. */
    fun compileArgs(dir: String): List<String> = listOf("compileall", "-q", "$dir/yt_dlp")

    /** Nama entri zip yang aman diekstrak (relatif, tanpa ".." dan backslash), atau null. Entri direktori (akhiran /) diterima. */
    fun safeName(raw: String): String? {
        if (raw.isEmpty() || raw.startsWith("/") || '\\' in raw || '\u0000' in raw) return null
        val parts = raw.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == ".." || it == "." }) return null
        return parts.joinToString("/") + if (raw.endsWith("/")) "/" else ""
    }

    /** Galat khas direktori rusak/tidak lengkap (bukan galat situs): penelepon membuang penanda dan mengulang dengan zipapp. */
    fun looksBroken(stderr: String): Boolean {
        val e = stderr.lowercase()
        return "no module named" in e || "can't find '__main__'" in e || "importerror" in e || "modulenotfounderror" in e
    }
}
