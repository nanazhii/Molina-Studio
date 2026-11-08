package com.molina.cinema

import com.molina.core.common.StreamEntry
import com.molina.core.common.StreamError
import com.molina.core.common.StreamHeaders
import com.molina.core.common.StreamOutcome
import com.molina.core.common.StreamResolvers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hasil persiapan satu masukan web sebelum diberikan ke mpv. */
internal sealed interface Prepared {
    data class Ready(
        val url: String,
        val headers: Map<String, String>,
        val title: String?,
        val entries: List<StreamEntry> = emptyList(),
    ) : Prepared
    data class Failed(val error: StreamError) : Prepared
}

/**
 * Menjembatani masukan URL pengguna ke mpv: halaman yang dikenali resolver (mis. YouTube) diubah
 * menjadi URL stream + header + judul; URL lain diteruskan apa adanya. Resolusi jalan di
 * Dispatchers.IO; pembatalan coroutine diteruskan ke resolver.
 */
internal object StreamLoader {

    fun isWebUrl(raw: String): Boolean =
        raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)

    private val BARE_HOSTS = listOf("www.", "youtu.be/", "youtube.com/", "m.youtube.com/", "music.youtube.com/")

    /** Teks bebas (bukan URL, bukan path berkas, bukan host tanpa skema) dianggap kata kunci pencarian judul. */
    fun isQuery(raw: String): Boolean {
        val t = raw.trim()
        return t.isNotEmpty() && !t.contains("://") && !t.startsWith("/") &&
            BARE_HOSTS.none { t.startsWith(it, ignoreCase = true) }
    }

    /** [onResolving] dipanggil di thread utama hanya bila resolver benar-benar akan dipakai. */
    suspend fun prepare(raw: String, onResolving: () -> Unit = {}): Prepared {
        if (!isWebUrl(raw)) return Prepared.Ready(raw, emptyMap(), null)
        return withContext(Dispatchers.IO) {
            val resolver = StreamResolvers.get()
            if (resolver == null || !resolver.canResolve(raw)) {
                Prepared.Ready(raw, emptyMap(), null)
            } else {
                withContext(Dispatchers.Main) { onResolving() }
                when (val r = resolver.resolve(raw)) {
                    is StreamOutcome.Success ->
                        Prepared.Ready(r.value.url, StreamHeaders.sanitize(r.value.headers), r.value.title, r.value.entries)
                    is StreamOutcome.Failure -> Prepared.Failed(r.error)
                }
            }
        }
    }

    fun messageRes(e: StreamError): Int = when (e) {
        StreamError.UNSUPPORTED -> R.string.stream_err_unsupported
        StreamError.NOT_FOUND -> R.string.stream_err_not_found
        StreamError.PRIVATE -> R.string.stream_err_private
        StreamError.AGE_RESTRICTED -> R.string.stream_err_age
        StreamError.GEO_BLOCKED -> R.string.stream_err_geo
        StreamError.NETWORK -> R.string.stream_err_network
        StreamError.PARSE -> R.string.stream_err_parse
        StreamError.UNKNOWN -> R.string.stream_err_unknown
    }
}
