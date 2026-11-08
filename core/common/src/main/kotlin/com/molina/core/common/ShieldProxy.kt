package com.molina.core.common

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/**
 * Molina Shield: proxy HTTP lokal di 127.0.0.1 (port acak, wajib token) yang dilewati semua lalu
 * lintas http/https mpv/FFmpeg. Redirect, segmen HLS/DASH, dan entri playlist semuanya kembali
 * lewat sini, jadi tiap lompatan diperiksa ulang. Alur per permintaan:
 * 1. token (FFmpeg membalas tantangan 407 dengan Proxy-Authorization),
 * 2. [UrlPolicy] pada URL, port sensitif, daftar pelacak ([TrackerList]),
 * 3. DNS di-resolve DI SINI, tiap IP diperiksa [ShieldPolicy.checkAddress], lalu menyambung ke IP
 *    yang sama (anti DNS rebinding),
 * 4. http: diteruskan sebagai origin-form tanpa Cookie/Proxy-*, Connection: close; https: CONNECT
 *    (TLS tetap ujung ke ujung, Shield tidak melihat isinya).
 * Tidak mencakup rtmp/rtsp (tidak lewat proxy HTTP) dan yt-dlp (jalan di dalam Debian).
 * Murni JVM, tanpa Android.
 */
object ShieldProxy {
    private const val USER = "molina"
    private const val TAG = "shield"
    private const val MAX_HEAD_BYTES = 16 * 1024
    private const val MAX_CONNECTIONS = 64
    private const val HEAD_TIMEOUT_MS = 15_000
    private const val IDLE_TIMEOUT_MS = 30_000
    private const val CONNECT_TIMEOUT_MS = 8_000

    /** Header yang tidak diteruskan ke server asal (hop-by-hop, kredensial proxy, cookie). */
    private val DROP_HEADERS = setOf(
        "proxy-authorization", "proxy-connection", "connection", "keep-alive",
        "te", "upgrade", "cookie", "trailer", "transfer-encoding",
    )

    @Volatile private var server: ServerSocket? = null
    @Volatile private var token: String = ""
    @Volatile private var lanProvider: () -> Boolean = { NetworkPolicy.allowLan }
    private val slots = Semaphore(MAX_CONNECTIONS)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "molina-shield").apply { isDaemon = true }
    }

    /** Sakelar pengguna (ShieldPrefs). Mati: [proxyUrl] null sehingga mpv tidak memakai proxy. */
    @Volatile var enabled: Boolean = true

    /** Buang header Referer sebelum diteruskan ke server asal (default mati: sebagian IPTV memerlukannya). */
    @Volatile var stripReferer: Boolean = false

    fun isRunning(): Boolean = server?.isClosed == false

    /** URL proxy untuk opsi `http_proxy` FFmpeg, atau null bila Shield tidak berjalan. */
    fun proxyUrl(): String? {
        if (!enabled) return null
        val s = server ?: return null
        if (s.isClosed) return null
        return "http" + "://" + USER + ":" + token + "@127.0.0.1:" + s.localPort
    }

    @Synchronized
    fun start(allowLan: () -> Boolean = { NetworkPolicy.allowLan }): Boolean {
        if (isRunning()) return true
        lanProvider = allowLan
        return try {
            val raw = ByteArray(24)
            SecureRandom().nextBytes(raw)
            token = raw.joinToString("") { "%02x".format(it) }
            val s = ServerSocket(0, 50, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            server = s
            Thread({ acceptLoop(s) }, "molina-shield-accept").apply {
                isDaemon = true
                start()
            }
            AppLog.log("net", LogLevel.Info, TAG, "Molina Shield aktif (port ${s.localPort})")
            true
        } catch (e: IOException) {
            AppLog.log("net", LogLevel.Error, TAG, "Shield gagal start: ${e.javaClass.simpleName}")
            false
        }
    }

    @Synchronized
    fun stop() {
        try {
            server?.close()
        } catch (e: IOException) {
            // abaikan
        }
        server = null
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try {
                s.accept()
            } catch (e: IOException) {
                break
            }
            if (!slots.tryAcquire()) {
                closeQuietly(c)
                continue
            }
            try {
                pool.execute {
                    try {
                        handle(c)
                    } catch (e: Exception) {
                        // satu koneksi gagal tidak boleh menjatuhkan proxy
                    } finally {
                        slots.release()
                        closeQuietly(c)
                    }
                }
            } catch (e: Exception) {
                slots.release()
                closeQuietly(c)
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = HEAD_TIMEOUT_MS
        client.tcpNoDelay = true
        val input = client.getInputStream()
        val out = client.getOutputStream()
        val head = readHead(input) ?: return reply(out, 400, "Bad Request")
        val lines = head.split("\r\n")
        val first = lines[0].split(' ')
        if (first.size != 3) return reply(out, 400, "Bad Request")
        val method = first[0].uppercase()
        val target = first[1]
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val c = lines[i].indexOf(':')
            if (c <= 0) continue
            headers[lines[i].substring(0, c).trim().lowercase()] = lines[i].substring(c + 1).trim()
        }
        if (!authorized(headers["proxy-authorization"])) return deny407(out)
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        if (length > 0L) return reply(out, 400, "Bad Request")
        when (method) {
            "CONNECT" -> handleConnect(client, out, target)
            "GET", "HEAD" -> handleForward(client, out, method, target, lines)
            else -> reply(out, 405, "Method Not Allowed")
        }
    }

    private fun handleConnect(client: Socket, out: OutputStream, target: String) {
        val hp = splitHostPort(target, 443) ?: return reply(out, 400, "Bad Request")
        val origin = openOrigin(out, hp.first, hp.second, "https" + "://" + target) ?: return
        try {
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
            ShieldStats.recordPassed()
            tunnel(client, origin, clientToOrigin = true)
        } finally {
            closeQuietly(origin)
        }
    }

    private fun handleForward(client: Socket, out: OutputStream, method: String, target: String, lines: List<String>) {
        if (!target.startsWith("http://", ignoreCase = true)) return reply(out, 400, "Bad Request")
        val afterScheme = target.substring(7)
        val authEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authEnd < 0) afterScheme else afterScheme.substring(0, authEnd)
        var path = if (authEnd < 0) "/" else afterScheme.substring(authEnd)
        if (!path.startsWith("/")) path = "/$path"
        path = path.substringBefore('#')
        val hp = splitHostPort(authority, 80) ?: return reply(out, 400, "Bad Request")
        val origin = openOrigin(out, hp.first, hp.second, target) ?: return
        try {
            val sb = StringBuilder()
            sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            var hasHost = false
            for (i in 1 until lines.size) {
                val l = lines[i]
                val c = l.indexOf(':')
                if (c <= 0) continue
                if (l.indexOf('\n') >= 0 || l.indexOf('\r') >= 0) continue
                val name = l.substring(0, c).trim().lowercase()
                if (name in DROP_HEADERS || (stripReferer && name == "referer")) continue
                if (name == "host") hasHost = true
                sb.append(l).append("\r\n")
            }
            if (!hasHost) sb.append("Host: ").append(authority).append("\r\n")
            sb.append("Connection: close\r\n\r\n")
            val o = origin.getOutputStream()
            o.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            o.flush()
            ShieldStats.recordPassed()
            tunnel(client, origin, clientToOrigin = false)
        } finally {
            closeQuietly(origin)
        }
    }

    /** Periksa tujuan lalu sambung ke salah satu IP yang lolos; null bila ditolak (balasan sudah dikirim). */
    private fun openOrigin(out: OutputStream, host: String, port: Int, policyUrl: String): Socket? {
        val allowLan = lanProvider()
        val v = UrlPolicy.check(policyUrl, allowLan)
        if (v is UrlPolicy.Verdict.Blocked) {
            block(out, host, v.reason.message)
            return null
        }
        if (!ShieldPolicy.portAllowed(port)) {
            block(out, host, "port tidak diizinkan")
            return null
        }
        if (TrackerList.isTracker(host)) {
            block(out, host, "pelacak atau iklan")
            return null
        }
        val all = try {
            InetAddress.getAllByName(host)
        } catch (e: UnknownHostException) {
            reply(out, 502, "Bad Gateway")
            return null
        }
        val ok = all.filter { ShieldPolicy.checkAddress(it, allowLan) is UrlPolicy.Verdict.Allowed }
            .sortedBy { if (it is Inet4Address) 0 else 1 }
        if (ok.isEmpty()) {
            block(out, host, "DNS mengarah ke alamat terlarang")
            return null
        }
        for (ip in ok) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                s.tcpNoDelay = true
                s.soTimeout = IDLE_TIMEOUT_MS
                return s
            } catch (e: IOException) {
                closeQuietly(s)
            }
        }
        reply(out, 502, "Bad Gateway")
        return null
    }

    private fun tunnel(client: Socket, origin: Socket, clientToOrigin: Boolean) {
        client.soTimeout = IDLE_TIMEOUT_MS
        if (clientToOrigin) {
            val up = Thread({
                pump(client.getInputStream(), origin.getOutputStream(), ignoreTimeout = true)
                try {
                    origin.shutdownOutput()
                } catch (e: IOException) {
                    // abaikan
                }
            }, "molina-shield-up")
            up.isDaemon = true
            up.start()
        }
        pump(origin.getInputStream(), client.getOutputStream(), ignoreTimeout = false)
        closeQuietly(origin)
        closeQuietly(client)
    }

    private fun pump(src: InputStream, dst: OutputStream, ignoreTimeout: Boolean) {
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = try {
                src.read(buf)
            } catch (e: SocketTimeoutException) {
                if (ignoreTimeout) continue else return
            } catch (e: IOException) {
                return
            }
            if (n < 0) return
            try {
                dst.write(buf, 0, n)
                dst.flush()
            } catch (e: IOException) {
                return
            }
        }
    }

    private fun authorized(given: String?): Boolean {
        if (given == null) return false
        val expected = "Basic " + Base64.getEncoder().encodeToString((USER + ":" + token).toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }

    /** Baca kepala permintaan byte demi byte (tanpa membaca lebih dari "\r\n\r\n"); null bila tidak sah. */
    private fun readHead(input: InputStream): String? {
        val buf = ByteArray(MAX_HEAD_BYTES)
        var n = 0
        try {
            while (n < buf.size) {
                val b = input.read()
                if (b < 0) return null
                buf[n++] = b.toByte()
                if (n >= 4 && buf[n - 4] == CR && buf[n - 3] == LF && buf[n - 2] == CR && buf[n - 1] == LF) {
                    return String(buf, 0, n - 4, Charsets.ISO_8859_1)
                }
            }
        } catch (e: IOException) {
            return null
        }
        return null
    }

    private const val CR: Byte = 13
    private const val LF: Byte = 10

    private fun splitHostPort(s: String, defaultPort: Int): Pair<String, Int>? {
        if (s.isEmpty()) return null
        val host: String
        val portText: String
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return null
            host = s.substring(1, end)
            val rest = s.substring(end + 1)
            portText = when {
                rest.isEmpty() -> ""
                rest.startsWith(":") -> rest.substring(1)
                else -> return null
            }
        } else {
            val c = s.lastIndexOf(':')
            if (c >= 0) {
                host = s.substring(0, c)
                portText = s.substring(c + 1)
            } else {
                host = s
                portText = ""
            }
        }
        if (host.isEmpty()) return null
        val port = if (portText.isEmpty()) defaultPort else (portText.toIntOrNull() ?: return null)
        if (port !in 1..65535) return null
        return host to port
    }

    private fun block(out: OutputStream, host: String, reason: String) {
        ShieldStats.recordBlocked(host, reason)
        AppLog.log("net", LogLevel.Warn, TAG, "blokir ${ShieldStats.cleanHost(host)}: $reason")
        reply(out, 403, "Forbidden")
    }

    private fun deny407(out: OutputStream) {
        try {
            out.write(
                ("HTTP/1.1 407 Proxy Authentication Required\r\n" +
                    "Proxy-Authenticate: Basic realm=\"molina\"\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
            )
            out.flush()
        } catch (e: IOException) {
            // klien sudah pergi
        }
    }

    private fun reply(out: OutputStream, code: Int, msg: String) {
        try {
            out.write("HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        } catch (e: IOException) {
            // klien sudah pergi
        }
    }

    private fun closeQuietly(s: Socket) {
        try {
            s.close()
        } catch (e: IOException) {
            // abaikan
        }
    }
}
