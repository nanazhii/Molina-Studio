package com.molina.engine.newpipe

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Downloader NewPipeExtractor di atas HttpURLConnection (tanpa dependensi tambahan).
 * Hanya HTTPS; badan respons dibatasi [MAX_BODY_BYTES]; gzip didekode di sini agar perilaku sama
 * di JVM dan Android. Pembatalan coroutine ditangani pemanggil (runInterruptible) + timeout.
 */
internal class MolinaDownloader : Downloader() {

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val target = request.url()
        val url = URL(target)
        if (!url.protocol.equals("https", ignoreCase = true)) {
            throw IOException("Hanya HTTPS yang diizinkan")
        }
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.requestMethod = request.httpMethod()

            var hasUserAgent = false
            for ((name, values) in request.headers()) {
                if (name.equals("Accept-Encoding", ignoreCase = true)) continue // diatur di bawah
                if (name.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
                values.forEachIndexed { i, v ->
                    if (i == 0) conn.setRequestProperty(name, v) else conn.addRequestProperty(name, v)
                }
            }
            if (!hasUserAgent) conn.setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
            conn.setRequestProperty("Accept-Encoding", "gzip")

            val body = request.dataToSend()
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }

            val code = conn.responseCode
            if (code == 429) throw ReCaptchaException("Tantangan reCAPTCHA diminta", target)

            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val gzip = conn.contentEncoding.equals("gzip", ignoreCase = true)
            val text = stream?.use { readCapped(if (gzip) GZIPInputStream(it) else it) }.orEmpty()

            val headers = conn.headerFields
                .filterKeys { it != null }
                .mapValues { it.value.toList() }
            return Response(code, conn.responseMessage.orEmpty(), headers, text, conn.url.toString())
        } finally {
            conn.disconnect()
        }
    }

    private fun readCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BODY_BYTES) throw IOException("Respons terlalu besar")
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_BODY_BYTES = 8 * 1024 * 1024
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
