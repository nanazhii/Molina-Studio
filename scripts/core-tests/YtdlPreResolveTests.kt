import com.molina.core.net.ShieldHttp
import com.molina.core.stream.YtdlPreResolve as P

private var fails = 0
private fun check(name: String, ok: Boolean) { if (ok) println("ok   $name") else { fails++; println("GAGAL $name") } }

private fun ok(final: String, body: String = "", hdr: Map<String, List<String>> = emptyMap(), code: Int = 200) =
    ShieldHttp.Result.Ok(code, "text/html", body.toByteArray(), final, 5, 0, hdr)

fun main() {
    val s = "https://www.facebook.com/share/r/1E8TBSYQer/"
    val reel = "https://web.facebook.com/reel/2658118794617813/"
    check("share fb", P.isShareLink(s))
    check("share fb.watch", P.isShareLink("https://fb.watch/abc/"))
    check("bukan share: reel", !P.isShareLink(reel))
    check("bukan share: host lain", !P.isShareLink("https://evil.example/share/r/1"))
    check("bukan share: facebook.com.evil", !P.isShareLink("https://facebook.com.evil.example/share/r/1"))
    check("accept reel", P.accept(s, reel))
    check("tolak host lain", !P.accept(s, "https://evil.example/reel/1"))
    check("tolak skema aneh", !P.accept(s, "javascript:alert(1)"))
    check("tolak sama", !P.accept(s, s))
    check("tolak masih share", !P.accept(s, "https://m.facebook.com/share/v/9/"))
    check("header refresh", P.refreshTarget("0;url=$reel", s) == reel)
    check("tanpa refresh", P.refreshTarget(null, s) == null)
    check("resolve: Location", P.resolve(s, http = { _, _ -> ok(reel) }) == reel)
    check("resolve: header", P.resolve(s, http = { _, _ -> ok(s, hdr = mapOf("refresh" to listOf("0; url=$reel"))) }) == reel)
    check("resolve: tidak berubah -> null", P.resolve(s, http = { _, _ -> ok(s, "<html></html>") }) == null)
    check("resolve: Blocked -> null", P.resolve(s, http = { _, _ -> ShieldHttp.Result.Blocked("x") }) == null)
    check("resolve: Failed -> null", P.resolve(s, http = { _, _ -> ShieldHttp.Result.Failed("x", 1) }) == null)
    check("resolve: bukan share tidak memanggil http", P.resolve(reel, http = { _, _ -> error("tidak boleh") }) == null)
    check("resolve: Refresh host asing ditolak", P.resolve(s, http = { _, _ -> ok(s, hdr = mapOf("Refresh" to listOf("0;url=https://evil.example/x"))) }) == null)
    check("resolve: query dibuang", P.resolve(s, http = { _, _ -> ok("$reel?rdid=abc&share_url=x") }) == reel)
    if (fails > 0) { println("$fails GAGAL"); kotlin.system.exitProcess(1) }
    println("semua lulus")
}
