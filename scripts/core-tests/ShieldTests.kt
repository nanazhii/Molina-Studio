import com.molina.core.common.NetworkPolicy
import com.molina.core.common.ShieldPolicy
import com.molina.core.common.ShieldProxy
import com.molina.core.common.ShieldStats
import com.molina.core.common.StreamPin
import com.molina.core.common.TrackerList
import com.molina.core.common.UrlPolicy
import com.molina.core.common.YtdlpCommand
import com.molina.cinema.MpvLoad
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun ip(s: String): InetAddress = InetAddress.getByName(s) // hanya untuk literal IP (tanpa DNS)
private fun allowed(s: String, lan: Boolean = false) = ShieldPolicy.checkAddress(ip(s), lan) is UrlPolicy.Verdict.Allowed
private fun reasonOf(s: String, lan: Boolean = false): UrlPolicy.Reason? =
    (ShieldPolicy.checkAddress(ip(s), lan) as? UrlPolicy.Verdict.Blocked)?.reason

private const val ISO = "ISO-8859-1"

/** Kirim [req] mentah ke proxy, kembalikan baris status (atau "" bila koneksi ditutup tanpa jawaban). */
private fun status(port: Int, req: String, timeoutMs: Int = 5000): String {
    Socket("127.0.0.1", port).use { s ->
        s.soTimeout = timeoutMs
        s.getOutputStream().write(req.toByteArray(charset(ISO)))
        s.getOutputStream().flush()
        val sb = StringBuilder()
        val buf = ByteArray(2048)
        try {
            while (true) {
                val n = s.getInputStream().read(buf)
                if (n < 0) break
                sb.append(String(buf, 0, n, charset(ISO)))
                if (sb.contains("\r\n")) break
            }
        } catch (e: SocketTimeoutException) { }
        return sb.toString().substringBefore("\r\n")
    }
}

fun main() {
    // ---------------------------------------------------------------- ShieldPolicy.checkAddress
    for (a in listOf("127.0.0.1", "127.255.255.254", "0.0.0.0", "169.254.169.254", "224.0.0.1", "255.255.255.255", "::1", "::")) {
        check("checkAddress: $a ditolak (loopback/metadata/multicast)", !allowed(a) && !allowed(a, true))
    }
    for (a in listOf("10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1")) {
        check("checkAddress: $a privat ditolak, boleh bila LAN diizinkan", !allowed(a) && allowed(a, true))
    }
    check("checkAddress: CGNAT 100.64.0.1 dan 100.127.255.255 diperlakukan privat", !allowed("100.64.0.1") && !allowed("100.127.255.255"))
    check("checkAddress: batas CGNAT 100.63.255.255 dan 100.128.0.1 publik", allowed("100.63.255.255") && allowed("100.128.0.1"))
    check("checkAddress: batas privat 172.15.255.255 dan 172.32.0.1 publik", allowed("172.15.255.255") && allowed("172.32.0.1"))
    check("checkAddress: publik 8.8.8.8 dan 1.1.1.1 lolos", allowed("8.8.8.8") && allowed("1.1.1.1"))
    check("checkAddress: IPv4-mapped ::ffff:127.0.0.1 ditolak", !allowed("::ffff:127.0.0.1") && !allowed("::ffff:7f00:1"))
    check("checkAddress: IPv4-mapped ::ffff:10.0.0.1 privat, ::ffff:8.8.8.8 lolos", !allowed("::ffff:10.0.0.1") && allowed("::ffff:8.8.8.8"))
    check("checkAddress: NAT64 ke loopback 64:ff9b::7f00:1 ditolak", !allowed("64:ff9b::7f00:1") && !allowed("64:ff9b::7f00:1", true))
    check("checkAddress: NAT64 ke privat 64:ff9b::a00:1 ikut aturan LAN", !allowed("64:ff9b::a00:1") && allowed("64:ff9b::a00:1", true))
    check("checkAddress: 6to4 ke loopback 2002:7f00:1:: ditolak", !allowed("2002:7f00:1::") && !allowed("2002:7f00:1::", true))
    check("checkAddress: link-local fe80::1 dan multicast ff02::1 ditolak", !allowed("fe80::1", true) && !allowed("ff02::1", true))
    check("checkAddress: ULA fc00::1/fd00::1 privat", !allowed("fd00::1") && allowed("fd00::1", true) && !allowed("fc00::1"))
    check("checkAddress: IPv6 publik 2606:4700:4700::1111 lolos", allowed("2606:4700:4700::1111"))
    check("checkAddress: alasan loopback vs privat benar", reasonOf("127.0.0.1") == UrlPolicy.Reason.LOOPBACK && reasonOf("10.0.0.1") == UrlPolicy.Reason.PRIVATE_ADDRESS)

    // ---------------------------------------------------------------- portAllowed & TrackerList
    check("portAllowed: layanan sensitif ditolak", listOf(22, 25, 445, 3306, 3389, 5432, 6379).none { ShieldPolicy.portAllowed(it) })
    check("portAllowed: web/stream lolos, di luar rentang ditolak", ShieldPolicy.portAllowed(80) && ShieldPolicy.portAllowed(443) && ShieldPolicy.portAllowed(1935) && !ShieldPolicy.portAllowed(0) && !ShieldPolicy.portAllowed(65536))
    check("TrackerList: sufiks domain cocok, domain mirip tidak", TrackerList.isTracker("ads.doubleclick.net") && TrackerList.isTracker("DoubleClick.net.") && !TrackerList.isTracker("notdoubleclick.net") && !TrackerList.isTracker("doubleclick.net.example.org"))
    TrackerList.allowed = setOf("doubleclick.net")
    check("TrackerList: pengecualian pengguna menang", !TrackerList.isTracker("ads.doubleclick.net"))
    TrackerList.allowed = emptySet()
    TrackerList.enabled = false
    check("TrackerList: sakelar mati = tidak ada pelacak", !TrackerList.isTracker("ads.doubleclick.net"))
    TrackerList.enabled = true

    // ---------------------------------------------------------------- StreamPin
    check("hostOf: userinfo, port, IPv6, query berisi @", StreamPin.hostOf("rtmp://u:p@h.example:1935/a") == "h.example" && StreamPin.hostOf("rtsp://[2001:db8::1]:554/x") == "2001:db8::1" && StreamPin.hostOf("rtmp://h.example/a?x=a@b") == "h.example" && StreamPin.hostOf("rtmp:///a") == null && StreamPin.hostOf("nourl") == null)
    var calls = 0
    val rebinding: (String) -> List<InetAddress> = { calls++; if (calls == 1) listOf(ip("8.8.8.8")) else listOf(ip("127.0.0.1")) }
    val r1 = StreamPin.resolve("rtmp://live.example.com:1935/app/stream?k=1", false, rebinding)
    check("pin: rtmp dipin ke IP lolos, port/path/query utuh", r1 is StreamPin.Result.Pinned && r1.url == "rtmp://8.8.8.8:1935/app/stream?k=1" && r1.host == "live.example.com")
    check("pin: DNS rebinding (jawaban ke-2 = loopback) tak berpengaruh; DNS hanya sekali", calls == 1 && (r1 as StreamPin.Result.Pinned).url.contains("8.8.8.8"))
    val r2 = StreamPin.resolve("rtsp://cam.example.com/s", false) { listOf(ip("10.0.0.5"), ip("8.8.4.4")) }
    check("pin: jawaban campuran memilih alamat yang lolos", r2 is StreamPin.Result.Pinned && r2.ip == "8.8.4.4" && r2.url == "rtsp://8.8.4.4/s")
    val r3 = StreamPin.resolve("rtmp://x.example.com/a", false) { listOf(ip("10.0.0.5")) }
    check("pin: semua alamat privat = ditolak PRIVATE_ADDRESS", r3 is StreamPin.Result.Blocked && r3.verdict.reason == UrlPolicy.Reason.PRIVATE_ADDRESS)
    check("pin: privat boleh bila LAN diizinkan", StreamPin.resolve("rtmp://x.example.com/a", true) { listOf(ip("10.0.0.5")) } is StreamPin.Result.Pinned)
    val r4 = StreamPin.resolve("rtmp://evil.example.com/a", true) { listOf(ip("127.0.0.1")) }
    check("pin: loopback selalu ditolak, juga saat LAN diizinkan", r4 is StreamPin.Result.Blocked && r4.verdict.reason == UrlPolicy.Reason.LOOPBACK)
    val r5 = StreamPin.resolve("rtsp://cam.example.com:8554/s", false) { listOf(ip("2606:4700:4700::1111")) }
    check("pin: IPv6 dikurung [] dan port dipertahankan", r5 is StreamPin.Result.Pinned && r5.url.startsWith("rtsp://[") && r5.url.endsWith("]:8554/s"))
    val r6 = StreamPin.resolve("rtsp://cam.example.com/s", false) { listOf(ip("2606:4700:4700::1111"), ip("8.8.8.8")) }
    check("pin: IPv4 didahulukan dari IPv6", r6 is StreamPin.Result.Pinned && r6.ip == "8.8.8.8")
    val r7 = StreamPin.resolve("rtmps://x.example.com/a", false) { listOf(ip("8.8.8.8")) }
    check("pin: rtmps tidak ditulis ulang (TLS terikat nama host)", r7 is StreamPin.Result.Unchanged && r7.url == "rtmps://x.example.com/a")
    val r8 = StreamPin.resolve("rtmps://x.example.com/a", false) { listOf(ip("8.8.8.8"), ip("127.0.0.1")) }
    check("pin: rtmps ditolak bila SALAH SATU alamat terlarang", r8 is StreamPin.Result.Blocked)
    var lit = 0
    val r9 = StreamPin.resolve("rtmp://8.8.8.8/live", false) { lit++; emptyList() }
    check("pin: host IP literal tanpa DNS dan tanpa penulisan ulang", r9 is StreamPin.Result.Unchanged && lit == 0)
    check("pin: IP literal privat ditolak", StreamPin.resolve("rtmp://192.168.1.9/live", false) { emptyList() } is StreamPin.Result.Blocked)
    val r10 = StreamPin.resolve("rtmp://x.example.com/a", false) { throw java.net.UnknownHostException("x") }
    check("pin: gagal resolve = ditolak (fail-closed)", r10 is StreamPin.Result.Blocked && r10.verdict.reason == UrlPolicy.Reason.MALFORMED)
    check("pin: jawaban DNS kosong = ditolak", StreamPin.resolve("rtmp://x.example.com/a", false) { emptyList() } is StreamPin.Result.Blocked)
    check("pin: skema tak sah ditolak UrlPolicy sebelum DNS", StreamPin.resolve("file:///etc/passwd", false) { error("tak boleh dipanggil") } is StreamPin.Result.Blocked)
    check("rewriteHost: fragmen dan tanpa path", StreamPin.rewriteHost("rtsp://h.example:554#f", "1.2.3.4") == "rtsp://1.2.3.4:554#f" && StreamPin.rewriteHost("rtmp://h.example", "1.2.3.4") == "rtmp://1.2.3.4" && StreamPin.rewriteHost("rtmp://u@h.example/a", "1.2.3.4") == null)

    // ---------------------------------------------------------------- yt-dlp lewat Shield (jalur resolver)
    val plain = YtdlpCommand.prootArgs("/r", "/usr/local/bin/yt-dlp", listOf("-J", "--", "https://example.com/v"))
    check("ytdlp: tanpa proxy tidak ada env proxy", plain.none { it.contains("_proxy=") })
    val px = "http" + "://molina:abc@127.0.0.1:4567"
    val withPx = YtdlpCommand.prootArgs("/r", "/usr/local/bin/yt-dlp", listOf("-J", "--", "https://example.com/v"), px)
    check("ytdlp: proxy dipasang sebagai env sebelum python3", "http_proxy=$px" in withPx && "https_proxy=$px" in withPx && withPx.indexOf("http_proxy=$px") < withPx.indexOf(YtdlpCommand.GUEST_PYTHON) && withPx.indexOf("/usr/bin/env") < withPx.indexOf("http_proxy=$px"))
    check("ytdlp: argumen yt-dlp tetap di akhir dan urut", withPx.takeLast(3) == listOf("-J", "--", "https://example.com/v"))

    // ---------------------------------------------------------------- MpvLoad: fail-closed, opsi proxy, gerbang basi
    ShieldProxy.stop(); ShieldProxy.enabled = true
    check("failClosed: menyala tapi tidak berjalan = true", ShieldProxy.failClosed())
    check("shieldGate: https/http/playlist memori ditolak saat Shield mati", MpvLoad.shieldGate("https://example.com/a.mp4") != null && MpvLoad.shieldGate("http://example.com/a") != null && MpvLoad.shieldGate("memory://#EXTM3U\n#EXTINF:-1,A\nhttps://x.example/a\n") != null)
    check("shieldGate: berkas lokal dan rtmp tidak terpengaruh", MpvLoad.shieldGate("file:///sdcard/a.mp4") == null && MpvLoad.shieldGate("rtmp://x.example/a") == null)
    val blockedCmd = MpvLoad.loadfileArgs("https://example.com/a.mp4", "replace", emptyMap())
    check("loadfileArgs: Shield mati -> show-text, bukan loadfile", blockedCmd[0] == "show-text" && blockedCmd[1].startsWith("Diblokir:"))
    ShieldProxy.enabled = false
    check("shieldGate: pengguna mematikan Shield = tidak diblokir (pilihan sadar)", !ShieldProxy.failClosed() && MpvLoad.shieldGate("https://example.com/a.mp4") == null)
    ShieldProxy.enabled = true
    check("start Shield", ShieldProxy.start { false })
    val pl = MpvLoad.loadfileArgs("https://example.com/list.m3u8", "replace", emptyMap())
    check("loadfileArgs: entri m3u8 memasang proxy segar", pl[0] == "loadfile" && pl.last().contains("http_proxy") && pl.last().contains("stream-lavf-o="))
    val mp4 = MpvLoad.loadfileArgs("https://example.com/a.mp4", "replace", emptyMap())
    check("loadfileArgs: http biasa: whitelist + proxy", mp4.last().contains("protocol_whitelist") && mp4.last().contains("http_proxy"))

    // ---------------------------------------------------------------- ShieldProxy hidup (loopback): autentikasi, kebijakan, deadline
    val url = ShieldProxy.proxyUrl()!!
    val port = url.substringAfterLast(':').toInt()
    val cred = url.substringAfter("://").substringBefore('@')
    val auth = "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString(cred.toByteArray())
    val bad = "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString("molina:salah".toByteArray())
    fun get(target: String, extra: String = "") = "GET $target HTTP/1.1\r\nHost: x\r\n$extra\r\n"
    check("proxy: tanpa kredensial -> 407", status(port, get("http://8.8.8.8/")).contains(" 407 "))
    check("proxy: token salah -> 407", status(port, get("http://8.8.8.8/", bad + "\r\n")).contains(" 407 "))
    val before = ShieldStats.blockedCount.value
    check("proxy: GET ke loopback -> 403", status(port, get("http://127.0.0.1:$port/", auth + "\r\n")).contains(" 403 "))
    check("proxy: GET ke metadata cloud 169.254.169.254 -> 403", status(port, get("http://169.254.169.254/latest/meta-data/", auth + "\r\n")).contains(" 403 "))
    check("proxy: CONNECT ke port SMTP 8.8.8.8:25 -> 403", status(port, "CONNECT 8.8.8.8:25 HTTP/1.1\r\nHost: x\r\n$auth\r\n\r\n").contains(" 403 "))
    check("proxy: CONNECT ke 10.0.0.1:443 (privat) -> 403", status(port, "CONNECT 10.0.0.1:443 HTTP/1.1\r\nHost: x\r\n$auth\r\n\r\n").contains(" 403 "))
    check("proxy: pelacak ads.doubleclick.net -> 403 sebelum DNS", status(port, get("http://ads.doubleclick.net/x", auth + "\r\n")).contains(" 403 "))
    check("proxy: pemblokiran tercatat di statistik", ShieldStats.blockedCount.value >= before + 5)
    check("proxy: POST -> 405", status(port, "POST http://8.8.8.8/ HTTP/1.1\r\nHost: x\r\n$auth\r\nContent-Length: 0\r\n\r\n").contains(" 405 "))
    check("proxy: badan permintaan (Content-Length>0) -> 400", status(port, "GET http://8.8.8.8/ HTTP/1.1\r\nHost: x\r\n$auth\r\nContent-Length: 5\r\n\r\n").contains(" 400 "))
    check("proxy: bentuk-asal (bukan absolute-form) -> 400", status(port, "GET /path HTTP/1.1\r\nHost: x\r\n$auth\r\n\r\n").contains(" 400 "))
    check("proxy: baris permintaan rusak -> 400", status(port, "GARBAGE\r\n\r\n").contains(" 400 "))
    check("proxy: nama host dengan CRLF tak lolos (400/403)", status(port, "CONNECT 8.8.8.8:443\r\nX: y HTTP/1.1\r\n$auth\r\n\r\n").let { it.contains(" 400 ") || it.contains(" 403 ") || it.isEmpty() })

    // readHead: kiriman lambat (1 byte/detik) harus diputus oleh batas WAKTU TOTAL (15 dtk), bukan menggantung selamanya
    val t0 = System.nanoTime()
    var closedAfterMs = -1L
    Socket("127.0.0.1", port).use { s ->
        s.soTimeout = 2000
        val out = s.getOutputStream()
        val head = "GET http://8.8.8.8/ HTTP/1.1\r\nX-Slow: " + "a".repeat(200)
        try {
            for (b in head.toByteArray()) {
                out.write(b.toInt()); out.flush()
                Thread.sleep(1000)
                if ((System.nanoTime() - t0) / 1_000_000 > 25_000) break
                // bila server menutup, read mengembalikan -1 atau menulis gagal
                s.soTimeout = 1
                val r = try { s.getInputStream().read() } catch (e: SocketTimeoutException) { 0 }
                if (r != 0) { closedAfterMs = (System.nanoTime() - t0) / 1_000_000; break }
            }
        } catch (e: java.io.IOException) {
            closedAfterMs = (System.nanoTime() - t0) / 1_000_000
        }
    }
    check("readHead: kiriman lambat diputus ≈15 dtk (terukur ${closedAfterMs} ms)", closedAfterMs in 14_000..21_000)

    // gerbang opsi basi: setelah opsi global ditandai, restart Shield (port/token baru) harus menolak sampai Refresh
    MpvLoad.markGlobalOptionsApplied()
    check("shieldGate: proxy sama dengan saat init -> boleh", MpvLoad.shieldGate("https://example.com/a.mp4") == null)
    ShieldProxy.stop()
    check("start ulang Shield", ShieldProxy.start { false })
    val stale = MpvLoad.shieldGate("https://example.com/a.mp4")
    check("shieldGate: proxy berubah sejak init -> minta Refresh Pemutar", stale != null && stale.contains("Refresh"))
    ShieldProxy.stop()

    if (fails == 0) println("\nSEMUA LOLOS") else { println("\n$fails GAGAL"); System.exit(1) }
    System.exit(0)
}
