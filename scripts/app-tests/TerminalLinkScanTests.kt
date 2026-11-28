import com.molina.studio.terminal.TerminalLinkScan

private var fails = 0
private var runs = 0
private fun <T> eq(name: String, got: T, want: T) { runs++; if (got != want) { fails++; println("GAGAL: $name -> $got (seharusnya $want)") } }
private fun ex(t: String, max: Int = 30) = TerminalLinkScan.extract(t, max)

fun main() {
    eq("kosong", ex(""), emptyList<String>())
    eq("tanpa tautan", ex("user@host:~$ ls -la\ntotal 4"), emptyList<String>())
    eq("satu https", ex("buka https://example.com/a?b=1 sekarang"), listOf("https://example.com/a?b=1"))
    eq("titik akhir kalimat", ex("lihat https://example.com/x."), listOf("https://example.com/x"))
    eq("koma dan titik koma", ex("https://a.io/x, lalu https://b.io/y;"), listOf("https://b.io/y", "https://a.io/x"))
    eq("kurung pembungkus", ex("(https://example.com/doc)"), listOf("https://example.com/doc"))
    eq("kurung bagian URL", ex("https://id.wikipedia.org/wiki/Bali_(pulau)"), listOf("https://id.wikipedia.org/wiki/Bali_(pulau)"))
    eq("kurung siku", ex("[https://example.com/a]"), listOf("https://example.com/a"))
    eq("tanda kutip", ex("curl \"https://example.com/a\" 'https://example.com/b'"), listOf("https://example.com/b", "https://example.com/a"))
    eq("terbaru dulu", ex("http://a.com\nhttp://b.com\nhttp://c.com"), listOf("http://c.com", "http://b.com", "http://a.com"))
    eq("duplikat: kemunculan terakhir menang", ex("http://a.com\nhttp://b.com\nhttp://a.com"), listOf("http://a.com", "http://b.com"))
    eq("batas max", ex("http://a.com http://b.com http://c.com", 2), listOf("http://c.com", "http://b.com"))
    eq("max nol", ex("http://a.com", 0), emptyList<String>())
    eq("skema huruf besar", ex("HTTPS://Example.com/P"), listOf("HTTPS://Example.com/P"))
    eq("rtsp rtmp", ex("rtsp://cam.local:554/s rtmp://x.io/live"), listOf("rtmp://x.io/live", "rtsp://cam.local:554/s"))
    eq("skema tak didukung", ex("ftp://a.com file:///sdcard/x javascript://x"), emptyList<String>())
    eq("bukan awal kata", ex("xhttps://a.com"), emptyList<String>())
    eq("skema tanpa host", ex("http:// dan https://"), emptyList<String>())
    eq("host diawali slash", ex("http:///etc/passwd"), emptyList<String>())
    eq("IPv4 dan port", ex("http://192.168.1.5:8080/x"), listOf("http://192.168.1.5:8080/x"))
    eq("IPv6", ex("http://[::1]:3000/"), listOf("http://[::1]:3000/"))
    eq("userinfo", ex("https://user:pw@host.io/p"), listOf("https://user:pw@host.io/p"))
    eq("fragment dan query", ex("https://a.io/p?x=1&y=2#frag"), listOf("https://a.io/p?x=1&y=2#frag"))
    eq("pemisah tab dan CR", ex("https://a.io/1\thttps://b.io/2\r\n"), listOf("https://b.io/2", "https://a.io/1"))
    eq("terlalu panjang", ex("https://a.io/" + "x".repeat(2100)), emptyList<String>())
    eq("panjang batas", ex("https://a.io/" + "x".repeat(2000)).size, 1)
    eq("karakter kontrol memotong URL", ex("https://a.io/p\u0000junk"), listOf("https://a.io/p"))
    eq("yt-dlp", ex("[youtube] Extracting URL: https://www.youtube.com/watch?v=abc123"), listOf("https://www.youtube.com/watch?v=abc123"))
    eq("clean null", TerminalLinkScan.clean("http://"), null)
    println("Uji TerminalLinkScan: $runs pemeriksaan, $fails gagal")
    if (fails > 0) kotlin.system.exitProcess(1)
}
