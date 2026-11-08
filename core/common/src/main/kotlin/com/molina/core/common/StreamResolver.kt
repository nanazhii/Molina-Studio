package com.molina.core.common

/**
 * Kontrak resolusi URL stream. Implementasi tinggal di modul engine (mis. `:engine:newpipe`);
 * pemutar hanya mengenal tipe di berkas ini, jadi mengganti/menambah engine tidak mengubah pemutar.
 *
 * Catatan: URL stream hasil resolusi bisa kedaluwarsa (mis. YouTube beberapa jam). Untuk antrean
 * panjang, simpan [StreamSearchItem.pageUrl] dan panggil [StreamResolver.resolve] sesaat sebelum
 * diputar, jangan me-resolve semuanya di depan.
 */
data class ResolvedStream(
    /** URL yang langsung bisa dimuat mpv. */
    val url: String,
    /** Header HTTP yang wajib dikirim ke [url] (User-Agent, Referer, dst.). Disaring [StreamHeaders]. */
    val headers: Map<String, String> = emptyMap(),
    val title: String? = null,
    val durationSec: Long? = null,
    /** Epoch ms perkiraan URL kedaluwarsa; null = tidak diketahui. */
    val expiresAtMs: Long? = null,
    /** Playlist hasil resolver (mis. daftar channel yt-dlp); kosong = satu stream. Entri pertama = [url]. */
    val entries: List<StreamEntry> = emptyList(),
)

/** Satu item playlist hasil resolver. */
data class StreamEntry(val url: String, val title: String? = null)

/** Satu hasil pencarian judul. [pageUrl] diteruskan ke [StreamResolver.resolve] saat dipilih. */
data class StreamSearchItem(
    val pageUrl: String,
    val title: String,
    val uploader: String? = null,
    val durationSec: Long? = null,
    val thumbnailUrl: String? = null,
    /** Waktu unggah (epoch ms); null = tidak diketahui (mis. siaran langsung). Untuk YouTube berupa perkiraan. */
    val uploadedAtMs: Long? = null,
    /** Teks waktu unggah mentah dari sumber (mis. "2 days ago"), dipakai bila [uploadedAtMs] kosong. */
    val uploadedText: String? = null,
)

enum class StreamError {
    UNSUPPORTED, NOT_FOUND, PRIVATE, AGE_RESTRICTED, GEO_BLOCKED, NETWORK, PARSE, UNKNOWN
}

/** Hasil operasi resolver. Galat yang diharapkan dikembalikan sebagai [Failure], bukan exception. */
sealed interface StreamOutcome<out T> {
    data class Success<T>(val value: T) : StreamOutcome<T>

    /** [detail] untuk log; jangan isi URL lengkap atau token (log tetap disamarkan [LogRedact]). */
    data class Failure(val error: StreamError, val detail: String? = null) : StreamOutcome<Nothing>
}

interface StreamResolver {
    /** Pengenal singkat engine, untuk log (mis. "newpipe"). */
    val id: String

    /** Cepat dan tanpa jaringan: apakah URL ini dikenali engine. */
    fun canResolve(url: String): Boolean

    /**
     * Ubah URL halaman menjadi stream siap putar. Pembatalan coroutine HARUS diteruskan
     * (jangan ditelan menjadi [StreamOutcome.Failure]).
     */
    suspend fun resolve(pageUrl: String): StreamOutcome<ResolvedStream>

    suspend fun search(query: String, limit: Int = 20): StreamOutcome<List<StreamSearchItem>>
}
