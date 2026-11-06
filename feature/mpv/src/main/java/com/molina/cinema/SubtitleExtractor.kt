package com.molina.cinema

/**
 * [SUBTITLE-CONVERT] Titik sambung untuk mengekstrak subtitle TERTANAM dari video. :feature:mpv tidak bisa
 * memanggil PRoot (arah dependensi :app -> :feature:mpv), jadi :app mendaftarkan implementasinya ke
 * [SubtitleExtractors.provider]. Selama belum terdaftar, menu Konversi hanya menjelaskan bahwa ekstraksi
 * belum tersedia. FFmpeg di libmpv Molina hanya punya demuxer (tanpa muxer), sehingga ekstraksi in-process
 * tidak mungkin tanpa membangun ulang native.
 */
interface EmbeddedSubtitleExtractor {
    /** Null bila siap; selain itu pesan siap tampil (mis. FFmpeg belum terpasang, atau video di luar storage yang di-bind). */
    suspend fun unavailableReason(mediaPath: String): String?

    /** Ekstrak stream [streamIndex] (indeks kontainer) ke [outPath] berformat [target]. Null = sukses; selain itu pesan galat. */
    suspend fun extract(mediaPath: String, streamIndex: Int, target: SubFormat, outPath: String): String?
}

object SubtitleExtractors {
    @Volatile
    var provider: EmbeddedSubtitleExtractor? = null
}
