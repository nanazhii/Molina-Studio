package com.molina.engine.newpipe

import com.molina.core.common.AppLog
import com.molina.core.common.LogLevel
import com.molina.core.common.OriginalTitles
import com.molina.core.common.ResolvedStream
import com.molina.core.common.SearchLang
import com.molina.core.common.StreamError
import com.molina.core.common.StreamOutcome
import com.molina.core.common.StreamResolver
import com.molina.core.common.StreamSearchItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.net.URLEncoder

/**
 * Implementasi [StreamResolver] berbasis NewPipeExtractor (resolusi langsung di perangkat, tanpa
 * API resmi). Semua pekerjaan jaringan jalan di Dispatchers.IO lewat runInterruptible sehingga
 * pembatalan coroutine menginterupsi thread (ditambah timeout di [MolinaDownloader]).
 * Galat yang diharapkan dikembalikan sebagai [StreamOutcome.Failure]; pembatalan diteruskan.
 */
private const val OEMBED_URL = "https://www.youtube.com/oembed?format=json&url="

class NewPipeStreamResolver(
    languageCode: String = "id",
    countryCode: String = "ID",
) : StreamResolver {

    override val id: String = "newpipe"

    init {
        ensureInit(Localization(languageCode, countryCode), ContentCountry(countryCode))
    }

    override fun canResolve(url: String): Boolean = serviceFor(url) != null

    override suspend fun resolve(pageUrl: String): StreamOutcome<ResolvedStream> {
        val service = serviceFor(pageUrl)
            ?: return StreamOutcome.Failure(StreamError.UNSUPPORTED, "url tidak dikenali")
        val started = System.nanoTime()
        return try {
            val info = runInterruptible(Dispatchers.IO) { StreamInfo.getInfo(service, pageUrl) }
            val pick = StreamPicker.pick(info)
                ?: return fail(StreamError.NOT_FOUND, "tidak ada stream yang bisa diputar")
            val ms = (System.nanoTime() - started) / 1_000_000
            AppLog.log(
                SOURCE, LogLevel.Info, "resolve",
                "OK layanan=${service.serviceInfo.name} jenis=${pick.kind}" +
                    (if (pick.height > 0) " ${pick.height}p" else "") + " dalam ${ms}ms",
            )
            StreamOutcome.Success(
                ResolvedStream(
                    url = pick.url,
                    headers = mapOf("User-Agent" to userAgentFor(pick.url)),
                    title = info.name?.takeIf { it.isNotBlank() },
                    durationSec = info.duration.takeIf { it > 0 },
                    expiresAtMs = StreamPicker.expiresAtMs(pick.url),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive() // interupsi akibat pembatalan bukan galat jaringan
            failFrom("resolve", e)
        }
    }

    override suspend fun search(query: String, limit: Int): StreamOutcome<List<StreamSearchItem>> {
        val q = query.trim()
        if (q.isEmpty()) return StreamOutcome.Success(emptyList())
        return try {
            val items = runInterruptible(Dispatchers.IO) {
                val service = ServiceList.YouTube
                val handler = service.searchQHFactory.fromQuery(q)
                // Kunci bahasa menyaring sesudahnya, jadi kumpulkan kandidat 2x lebih banyak.
                val want = if (SearchLang.enabled) (limit * 2).coerceAtMost(70) else limit
                val first = SearchInfo.getInfo(service, handler)
                val all = ArrayList<InfoItem>(first.relatedItems.orEmpty())
                var page = first.nextPage
                var pages = 1
                // Satu halaman YouTube hanya ~20 entri campuran; lanjutkan sampai [limit] video terkumpul.
                try {
                    while (page != null && pages < 6 && all.count { it is StreamInfoItem } < want) {
                        val more = SearchInfo.getMoreItems(service, handler, page)
                        all.addAll(more.items.orEmpty())
                        page = more.nextPage
                        pages++
                    }
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    // halaman lanjutan gagal: pakai hasil yang sudah terkumpul
                }
                all
            }
            val cands = items.asSequence()
                .filterIsInstance<StreamInfoItem>()
                .distinctBy { it.url }
                .map { i ->
                    StreamSearchItem(
                        pageUrl = i.url,
                        title = i.name.orEmpty(),
                        uploader = i.uploaderName?.takeIf { it.isNotBlank() },
                        durationSec = i.duration.takeIf { it >= 0 },
                        thumbnailUrl = i.thumbnails.orEmpty().maxByOrNull { it.height }?.url,
                        uploadedAtMs = i.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli(),
                        uploadedText = i.textualUploadDate?.takeIf { it.isNotBlank() },
                    )
                }
                .toList()
            // Judul hasil pencarian dilokalkan YouTube; ganti dengan judul asli sebelum klasifikasi bahasa.
            val titled = if (SearchLang.originalTitles) {
                runInterruptible(Dispatchers.IO) {
                    val r = OriginalTitles.apply(cands) { u ->
                        try {
                            NewPipe.getDownloader().get(OEMBED_URL + URLEncoder.encode(u, "UTF-8")).responseBody()
                        } catch (e: Exception) {
                            null
                        }
                    }
                    AppLog.log(SOURCE, LogLevel.Info, "search", "judul asli: ${r.known}/${cands.size} terambil, ${r.changed} beda dari judul pencarian")
                    r.items
                }
            } else {
                cands
            }
            val out = SearchLang.apply(titled, limit)
            AppLog.log(SOURCE, LogLevel.Info, "search", "OK ${out.size} hasil")
            StreamOutcome.Success(out)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            failFrom("search", e)
        }
    }

    private fun serviceFor(url: String): StreamingService? =
        try {
            ServiceList.all().firstOrNull {
                it.getLinkTypeByUrl(url) == StreamingService.LinkType.STREAM
            }
        } catch (e: Exception) {
            null
        }

    /** URL googlevideo terikat ke klien asalnya: UA harus cocok, jika tidak 403. Pola molina_suite. */
    private fun userAgentFor(url: String): String = try {
        when {
            YoutubeParsingHelper.isIosStreamingUrl(url) -> YoutubeParsingHelper.getIosUserAgent(null)
            YoutubeParsingHelper.isAndroidStreamingUrl(url) -> YoutubeParsingHelper.getAndroidUserAgent(null)
            YoutubeParsingHelper.isVisionOsStreamingUrl(url) -> YoutubeParsingHelper.getVisionOsUserAgent(null)
            else -> WEB_USER_AGENT
        }
    } catch (e: Exception) {
        WEB_USER_AGENT
    }

    private fun fail(error: StreamError, detail: String): StreamOutcome.Failure {
        AppLog.log(SOURCE, LogLevel.Warn, "resolve", "gagal $error: $detail")
        return StreamOutcome.Failure(error, detail)
    }

    /** Detail hanya nama kelas galat: pesan extractor bisa memuat URL/token. */
    private fun failFrom(tag: String, e: Exception): StreamOutcome.Failure {
        val error = map(e)
        val detail = e.javaClass.simpleName
        AppLog.log(SOURCE, LogLevel.Warn, tag, "gagal $error: $detail")
        return StreamOutcome.Failure(error, detail)
    }

    internal companion object {
        const val SOURCE = "newpipe"
        const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
        private val lock = Any()
        private var initialized = false

        private fun ensureInit(l: Localization, c: ContentCountry) {
            synchronized(lock) {
                if (!initialized) {
                    NewPipe.init(MolinaDownloader(), l, c)
                    initialized = true
                }
            }
        }

        /** Urutan penting: subkelas lebih dulu dari induknya. */
        fun map(e: Exception): StreamError = when (e) {
            is AgeRestrictedContentException -> StreamError.AGE_RESTRICTED
            is GeographicRestrictionException -> StreamError.GEO_BLOCKED
            is PrivateContentException -> StreamError.PRIVATE
            is ContentNotAvailableException -> StreamError.NOT_FOUND
            is ContentNotSupportedException -> StreamError.UNSUPPORTED
            is SignInConfirmNotBotException -> StreamError.NETWORK
            is ParsingException -> StreamError.PARSE
            is ReCaptchaException -> StreamError.NETWORK
            is ExtractionException -> StreamError.UNKNOWN
            is IOException -> StreamError.NETWORK
            else -> StreamError.UNKNOWN
        }
    }
}
