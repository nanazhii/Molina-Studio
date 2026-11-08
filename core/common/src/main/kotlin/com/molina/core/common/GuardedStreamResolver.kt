package com.molina.core.common

/**
 * Pembungkus resolver yang menerapkan [UrlPolicy] pada dua sisi: URL halaman yang masuk, dan URL
 * stream yang dikembalikan engine. Respons server (NewPipe, yt-dlp) tidak dipercaya: URL hasil
 * resolusi yang mengarah ke skema atau alamat terlarang ditolak sebelum sampai ke mpv.
 */
class GuardedStreamResolver(
    private val inner: StreamResolver,
    private val allowLan: () -> Boolean = { NetworkPolicy.allowLan },
) : StreamResolver {
    override val id: String get() = inner.id

    override fun canResolve(url: String): Boolean =
        UrlPolicy.check(url, allowLan()) is UrlPolicy.Verdict.Allowed && inner.canResolve(url)

    override suspend fun resolve(pageUrl: String): StreamOutcome<ResolvedStream> {
        val pre = UrlPolicy.check(pageUrl, allowLan())
        if (pre is UrlPolicy.Verdict.Blocked) {
            return StreamOutcome.Failure(StreamError.UNSUPPORTED, "URL ditolak: ${pre.reason.message}")
        }
        val r = inner.resolve(pageUrl)
        if (r is StreamOutcome.Success) {
            if (r.value.entries.isNotEmpty()) return guardEntries(r.value)
            val post = UrlPolicy.check(r.value.url, allowLan())
            if (post is UrlPolicy.Verdict.Blocked) {
                return StreamOutcome.Failure(StreamError.UNSUPPORTED, "URL hasil resolusi ditolak: ${post.reason.message}")
            }
        }
        return r
    }

    /** Playlist: entri yang ditolak [UrlPolicy] dibuang; yang pertama lolos jadi item utama. */
    private fun guardEntries(v: ResolvedStream): StreamOutcome<ResolvedStream> {
        val ok = v.entries.filter { UrlPolicy.check(it.url, allowLan()) is UrlPolicy.Verdict.Allowed }
        if (ok.isEmpty()) {
            return StreamOutcome.Failure(StreamError.UNSUPPORTED, "semua entri ditolak kebijakan URL")
        }
        return StreamOutcome.Success(v.copy(url = ok[0].url, title = ok[0].title, entries = ok))
    }

    override suspend fun search(query: String, limit: Int): StreamOutcome<List<StreamSearchItem>> =
        inner.search(query, limit)
}
