package com.molina.core.common

/**
 * Menggabungkan dua resolver: [primary] (mis. NewPipe) lebih dulu, [fallback] (mis. yt-dlp) untuk
 * URL yang tidak dikenali [primary] atau gagal karena tidak didukung/tidak terurai. Galat yang
 * pasti sama di engine lain (privat, tidak ditemukan, geo) tidak diulang di [fallback].
 */
class CompositeStreamResolver(
    private val primary: StreamResolver,
    private val fallback: StreamResolver?,
) : StreamResolver {
    override val id: String = "composite"

    override fun canResolve(url: String): Boolean =
        primary.canResolve(url) || fallback?.canResolve(url) == true

    override suspend fun resolve(pageUrl: String): StreamOutcome<ResolvedStream> {
        val fb = fallback?.takeIf { it.canResolve(pageUrl) }
        if (!primary.canResolve(pageUrl)) {
            return fb?.resolve(pageUrl) ?: StreamOutcome.Failure(StreamError.UNSUPPORTED, "tidak ada engine")
        }
        val r = primary.resolve(pageUrl)
        if (r is StreamOutcome.Failure && fb != null && retryable(r.error)) {
            return fb.resolve(pageUrl)
        }
        return r
    }

    override suspend fun search(query: String, limit: Int): StreamOutcome<List<StreamSearchItem>> {
        val r = primary.search(query, limit)
        if (r is StreamOutcome.Failure && fallback != null && retryable(r.error)) {
            return fallback.search(query, limit)
        }
        return r
    }

    private fun retryable(e: StreamError): Boolean = e == StreamError.UNSUPPORTED || e == StreamError.PARSE
}
