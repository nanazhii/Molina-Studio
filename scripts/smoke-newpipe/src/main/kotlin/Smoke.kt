import com.molina.engine.newpipe.MolinaDownloader
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.exitProcess

// Uji runtime NewPipeExtractor: init -> pencarian -> resolve -> ambil 1 KB stream (Range).
// Memakai MolinaDownloader asli (disalin oleh workflow). Kode keluar 1 bila ada langkah gagal.

private const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
private const val FIXED_VIDEO = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"
private var failures = 0

/** Membungkus downloader asli: mencatat status dan awal isi saat respons >= 400 atau HTML. */
private class LoggingDownloader(private val inner: Downloader) : Downloader() {
    override fun execute(request: Request): Response {
        val r = inner.execute(request)
        val ct = r.getHeader("Content-Type").orEmpty()
        if (r.responseCode() >= 400 || ct.contains("text/html", ignoreCase = true)) {
            val head = r.responseBody().orEmpty().replace(Regex("\\s+"), " ").take(160)
            println("  [HTTP ${r.responseCode()}] ${request.url().substringBefore('?')} ct=$ct :: $head")
        }
        return r
    }
}

private fun step(name: String, block: () -> String) {
    try {
        println("PASS  $name: ${block()}")
    } catch (e: Throwable) {
        failures++
        println("FAIL  $name: ${e.javaClass.name}: ${(e.message ?: "").replace('\n', ' ').take(300)}")
        var c = e.cause
        var d = 0
        while (c != null && d < 3) {
            println("      sebab: ${c.javaClass.name}: ${(c.message ?: "").replace('\n', ' ').take(200)}")
            c = c.cause
            d++
        }
    }
}

/** UA harus cocok dengan klien asal URL. iOS/Android hanya ada di versi lama (dicek lewat refleksi). */
private fun userAgentFor(url: String): String {
    val cls = YoutubeParsingHelper::class.java
    for ((isFn, uaFn) in listOf("isIosStreamingUrl" to "getIosUserAgent", "isAndroidStreamingUrl" to "getAndroidUserAgent")) {
        try {
            if (cls.getMethod(isFn, String::class.java).invoke(null, url) == true) {
                return cls.getMethod(uaFn, Localization::class.java).invoke(null, *arrayOf<Any?>(null)) as String
            }
        } catch (e: ReflectiveOperationException) {
            // API sudah dihapus pada versi ini
        }
    }
    return try {
        if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) YoutubeParsingHelper.getVisionOsUserAgent(null) else WEB_UA
    } catch (e: Exception) {
        WEB_UA
    }
}

private fun probe(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", userAgentFor(url))
        c.setRequestProperty("Range", "bytes=0-1023")
        val code = c.responseCode
        require(code == 200 || code == 206) { "HTTP $code" }
        val n = c.inputStream.use { it.readNBytes(1024).size }
        return "HTTP $code, $n byte"
    } finally {
        c.disconnect()
    }
}

private fun <T : Stream> List<T>.progressive(): List<T> =
    filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }

private fun resolveAndProbe(label: String, pageUrl: String) {
    var info: StreamInfo? = null
    step("resolve[$label]") {
        val i = StreamInfo.getInfo(ServiceList.YouTube, pageUrl)
        info = i
        val hls = !i.hlsUrl.isNullOrEmpty()
        require(i.videoStreams.isNotEmpty() || i.videoOnlyStreams.isNotEmpty() || i.audioStreams.isNotEmpty() || hls) { "tidak ada stream" }
        "muxed=${i.videoStreams.size} videoOnly=${i.videoOnlyStreams.size} audio=${i.audioStreams.size} hls=$hls"
    }
    val i = info ?: return
    step("audio[$label]") {
        val a = i.audioStreams.progressive().maxByOrNull { it.averageBitrate } ?: error("tidak ada audio progresif")
        probe(a.content)
    }
    step("video-720p[$label]") {
        val v = (i.videoStreams + i.videoOnlyStreams).progressive().filter { it.height in 1..720 }.maxByOrNull { it.height }
            ?: error("tidak ada video progresif <=720p")
        "${v.height}p ${probe(v.content)}"
    }
}

fun main() {
    println("== Smoke NewPipe ${System.getProperty("npCoord")} (JVM ${System.getProperty("java.version")}) ==")
    step("init") {
        com.molina.core.net.ShieldProxy.enabled = false // [SHIELD-NP] runner CI tanpa proxy lokal; fail-closed memblokir semua
        NewPipe.init(LoggingDownloader(MolinaDownloader()), Localization("id", "ID"), ContentCountry("ID"))
        "ok"
    }
    val yt = ServiceList.YouTube
    var handler: SearchQueryHandler? = null
    var first: SearchInfo? = null
    var firstUrl: String? = null
    step("search") {
        val h = yt.searchQHFactory.fromQuery("big buck bunny", listOf("videos"), "")
        handler = h
        val r = SearchInfo.getInfo(yt, h)
        first = r
        val vids = r.relatedItems.filterIsInstance<StreamInfoItem>()
        require(vids.size >= 5) { "hanya ${vids.size} video" }
        firstUrl = vids.firstOrNull { it.duration > 0 }?.url
        "${vids.size} video"
    }
    step("search-lanjutan") {
        val h = handler ?: error("dilewati: search gagal")
        val page = (first ?: error("dilewati: search gagal")).nextPage ?: error("tidak ada halaman lanjutan")
        "${SearchInfo.getMoreItems(yt, h, page).items.size} item"
    }
    resolveAndProbe("tetap", FIXED_VIDEO)
    firstUrl?.let { resolveAndProbe("hasil-search", it) }
    println(if (failures == 0) "HASIL: SEMUA LULUS" else "HASIL: $failures LANGKAH GAGAL")
    exitProcess(if (failures == 0) 0 else 1)
}
