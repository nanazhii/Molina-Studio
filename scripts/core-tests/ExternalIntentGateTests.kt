import com.molina.core.common.ExternalIntentGate as G
import kotlin.system.exitProcess

private var runs = 0
private var fails = 0
private fun check(name: String, ok: Boolean) { runs++; if (!ok) { fails++; println("GAGAL: $name") } }

fun main() {
    val tok = "0123456789abcdef0123456789abcdef"
    G.tokenProvider = null
    check("tanpa penyedia token: tidak tepercaya", !G.isTrusted(tok) && !G.isTrusted(null))
    G.tokenProvider = { tok }
    check("token cocok tepercaya", G.isTrusted(tok))
    check("token salah/null/kosong ditolak", !G.isTrusted(tok.reversed()) && !G.isTrusted(null) && !G.isTrusted(""))
    G.tokenProvider = { throw IllegalStateException("x") }
    check("penyedia melempar: tidak tepercaya", !G.isTrusted(tok))
    G.tokenProvider = { "pendek" }
    check("token pendek ditolak", !G.isTrusted("pendek"))

    fun net(vararg e: String?) = G.requestsNetwork(e.toList())
    check("path lokal bukan jaringan", !net("/sdcard/a.mp4") && !net("/storage/emulated/0/Movies/x y.mkv"))
    check("file dan content bukan jaringan", !net("file:///sdcard/a.mp4") && !net("content://media/external/video/1") && !net("FILE:///x"))
    check("http/https/rtmp/rtsp jaringan", net("https://a/b.mp4") && net("http://a") && net("rtmp://a/x") && net("rtmps://a") && net("rtsp://a/x"))
    check("huruf besar dan spasi depan tetap terdeteksi", net(" HTTP://A/b") && net("\tHttps://a"))
    check("skema pembungkus mpv jaringan", net("ytdl://https://x") && net("cache:http://x") && net("lavf://http://x") && net("ftp://x") && net("tcp://1.2.3.4:80"))
    check("file:// yang memuat tulisan http tetap lokal", !net("file:///sdcard/http://y"))
    check("memory playlist lokal bukan jaringan", !net("memory://#EXTM3U\n/sdcard/a.mp4\nfile:///sdcard/b.mp4\n"))
    check("memory playlist dengan satu baris http jaringan", net("memory://#EXTM3U\n/sdcard/a.mp4\nhttps://x/y.m3u8\n"))
    check("memory playlist CRLF", net("memory://#EXTM3U\r\nhttp://x\r\n"))
    check("salah satu entri jaringan cukup", net("/sdcard/a.mp4", null, "https://x"))
    check("daftar kosong dan null aman", !G.requestsNetwork(emptyList()) && !net(null) && !net(""))

    check("firstRemote mengambil yang pertama", G.firstRemote(listOf("/a", "https://one/x", "http://two")) == "https://one/x")
    check("firstRemote null bila lokal", G.firstRemote(listOf("/a", "file:///b")) == null)

    check("host biasa", G.displayHost("https://example.com/a?b=c") == "example.com")
    check("host tanpa userinfo dan port", G.displayHost("https://user:pw@example.com:8443/a") == "example.com")
    check("host IPv6", G.displayHost("http://[2001:db8::1]:80/x") == "[2001:db8::1]")
    check("host dipotong 80", G.displayHost("https://" + "a".repeat(200) + "/x").length == 80)
    check("karakter kontrol dibuang", G.displayHost("https://ex\u0007am\nple.com/x") == "example.com")
    check("bukan URL kosong", G.displayHost("/sdcard/a.mp4") == "" && G.displayHost(null) == "")

    println("ExternalIntentGateTests: ${runs - fails} lulus, $fails gagal")
    if (fails > 0) exitProcess(1)
}
