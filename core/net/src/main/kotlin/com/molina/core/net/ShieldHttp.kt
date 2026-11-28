package com.molina.core.net

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * [SHIELD-HTTP] Satu-satunya pintu HTTP(S) kecil di kode JVM yang tunduk pada kebijakan Shield:
 * fail-closed bila Shield menyala tetapi proxy mati, [ShieldProbe.isAllowed] untuk URL dan IP hasil DNS,
 * pengalihan diikuti manual (tiap lompatan diperiksa ulang), batas byte, dan durasi per permintaan.
 * [SHIELD-NP] Mendukung metode + badan (POST), header berdaftar ganda, gzip, header respons, dan mode
 * hanya-HTTPS untuk mesin NewPipe. Pengalihan 301/302/303 mengubah POST menjadi GET tanpa badan; lintas host
 * membuang Authorization/Cookie.
 * Murni JVM, tanpa Android. Sisa risiko: DNS di-resolve ulang oleh HttpURLConnection (jendela rebinding sempit).
 */
object ShieldHttp {
    const val MAX_HOPS = 3

    private val SENSITIVE = setOf("authorization", "cookie", "proxy-authorization")
    private val BODY_HEADERS = setOf("content-type", "content-length", "content-encoding")

    class Request(
        val url: String,
        val timeoutMs: Int = 8_000,
        val headers: Map<String, String> = emptyMap(),
        val maxHops: Int = MAX_HOPS,
        /** 0 = jangan baca isi, hanya status dan Content-Type. */
        val maxBytes: Int = 0,
        /** [SHIELD-LOG] Label pemanggil di baris log (shield/<label> ...): http, probe, iptv, thumb. */
        val label: String = "http",
        /** [SHIELD-NP] Metode HTTP; badan dikirim bila [body] tidak null. */
        val method: String = "GET",
        val body: ByteArray? = null,
        /** [SHIELD-NP] Header dengan beberapa nilai (nilai pertama set, sisanya add). */
        val headerLists: Map<String, List<String>> = emptyMap(),
        /** [SHIELD-NP] 0 = pakai [timeoutMs] juga untuk baca. */
        val readTimeoutMs: Int = 0,
        /** [SHIELD-NP] Minta gzip (Accept-Encoding diatur di sini) dan dekode; batas byte berlaku setelah dekode. */
        val gzip: Boolean = false,
        /** [SHIELD-NP] Tolak tiap lompatan non-HTTPS, termasuk pengalihan turun ke http. */
        val httpsOnly: Boolean = false,
        /** [SHIELD-NP] Isi lebih besar dari [maxBytes] -> Failed, bukan dipotong diam-diam. */
        val failOnOverflow: Boolean = false,
    )

    sealed interface Result {
        class Ok(
            val code: Int,
            val contentType: String?,
            val body: ByteArray,
            val finalUrl: String,
            val ms: Long,
            val hops: Int,
            /** [SHIELD-NP] Header respons akhir (tanpa baris status). */
            val headers: Map<String, List<String>> = emptyMap(),
            val message: String = "",
        ) : Result
        class Blocked(val reason: String) : Result
        class Failed(val reason: String, val ms: Long, val cause: IOException? = null) : Result
    }

    private class Overflow : IOException("respons terlalu besar")

    /** [SHIELD-LOG] Hook log durasi per permintaan, termasuk galat dan blokir (hanya host, tanpa path/query). Diisi lapisan Android; null = diam. */
    @Volatile var onLog: ((String) -> Unit)? = null

    /** Memblokir: panggil dari thread IO. [check] hanya diganti oleh uji. */
    fun execute(req: Request, check: (String) -> Boolean = { ShieldProbe.isAllowed(it) }): Result {
        val r = run(req, check)
        if (onLog != null) {
            val detail = when (r) {
                is Result.Ok -> "HTTP ${r.code} ${r.ms}ms hop=${r.hops}"
                is Result.Failed -> "GAGAL ${r.reason} ${r.ms}ms"
                is Result.Blocked -> "DIBLOKIR ${r.reason}"
            }
            emit(req.label, if (r is Result.Ok) r.finalUrl else req.url.trim(), detail)
        }
        return r
    }

    private fun emit(label: String, url: String, detail: String) {
        val sink = onLog ?: return
        val host = ShieldStats.cleanHost(runCatching { URL(url).host }.getOrDefault(""))
        runCatching { sink("shield/$label $host $detail") }
    }

    /** [SHIELD-DOWNLOAD] Kendali unduhan. [cancel] memutus koneksi (jaringan): panggil dari thread IO, bukan main thread Android. */
    class Handle {
        @Volatile var cancelled = false
        @Volatile internal var conn: HttpURLConnection? = null
        fun cancel() { cancelled = true; conn?.disconnect() }
    }

    sealed interface DownloadResult {
        class Done(val bytes: Long, val ms: Long, val resumed: Boolean) : DownloadResult
        class Blocked(val reason: String) : DownloadResult
        class Failed(val reason: String, val ms: Long, val cause: IOException? = null) : DownloadResult
        object Cancelled : DownloadResult
    }

    private class Opened(val conn: HttpURLConnection, val code: Int, val url: String, val hop: Int)

    private sealed interface Open {
        class Conn(val o: Opened) : Open
        class Stop(val result: Result) : Open
    }

    /** Membuka koneksi sampai respons non-pengalihan: fail-closed, [check] tiap lompatan, pengalihan manual. Penelepon wajib disconnect pada Conn. */
    private fun open(req: Request, check: (String) -> Boolean, t0: Long, handle: Handle?): Open {
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        if (ShieldProxy.failClosed()) return Open.Stop(Result.Blocked("Shield menyala tetapi proxy mati"))
        var current = req.url.trim()
        var method = req.method.uppercase()
        var body = req.body
        var dropSensitive = false
        var dropBody = false
        for (hop in 0..req.maxHops) {
            if (handle?.cancelled == true) return Open.Stop(Result.Failed("dibatalkan", ms()))
            if (req.httpsOnly && !current.startsWith("https://", ignoreCase = true)) return Open.Stop(Result.Failed("hanya HTTPS", ms()))
            if (!check(current)) {
                val h = runCatching { URL(current).host }.getOrDefault("")
                ShieldStats.recordBlocked(h, if (TrackerList.isTracker(h)) ShieldStats.REASON_TRACKER else "tautan ditolak kebijakan Shield")
                return Open.Stop(Result.Blocked("tautan ditolak kebijakan Shield"))
            }
            val conn = try {
                URL(current).openConnection() as? HttpURLConnection ?: return Open.Stop(Result.Failed("bukan HTTP", ms()))
            } catch (e: IOException) {
                return Open.Stop(Result.Failed("tautan tidak valid", ms()))
            }
            handle?.conn = conn
            var keep = false
            try {
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.connectTimeout = req.timeoutMs
                conn.readTimeout = if (req.readTimeoutMs > 0) req.readTimeoutMs else req.timeoutMs
                conn.requestMethod = method
                applyHeaders(conn, req, dropSensitive, dropBody)
                val payload = body
                if (payload != null) {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(payload.size)
                    conn.outputStream.use { it.write(payload) }
                }
                val code = conn.responseCode
                if (code in 301..303 || code == 307 || code == 308) {
                    val loc = conn.getHeaderField("Location") ?: return Open.Stop(Result.Failed("pengalihan tanpa tujuan", ms()))
                    val next = try {
                        URL(URL(current), loc)
                    } catch (e: IOException) {
                        return Open.Stop(Result.Failed("pengalihan tidak valid", ms()))
                    }
                    if (!next.host.equals(URL(current).host, ignoreCase = true)) dropSensitive = true
                    if (code != 307 && code != 308 && method != "GET" && method != "HEAD") {
                        method = "GET"; body = null; dropBody = true
                    }
                    current = next.toString()
                    continue
                }
                keep = true
                return Open.Conn(Opened(conn, code, current, hop))
            } catch (e: IOException) {
                return Open.Stop(Result.Failed(e.javaClass.simpleName, ms(), e))
            } finally {
                if (!keep) conn.disconnect()
            }
        }
        return Open.Stop(Result.Failed("terlalu banyak pengalihan", ms()))
    }

    private fun run(req: Request, check: (String) -> Boolean): Result {
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        val o = when (val x = open(req, check, t0, null)) {
            is Open.Stop -> return x.result
            is Open.Conn -> x.o
        }
        val conn = o.conn
        try {
            val bytes = if (req.maxBytes > 0) readLimited(conn, req) else ByteArray(0)
            val headers = buildMap { for ((k, v) in conn.headerFields) if (k != null) put(k, v.toList()) }
            return Result.Ok(o.code, conn.contentType, bytes, o.url, ms(), o.hop, headers, conn.responseMessage.orEmpty())
        } catch (e: Overflow) {
            return Result.Failed("respons terlalu besar", ms())
        } catch (e: IOException) {
            return Result.Failed(e.javaClass.simpleName, ms(), e)
        } finally {
            conn.disconnect()
        }
    }

    private fun applyHeaders(conn: HttpURLConnection, req: Request, dropSensitive: Boolean, dropBody: Boolean) {
        fun skip(name: String): Boolean {
            val n = name.lowercase()
            return (req.gzip && n == "accept-encoding") || (dropSensitive && n in SENSITIVE) || (dropBody && n in BODY_HEADERS)
        }
        for ((k, v) in req.headers) if (!skip(k)) conn.setRequestProperty(k, v)
        for ((k, vs) in req.headerLists) {
            if (skip(k)) continue
            vs.forEachIndexed { i, v -> if (i == 0) conn.setRequestProperty(k, v) else conn.addRequestProperty(k, v) }
        }
        if (req.gzip) conn.setRequestProperty("Accept-Encoding", "gzip")
    }

    private fun readLimited(conn: HttpURLConnection, req: Request): ByteArray {
        val max = req.maxBytes
        val limit = if (req.failOnOverflow && max < Int.MAX_VALUE) max + 1 else max
        val raw = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
        if (raw == null) return ByteArray(0)
        val gz = req.gzip && conn.contentEncoding.equals("gzip", ignoreCase = true)
        (if (gz) GZIPInputStream(raw) else raw).use { ins ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            while (out.size() < limit) {
                val n = ins.read(buf, 0, minOf(buf.size, limit - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            if (req.failOnOverflow && out.size() > max) throw Overflow()
            return out.toByteArray()
        }
    }

    /**
     * [SHIELD-DOWNLOAD] Unduh [url] ke [dest] dengan kebijakan Shield yang sama dengan [execute]. Lanjut dari panjang [dest] lewat
     * Range (206 = tambah, 200 = ulang dari awal); sisa lebih besar dari [expectedSize] dibuang; berkas utuh langsung selesai.
     * [onProgress] dipanggil saat persentase berubah. Putus di tengah meninggalkan sisa di [dest] agar bisa dilanjutkan.
     * Verifikasi isi (mis. SHA-256) tetap tugas pemanggil. Memblokir: thread IO.
     */
    fun download(
        url: String,
        dest: File,
        expectedSize: Long,
        handle: Handle = Handle(),
        timeoutMs: Int = 30_000,
        connectTimeoutMs: Int = 20_000,
        maxHops: Int = 5,
        label: String = "download",
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        check: (String) -> Boolean = { ShieldProbe.isAllowed(it) },
    ): DownloadResult {
        val r = downloadRun(url, dest, expectedSize, handle, timeoutMs, connectTimeoutMs, maxHops, label, onProgress, check)
        if (onLog != null) {
            val detail = when (r) {
                is DownloadResult.Done -> "UNDUH ${r.bytes}B ${r.ms}ms" + if (r.resumed) " lanjut" else ""
                is DownloadResult.Failed -> "GAGAL ${r.reason} ${r.ms}ms"
                is DownloadResult.Blocked -> "DIBLOKIR ${r.reason}"
                DownloadResult.Cancelled -> "DIBATALKAN"
            }
            emit(label, url, detail)
        }
        return r
    }

    private fun downloadRun(
        url: String, dest: File, expectedSize: Long, handle: Handle, timeoutMs: Int, connectTimeoutMs: Int,
        maxHops: Int, label: String, onProgress: (Long, Long) -> Unit, check: (String) -> Boolean,
    ): DownloadResult {
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        var offset = if (dest.isFile) dest.length() else 0L
        if (expectedSize > 0 && offset > expectedSize) { dest.delete(); offset = 0L }
        if (expectedSize > 0 && offset == expectedSize) return DownloadResult.Done(offset, 0, true)
        val headers = HashMap<String, String>()
        headers["Accept-Encoding"] = "identity" // tanpa kompresi transport: offset Range harus sama dengan byte berkas
        if (offset > 0) headers["Range"] = "bytes=$offset-"
        val req = Request(url, connectTimeoutMs, headers, maxHops, 0, label, readTimeoutMs = timeoutMs)
        val opened = when (val x = open(req, check, t0, handle)) {
            is Open.Stop -> return when (val s = x.result) {
                is Result.Blocked -> DownloadResult.Blocked(s.reason)
                is Result.Failed -> if (handle.cancelled) DownloadResult.Cancelled else DownloadResult.Failed(s.reason, s.ms, s.cause)
                is Result.Ok -> DownloadResult.Failed("hasil tak terduga", ms())
            }
            is Open.Conn -> x.o
        }
        val conn = opened.conn
        try {
            // 206 = lanjut dari offset; 200 = server mengabaikan Range, ulang dari awal.
            val append = when (opened.code) {
                206 -> true
                200 -> false
                else -> return DownloadResult.Failed("HTTP ${opened.code}", ms())
            }
            val total = if (append) expectedSize else (conn.contentLengthLong.takeIf { it > 0 } ?: expectedSize)
            var done = if (append) offset else 0L
            var last = -1
            conn.inputStream.use { input ->
                FileOutputStream(dest, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (handle.cancelled) return DownloadResult.Cancelled
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = ((done * 100) / total).toInt().coerceIn(0, 100)
                            if (p != last) { last = p; onProgress(done, total) }
                        }
                    }
                }
            }
            if (total > 0 && done < total) return DownloadResult.Failed("unduhan terputus", ms())
            return DownloadResult.Done(done, ms(), append && offset > 0)
        } catch (e: IOException) {
            return if (handle.cancelled) DownloadResult.Cancelled else DownloadResult.Failed(e.javaClass.simpleName, ms(), e)
        } finally {
            handle.conn = null
            conn.disconnect()
        }
    }
}
