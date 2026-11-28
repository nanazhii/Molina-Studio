package com.molina.cinema

/**
 * [SUBTITLE-CONVERT] Titik sambung untuk mengekstrak subtitle TERTANAM dari video. :feature:mpv tidak bisa
 * memanggil PRoot (arah dependensi :app -> :feature:mpv), jadi :app mendaftarkan implementasinya ke
 * [SubtitleExtractors.provider] (lihat MolinaApp). Selama belum terdaftar, menu Konversi hanya menjelaskan bahwa
 * ekstraksi belum tersedia. FFmpeg di libmpv Molina hanya punya demuxer (tanpa muxer), sehingga ekstraksi
 * in-process tidak mungkin tanpa membangun ulang native; implementasi di :app memakai FFmpeg di Debian PRoot.
 */

/**
 * [SUBTITLE-EXTRACT] Alasan ekstraksi belum bisa jalan, siap tampil. Bila [openTools] terisi, UI menampilkan tombol
 * "Buka Paket > Alat" yang memanggilnya (mis. FFmpeg belum terpasang).
 */
class ExtractBlock(
    val message: String,
    val openTools: (() -> Unit)? = null,
)

interface EmbeddedSubtitleExtractor {
    /** Null bila siap; selain itu alasan siap tampil (FFmpeg belum terpasang, video di luar storage yang di-bind, dst). */
    suspend fun unavailableReason(mediaPath: String): ExtractBlock?

    /** Ekstrak stream [streamIndex] (indeks kontainer) ke [outPath] berformat [target]. Null = sukses; selain itu pesan galat. */
    suspend fun extract(mediaPath: String, streamIndex: Int, target: SubFormat, outPath: String): String?
}

object SubtitleExtractors {
    @Volatile
    var provider: EmbeddedSubtitleExtractor? = null
}
