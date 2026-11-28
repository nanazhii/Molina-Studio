package com.molina.engine.newpipe

import com.molina.core.net.ShieldHttp
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.net.URL

/**
 * [SHIELD-NP] Downloader NewPipeExtractor di atas [ShieldHttp]: tiap permintaan dan tiap lompatan pengalihan
 * diperiksa kebijakan Shield dan fail-closed. Hanya HTTPS (termasuk pengalihan); badan respons dibatasi
 * [MAX_BODY_BYTES] dan lebih besar dari itu gagal; gzip didekode di [ShieldHttp] agar perilaku sama di JVM dan
 * Android. Pembatalan coroutine ditangani pemanggil (runInterruptible) + timeout.
 */
internal class MolinaDownloader : Downloader() {

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val target = request.url()
        if (!URL(target).protocol.equals("https", ignoreCase = true)) {
            throw IOException("Hanya HTTPS yang diizinkan")
        }
        val headers = LinkedHashMap<String, List<String>>()
        for ((name, values) in request.headers()) headers[name] = values
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) headers["User-Agent"] = listOf(DEFAULT_USER_AGENT)

        val result = ShieldHttp.execute(
            ShieldHttp.Request(
                url = target,
                timeoutMs = CONNECT_TIMEOUT_MS,
                maxHops = MAX_HOPS,
                maxBytes = MAX_BODY_BYTES,
                label = "newpipe",
                method = request.httpMethod(),
                body = request.dataToSend(),
                headerLists = headers,
                readTimeoutMs = READ_TIMEOUT_MS,
                gzip = true,
                httpsOnly = true,
                failOnOverflow = true,
            ),
        )
        return when (result) {
            is ShieldHttp.Result.Blocked -> throw IOException("Diblokir Shield: ${result.reason}")
            is ShieldHttp.Result.Failed -> throw IOException("Permintaan gagal: ${result.reason}")
            is ShieldHttp.Result.Ok -> {
                if (result.code == 429) throw ReCaptchaException("Tantangan reCAPTCHA diminta", target)
                Response(result.code, result.message, result.headers, String(result.body, Charsets.UTF_8), result.finalUrl)
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_BODY_BYTES = 8 * 1024 * 1024
        const val MAX_HOPS = 5
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
