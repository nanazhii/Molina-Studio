import com.molina.core.net.IptvFetch
import com.molina.core.net.IptvLoad
import com.molina.core.net.ShieldHttp
import com.molina.core.net.ShieldProbe
import com.molina.core.net.ShieldProxy
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

/** Server HTTP mini di loopback: /ok, /r1 (302 ke /ok), /loop, /bad (302 ke host lain), /big (1000 byte). */
private fun serve(ss: ServerSocket) = thread(isDaemon = true) {
    while (!ss.isClosed) {
        val c = try { ss.accept() } catch (e: Exception) { return@thread }
        thread(isDaemon = true) {
            c.use {
                val line = it.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine() ?: return@use
                val path = line.split(" ").getOrElse(1) { "/" }
                val port = ss.localPort
                val (head, body) = when (path) {
                    "/r1" -> "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:$port/ok\r\n" to ""
                    "/loop" -> "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:$port/loop\r\n" to ""
                    "/bad" -> "HTTP/1.1 302 Found\r\nLocation: http://blocked.invalid/x\r\n" to ""
                    "/big" -> "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" to "x".repeat(1000)
                    "/m3u" -> "HTTP/1.1 200 OK\r\nContent-Type: audio/mpegurl\r\n" to "#EXTM3U\n#EXTINF:-1,Uji\nhttps://example.com/a.m3u8\n"
                    "/m404" -> "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n" to "x"
                    else -> "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" to "hello"
                }
                it.getOutputStream().write("${head}Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray(Charsets.ISO_8859_1))
                it.getOutputStream().flush()
            }
        }
    }
}

/** [DOWNLOAD-SHIELD] Server berkas uji (200 KB deterministik): /file (Range 206), /norange, /trunc (putus), /slow (menggantung). */
private const val FILE_N = 200_000
private fun pat(from: Int, to: Int) = ByteArray(to - from) { ((from + it) % 251).toByte() }

private fun serveFile(ss: ServerSocket) = thread(isDaemon = true) {
    while (!ss.isClosed) {
        val c = try { ss.accept() } catch (e: Exception) { return@thread }
        thread(isDaemon = true) {
            c.use {
                val rd = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val path = (rd.readLine() ?: return@use).split(" ").getOrElse(1) { "/" }
                var off = 0
                while (true) {
                    val h = rd.readLine() ?: break
                    if (h.isEmpty()) break
                    if (h.startsWith("range:", ignoreCase = true)) off = h.substringAfter("bytes=").substringBefore("-").toIntOrNull() ?: 0
                }
                val os = it.getOutputStream()
                fun send(head: String, body: ByteArray, declared: Int = body.size) {
                    os.write("${head}Content-Type: application/octet-stream\r\nContent-Length: $declared\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    os.write(body); os.flush()
                }
                when (path) {
                    "/file" -> if (off > 0) send("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $off-${FILE_N - 1}/$FILE_N\r\n", pat(off, FILE_N))
                        else send("HTTP/1.1 200 OK\r\n", pat(0, FILE_N))
                    "/norange" -> send("HTTP/1.1 200 OK\r\n", pat(0, FILE_N))
                    "/trunc" -> send("HTTP/1.1 200 OK\r\n", pat(0, 100_000), FILE_N)
                    "/slow" -> { send("HTTP/1.1 200 OK\r\n", pat(0, 50_000), FILE_N); Thread.sleep(8000) }
                    else -> send("HTTP/1.1 404 Not Found\r\n", ByteArray(1))
                }
            }
        }
    }
}

/** [SHIELD-NP] Server uji kaya: membaca header + badan, memantulkan permintaan (/echo), gzip, galat, pengalihan. */
private fun serveRich(ss: ServerSocket) = thread(isDaemon = true) {
    while (!ss.isClosed) {
        val c = try { ss.accept() } catch (e: Exception) { return@thread }
        thread(isDaemon = true) {
            c.use {
                val rd = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val parts = (rd.readLine() ?: return@use).split(" ")
                val method = parts[0]
                val path = parts.getOrElse(1) { "/" }
                val hdr = LinkedHashMap<String, String>()
                val multi = mutableListOf<String>()
                while (true) {
                    val h = rd.readLine() ?: break
                    if (h.isEmpty()) break
                    val i = h.indexOf(':')
                    val name = h.substring(0, i).lowercase()
                    hdr[name] = h.substring(i + 1).trim()
                    if (name == "x-multi") multi += h.substring(i + 1).trim()
                }
                val n = hdr["content-length"]?.toIntOrNull() ?: 0
                val buf = CharArray(n)
                var got = 0
                while (got < n) { val r = rd.read(buf, got, n - got); if (r < 0) break; got += r }
                val body = String(buf, 0, got)
                val port = ss.localPort
                fun gz(s: String): ByteArray {
                    val bo = java.io.ByteArrayOutputStream()
                    java.util.zip.GZIPOutputStream(bo).use { g -> g.write(s.toByteArray()) }
                    return bo.toByteArray()
                }
                val (head, out) = when (path) {
                    "/gz" -> "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n" to gz("hello-gz")
                    "/gzbig" -> "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n" to gz("x".repeat(5000))
                    "/e500" -> "HTTP/1.1 500 Internal Server Error\r\nX-Resp: a\r\nX-Resp: b\r\n" to "galat-isi".toByteArray()
                    "/big" -> "HTTP/1.1 200 OK\r\n" to "x".repeat(1000).toByteArray()
                    "/p302" -> "HTTP/1.1 302 Found\r\nLocation: /echo\r\n" to ByteArray(0)
                    "/p307" -> "HTTP/1.1 307 Temporary Redirect\r\nLocation: /echo\r\n" to ByteArray(0)
                    "/xhost" -> "HTTP/1.1 302 Found\r\nLocation: http://localhost:$port/echo\r\n" to ByteArray(0)
                    else -> {
                        val echo = "m=$method;len=$n;body=$body;auth=${hdr["authorization"]};ae=${hdr["accept-encoding"]};" +
                            "ct=${hdr["content-type"]};x=${multi.joinToString("|")}"
                        "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" to echo.toByteArray()
                    }
                }
                val os = it.getOutputStream()
                os.write("${head}Content-Length: ${out.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                os.write(out)
                os.flush()
            }
        }
    }
}

fun main() {
    val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    serve(ss)
    val base = "http://127.0.0.1:${ss.localPort}"
    val local: (String) -> Boolean = { it.startsWith(base) }
    ShieldProxy.stop(); ShieldProxy.enabled = false // fail-closed tidak aktif untuk uji dasar

    val ok = ShieldHttp.execute(ShieldHttp.Request("$base/ok", 3000, maxBytes = 64), local)
    check("ok: 200 + isi + Content-Type", ok is ShieldHttp.Result.Ok && ok.code == 200 && String(ok.body) == "hello" && ok.contentType == "text/plain")
    check("ok: durasi tercatat >= 0", ok is ShieldHttp.Result.Ok && ok.ms >= 0 && ok.hops == 0)

    val noBody = ShieldHttp.execute(ShieldHttp.Request("$base/ok", 3000), local)
    check("maxBytes=0: isi tidak dibaca", noBody is ShieldHttp.Result.Ok && noBody.body.isEmpty())

    val r1 = ShieldHttp.execute(ShieldHttp.Request("$base/r1", 3000, maxBytes = 64), local)
    check("pengalihan diikuti (hop=1)", r1 is ShieldHttp.Result.Ok && r1.hops == 1 && r1.finalUrl.endsWith("/ok"))

    val loop = ShieldHttp.execute(ShieldHttp.Request("$base/loop", 3000), local)
    check("loop pengalihan -> Failed", loop is ShieldHttp.Result.Failed)

    val bad = ShieldHttp.execute(ShieldHttp.Request("$base/bad", 3000), local)
    check("pengalihan ke host terlarang diperiksa ulang -> Blocked", bad is ShieldHttp.Result.Blocked)

    val denied = ShieldHttp.execute(ShieldHttp.Request("$base/ok", 3000)) { false }
    check("check menolak -> Blocked", denied is ShieldHttp.Result.Blocked)

    val big = ShieldHttp.execute(ShieldHttp.Request("$base/big", 3000, maxBytes = 100), local)
    check("batas byte dipatuhi", big is ShieldHttp.Result.Ok && big.body.size == 100)

    var logged = ""
    ShieldHttp.onLog = { logged = it }
    ShieldHttp.execute(ShieldHttp.Request("$base/ok", 3000), local)
    check("log: host + durasi, tanpa path", logged.startsWith("shield/http 127.0.0.1 HTTP 200") && !logged.contains("/ok"))
    // [SHIELD-LOG] label pemanggil dan galat tercatat
    ShieldHttp.execute(ShieldHttp.Request("$base/ok", 3000, label = "probe"), local)
    check("log: label probe", logged.startsWith("shield/probe 127.0.0.1 HTTP 200"))
    ShieldHttp.execute(ShieldHttp.Request("http://127.0.0.1:1/x", 1000)) { true }
    check("log: galat dicatat (GAGAL + ms)", logged.startsWith("shield/http 127.0.0.1 GAGAL ") && logged.endsWith("ms"))
    ShieldHttp.execute(ShieldHttp.Request("$base/ok", 3000)) { false }
    check("log: blokir dicatat (DIBLOKIR)", logged.startsWith("shield/http 127.0.0.1 DIBLOKIR"))
    ShieldHttp.onLog = null

    // [IPTV-SHIELD] IptvFetch.load lewat ShieldHttp (check disuntik; tanpa DNS)
    val iptv = IptvFetch.load("$base/m3u") { null }
    check("IptvFetch: playlist 200 -> Ok 1 saluran", iptv is IptvLoad.Ok && iptv.result.channels.size == 1)
    val i404 = IptvFetch.load("$base/m404") { null }
    check("IptvFetch: HTTP 404 -> pesan", i404 is IptvLoad.Fail && i404.message.contains("HTTP 404"))
    val ibad = IptvFetch.load("$base/ok") { "Tautan ditolak: uji." }
    check("IptvFetch: pesan kebijakan dipertahankan", ibad is IptvLoad.Fail && ibad.message == "Tautan ditolak: uji.")
    val ired = IptvFetch.load("$base/bad") { if (it.startsWith(base)) null else "Tautan ditolak: uji." }
    check("IptvFetch: pengalihan ke host terlarang ditolak", ired is IptvLoad.Fail && ired.message == "Tautan ditolak: uji.")

    // [SHIELD-NP] POST, header ganda, gzip, galat, batas ukuran, pengalihan, hanya-HTTPS (server loopback kaya)
    val ss2 = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    serveRich(ss2)
    val b2 = "http://127.0.0.1:${ss2.localPort}"
    val any: (String) -> Boolean = { true }
    fun txt(r: ShieldHttp.Result) = (r as? ShieldHttp.Result.Ok)?.let { String(it.body) }.orEmpty()

    val post = ShieldHttp.execute(
        ShieldHttp.Request("$b2/echo", 3000, mapOf("Content-Type" to "text/x-uji"), maxBytes = 512, method = "POST", body = "halo".toByteArray()), any,
    )
    check("POST: metode + badan + header terkirim", txt(post).contains("m=POST;len=4;body=halo") && txt(post).contains("ct=text/x-uji"))

    val multi = ShieldHttp.execute(ShieldHttp.Request("$b2/echo", 3000, maxBytes = 512, headerLists = mapOf("X-Multi" to listOf("1", "2"))), any)
    check("headerLists: nilai ganda terkirim berurutan", txt(multi).endsWith("x=1|2"))

    val gz = ShieldHttp.execute(ShieldHttp.Request("$b2/gz", 3000, maxBytes = 64, gzip = true), any)
    check("gzip: isi didekode", txt(gz) == "hello-gz")
    val ae = ShieldHttp.execute(ShieldHttp.Request("$b2/echo", 3000, mapOf("Accept-Encoding" to "br"), maxBytes = 512, gzip = true), any)
    check("gzip: Accept-Encoding diatur ShieldHttp (menimpa pemanggil)", txt(ae).contains("ae=gzip;"))
    val gzBomb = ShieldHttp.execute(ShieldHttp.Request("$b2/gzbig", 3000, maxBytes = 100, gzip = true, failOnOverflow = true), any)
    check("gzip: batas dihitung setelah dekode -> Failed", gzBomb is ShieldHttp.Result.Failed && gzBomb.reason == "respons terlalu besar")
    val gzCut = ShieldHttp.execute(ShieldHttp.Request("$b2/gzbig", 3000, maxBytes = 100, gzip = true), any)
    check("gzip: tanpa failOnOverflow dipotong diam-diam (perilaku lama)", gzCut is ShieldHttp.Result.Ok && gzCut.body.size == 100)

    val e500 = ShieldHttp.execute(ShieldHttp.Request("$b2/e500", 3000, maxBytes = 64), any)
    check("galat 500: kode + isi galat + pesan status", e500 is ShieldHttp.Result.Ok && e500.code == 500 && txt(e500) == "galat-isi" && e500.message == "Internal Server Error")
    val xr = (e500 as? ShieldHttp.Result.Ok)?.headers?.entries?.firstOrNull { it.key.equals("x-resp", true) }?.value.orEmpty()
    check("header respons: nilai ganda tersedia", xr.toSet() == setOf("a", "b"))

    val exact = ShieldHttp.execute(ShieldHttp.Request("$b2/big", 3000, maxBytes = 1000, failOnOverflow = true), any)
    check("batas pas (1000/1000) -> Ok penuh", exact is ShieldHttp.Result.Ok && exact.body.size == 1000)
    val over = ShieldHttp.execute(ShieldHttp.Request("$b2/big", 3000, maxBytes = 999, failOnOverflow = true), any)
    check("lebih 1 byte -> Failed", over is ShieldHttp.Result.Failed)

    val h = mapOf("Content-Type" to "text/x-uji", "Authorization" to "Bearer s")
    val p302 = ShieldHttp.execute(ShieldHttp.Request("$b2/p302", 3000, h, maxBytes = 512, method = "POST", body = "halo".toByteArray()), any)
    check("POST + 302 -> GET tanpa badan/Content-Type, Authorization sehost tetap", txt(p302).contains("m=GET;len=0;body=;auth=Bearer s;") && txt(p302).contains("ct=null"))
    val p307 = ShieldHttp.execute(ShieldHttp.Request("$b2/p307", 3000, h, maxBytes = 512, method = "POST", body = "halo".toByteArray()), any)
    check("POST + 307 -> metode dan badan dipertahankan", txt(p307).contains("m=POST;len=4;body=halo") && p307 is ShieldHttp.Result.Ok && p307.hops == 1)
    val xh = ShieldHttp.execute(ShieldHttp.Request("$b2/xhost", 3000, h, maxBytes = 512), any)
    check("pengalihan lintas host membuang Authorization", txt(xh).contains("auth=null;"))

    val plain = ShieldHttp.execute(ShieldHttp.Request("$b2/echo", 3000, maxBytes = 64, httpsOnly = true), any)
    check("httpsOnly: http ditolak sebagai Failed (bukan Blocked)", plain is ShieldHttp.Result.Failed && plain.reason == "hanya HTTPS")

    // Kebijakan Shield tidak boleh memblokir host YouTube yang dipakai NewPipe (DNS disuntik, tanpa jaringan)
    val publicIp = { _: String -> listOf(InetAddress.getByName("142.250.80.46")) }
    for (u in listOf("https://www.youtube.com/youtubei/v1/player", "https://youtubei.googleapis.com/youtubei/v1/search", "https://i.ytimg.com/vi/x/0.jpg", "https://consent.youtube.com/m")) {
        check("kebijakan: $u diizinkan", ShieldProbe.isAllowed(u, publicIp))
    }
    ss2.close()

    // [DOWNLOAD-SHIELD] ShieldHttp.download: baru, lanjut (206), tanpa Range (200), utuh, kebesaran, 404, ditolak, terputus, batal, progres
    val fs = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    serveFile(fs)
    val fb = "http://127.0.0.1:${fs.localPort}"
    val tmp = java.nio.file.Files.createTempDirectory("shd").toFile()
    val full = pat(0, FILE_N)
    fun dl(path: String, f: java.io.File, h: ShieldHttp.Handle = ShieldHttp.Handle(), onP: (Long, Long) -> Unit = { _, _ -> }) =
        ShieldHttp.download("$fb$path", f, FILE_N.toLong(), h, 3000, 3000, onProgress = onP, check = { it.startsWith(fb) })
    fun part(n: String) = java.io.File(tmp, n)

    val f1 = part("a.part")
    val pct = ArrayList<Long>()
    val d1 = dl("/file", f1) { done, _ -> pct.add(done) }
    check("download: unduhan baru utuh", d1 is ShieldHttp.DownloadResult.Done && !d1.resumed && f1.readBytes().contentEquals(full))
    check("download: progres naik dan berakhir di total", pct.isNotEmpty() && pct == pct.sorted() && pct.last() == FILE_N.toLong())
    val f2 = part("b.part"); f2.writeBytes(full.copyOf(70_000))
    val d2 = dl("/file", f2)
    check("download: lanjut dari offset (206)", d2 is ShieldHttp.DownloadResult.Done && d2.resumed && f2.readBytes().contentEquals(full))
    val f3 = part("c.part"); f3.writeBytes(ByteArray(1000) { 9 })
    val d3 = dl("/norange", f3)
    check("download: server abaikan Range (200) -> ulang dari awal", d3 is ShieldHttp.DownloadResult.Done && !d3.resumed && f3.readBytes().contentEquals(full))
    val f4 = part("d.part"); f4.writeBytes(full)
    val d4 = ShieldHttp.download("http://127.0.0.1:1/x", f4, FILE_N.toLong(), check = { true })
    check("download: berkas sudah utuh -> selesai tanpa jaringan", d4 is ShieldHttp.DownloadResult.Done && d4.resumed)
    val f5 = part("e.part"); f5.writeBytes(ByteArray(FILE_N + 5) { 1 })
    val d5 = dl("/file", f5)
    check("download: sisa lebih besar dari target dibuang lalu diunduh ulang", d5 is ShieldHttp.DownloadResult.Done && f5.readBytes().contentEquals(full))
    val f6 = part("f.part")
    val d6 = dl("/nope", f6)
    check("download: HTTP 404 -> Failed", d6 is ShieldHttp.DownloadResult.Failed && d6.reason == "HTTP 404" && !f6.exists())
    val d7 = ShieldHttp.download("$fb/file", part("g.part"), FILE_N.toLong(), check = { false })
    check("download: kebijakan menolak -> Blocked", d7 is ShieldHttp.DownloadResult.Blocked)
    val f8 = part("h.part")
    val d8 = dl("/trunc", f8)
    check("download: terputus -> Failed, sisa disimpan untuk lanjut", d8 is ShieldHttp.DownloadResult.Failed && f8.length() in 1 until FILE_N.toLong())
    val f9 = part("i.part")
    val h9 = ShieldHttp.Handle()
    var r9: ShieldHttp.DownloadResult? = null
    val t9 = thread { r9 = dl("/slow", f9, h9) }
    val until = System.currentTimeMillis() + 5000
    while (f9.length() < 40_000 && System.currentTimeMillis() < until) Thread.sleep(20)
    val c0 = System.currentTimeMillis()
    h9.cancel()
    t9.join(4000)
    check("download: batal memutus bacaan yang menggantung (< 3 dtk)", r9 is ShieldHttp.DownloadResult.Cancelled && System.currentTimeMillis() - c0 < 3000 && f9.length() in 1 until FILE_N.toLong())
    tmp.deleteRecursively()

    // fail-closed: Shield menyala, proxy mati -> tolak sebelum menyentuh jaringan
    ShieldProxy.stop(); ShieldProxy.enabled = true
    val fc = ShieldHttp.execute(ShieldHttp.Request("$base/ok", 3000), local)
    check("fail-closed: Shield nyala + proxy mati -> Blocked", fc is ShieldHttp.Result.Blocked)
    val fcd = ShieldHttp.download("$fb/file", java.io.File(java.nio.file.Files.createTempDirectory("shf").toFile(), "x.part"), FILE_N.toLong(), check = { it.startsWith(fb) })
    check("download: fail-closed -> Blocked", fcd is ShieldHttp.DownloadResult.Blocked)
    ShieldProxy.enabled = true

    ss.close()
    if (fails == 0) println("\nSEMUA LOLOS") else { println("\n$fails GAGAL"); System.exit(1) }
    System.exit(0)
}
