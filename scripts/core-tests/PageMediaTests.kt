import com.molina.core.net.ShieldHttp
import com.molina.core.stream.PageMedia as P

private var fails = 0
private fun check(name: String, ok: Boolean) { if (ok) println("ok   $name") else { fails++; println("GAGAL $name") } }

private fun ok(body: String, url: String = "https://x.example/", code: Int = 200) =
    ShieldHttp.Result.Ok(code, "text/html", body.toByteArray(), url, 5, 0)

private const val PAGE = "https://www.cubmu.com/watch/live-tv/216-indosiar"
private const val MPD = "https://cdn.example.net/ch/manifest.mpd?signature=abc=\\u0026time=1\\u0026device_id=z"
private const val MPD_OK = "https://cdn.example.net/ch/manifest.mpd?signature=abc=&time=1&device_id=z"

fun main() {
    check("unescape", P.unescape("a\\u0026b\\/c&amp;d") == "a&b/c&d")
    val html = P.unescape("""{"manifest":"$MPD","x":"https:\/\/c.example\/live\/index.m3u8"}""")
    val urls = P.findManifestUrls(html)
    check("find: mpd dengan query", urls.firstOrNull() == MPD_OK)
    check("find: m3u8 ter-escape", urls.getOrNull(1) == "https://c.example/live/index.m3u8")
    check("find: tanpa duplikat", P.findManifestUrls("$MPD_OK $MPD_OK").size == 1)
    check("find: tidak ada", P.findManifestUrls("<html>hi</html>").isEmpty())

    check("enc: ContentProtection", P.isEncrypted("<MPD><ContentProtection schemeIdUri=\"urn:mpeg:dash:mp4protection:2011\"/></MPD>"))
    check("enc: MPD bersih", !P.isEncrypted("<MPD><AdaptationSet/></MPD>"))
    check("enc: HLS SAMPLE-AES", P.isEncrypted("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\"\n"))
    check("enc: HLS AES-128 boleh", !P.isEncrypted("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.key\"\n"))
    check("enc: HLS NONE boleh", !P.isEncrypted("#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n"))

    check("hint: widevine terisi", P.hintsDrm("\"widevine_license_url\":\"https://api.x/license\""))
    check("hint: playready null", !P.hintsDrm("\"playready_license_url\":null"))
    check("hint: tidak ada", !P.hintsDrm("<html></html>"))

    val pageHtml = "<script>{\"manifest\":\"$MPD\"}</script>"
    // DRM: manifest ber-ContentProtection
    var r = P.probe(PAGE, http = { req -> if (req.url == PAGE) ok(pageHtml) else ok("<MPD><ContentProtection/></MPD>", req.url) })
    check("probe: Drm", r === P.Result.Drm)
    // Clear: manifest bersih -> URL ter-unescape + Referer
    r = P.probe(PAGE, http = { req -> if (req.url == PAGE) ok(pageHtml) else ok("<MPD/>", req.url) })
    check("probe: Clear url", (r as? P.Result.Clear)?.url == MPD_OK)
    check("probe: Clear Referer", (r as? P.Result.Clear)?.headers?.get("Referer") == "https://www.cubmu.com/")
    // manifest tak terjangkau + petunjuk lisensi -> Drm
    r = P.probe(PAGE, http = { req -> if (req.url == PAGE) ok(pageHtml + "\"widevine_license_url\":\"https://api.x/l\"") else ShieldHttp.Result.Failed("x", 1) })
    check("probe: manifest gagal + petunjuk -> Drm", r === P.Result.Drm)
    // manifest tak terjangkau tanpa petunjuk -> None
    r = P.probe(PAGE, http = { req -> if (req.url == PAGE) ok(pageHtml) else ShieldHttp.Result.Blocked("x") })
    check("probe: manifest gagal tanpa petunjuk -> None", r === P.Result.None)
    // manifest 403 dilewati
    r = P.probe(PAGE, http = { req -> if (req.url == PAGE) ok(pageHtml) else ok("", req.url, 403) })
    check("probe: 403 -> None", r === P.Result.None)
    // halaman gagal / tanpa manifest
    r = P.probe(PAGE, http = { ShieldHttp.Result.Failed("x", 1) })
    check("probe: halaman gagal -> None", r === P.Result.None)
    r = P.probe(PAGE, http = { ok("<html>tanpa media</html>") })
    check("probe: tanpa manifest -> None", r === P.Result.None)

    if (fails > 0) { println("$fails gagal"); kotlin.system.exitProcess(1) } else println("semua lulus")
}
