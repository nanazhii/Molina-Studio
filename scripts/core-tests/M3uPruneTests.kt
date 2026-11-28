// [ERR-PRUNE] uji M3uPrune dan IptvLocal.prune
import com.molina.core.net.IptvLocal
import com.molina.core.net.M3uParser
import com.molina.core.net.M3uPrune
import java.io.File
import java.nio.file.Files

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

fun main() {
    val src = "#EXTM3U\n# ===== Berita =====\n" +
        "#EXTINF:-1 group-title=\"Berita\",A\n#EXTVLCOPT:http-referrer=https://r.example/\nhttps://a.example/a.m3u8\n" +
        "#EXTINF:-1,B\nhttps://b.example/b.m3u8\n" +
        "#EXTINF:-1,C\nhttps://c.example/c.m3u8\n"
    val r1 = M3uPrune.removeUrls(src, setOf("https://b.example/b.m3u8"))
    val want1 = "#EXTM3U\n# ===== Berita =====\n" +
        "#EXTINF:-1 group-title=\"Berita\",A\n#EXTVLCOPT:http-referrer=https://r.example/\nhttps://a.example/a.m3u8\n" +
        "#EXTINF:-1,C\nhttps://c.example/c.m3u8\n"
    check("buang satu entri, sisanya utuh", r1.text == want1 && r1.removed == 1)

    val r2 = M3uPrune.removeUrls(src, setOf("https://a.example/a.m3u8"))
    check("tag entri (EXTINF+EXTVLCOPT) ikut dibuang, komentar grup tetap",
        r2.text == "#EXTM3U\n# ===== Berita =====\n#EXTINF:-1,B\nhttps://b.example/b.m3u8\n#EXTINF:-1,C\nhttps://c.example/c.m3u8\n" && r2.removed == 1)

    val r3 = M3uPrune.removeUrls(src, setOf("https://tidak.example/x"))
    check("URL tak ada -> teks sama persis, removed 0", r3.text == src && r3.removed == 0)
    check("set kosong -> teks sama persis", M3uPrune.removeUrls(src, emptySet()).text == src)

    val withNote = "#EXTM3U\n#EXTINF:-1,X\n# catatan\nhttps://x.example/x\n"
    check("komentar di antara tag dan URL dipertahankan", M3uPrune.removeUrls(withNote, setOf("https://x.example/x")).text == "#EXTM3U\n# catatan\n")

    val crlf = "#EXTM3U\r\n#EXTINF:-1,A\r\nhttps://a.example/a\r\n#EXTINF:-1,B\r\nhttps://b.example/b\r\n"
    check("CRLF terjaga", M3uPrune.removeUrls(crlf, setOf("https://a.example/a")).text == "#EXTM3U\r\n#EXTINF:-1,B\r\nhttps://b.example/b\r\n")

    val dup = "#EXTM3U\n#EXTINF:-1,A\nhttps://d.example/d\n#EXTINF:-1,A2\nhttps://d.example/d\n#EXTINF:-1,Z\nhttps://z.example/z\n"
    val r4 = M3uPrune.removeUrls(dup, setOf("https://d.example/d"))
    check("semua kemunculan URL dibuang", r4.text == "#EXTM3U\n#EXTINF:-1,Z\nhttps://z.example/z\n" && r4.removed == 2)

    val bom = "\uFEFF#EXTM3U\n#EXTINF:-1,A\nhttps://a.example/a\n"
    check("BOM di #EXTM3U terjaga", M3uPrune.removeUrls(bom, setOf("https://a.example/a")).text == "\uFEFF#EXTM3U\n")

    check("hasil dibaca ulang parser: B hilang, A dan C tetap",
        M3uParser.parse(r1.text).channels.map { it.name } == listOf("A", "C"))

    // IptvLocal.prune di berkas nyata
    val base = Files.createTempDirectory("prune").toFile().canonicalFile
    val dir = File(base, "IPTV").also { it.mkdirs() }
    val f = File(dir, "tv.m3u")
    val orig = "#EXTM3U\n#EXTINF:-1,Caf\u00E9\nhttps://a.example/a\n#EXTINF:-1,Buruk\nhttps://b.example/b\n".toByteArray(Charsets.ISO_8859_1)
    f.writeBytes(orig) // 0xE9 tunggal: bukan UTF-8 sah, harus lolos tanpa rusak
    val done = IptvLocal.prune(dir, f, setOf("https://b.example/b")) as? IptvLocal.Prune.Done
    check("prune: Done(1)", done != null && done.removed == 1)
    check("prune: isi baru benar dan byte non-UTF-8 utuh",
        f.readBytes().contentEquals("#EXTM3U\n#EXTINF:-1,Caf\u00E9\nhttps://a.example/a\n".toByteArray(Charsets.ISO_8859_1)))
    check("prune: cadangan .bak = isi asli", File(dir, "tv.m3u.bak").readBytes().contentEquals(orig))
    check("prune: tak ada sisa .tmp", !File(dir, "tv.m3u.tmp").exists())
    check("prune: .bak tidak muncul di daftar playlist", IptvLocal.list(dir)!!.map { it.file.name } == listOf("tv.m3u"))

    val again = IptvLocal.prune(dir, f, setOf("https://b.example/b")) as? IptvLocal.Prune.Done
    check("prune: ulangi = Done(0)", again != null && again.removed == 0)
    check("prune: set kosong = Done(0)", (IptvLocal.prune(dir, f, emptySet()) as? IptvLocal.Prune.Done)?.removed == 0)

    val out = File(base, "lain.m3u").also { it.writeText("#EXTM3U\n#EXTINF:-1,X\nhttps://x.example/x\n") }
    check("prune: berkas di luar folder IPTV ditolak", IptvLocal.prune(dir, out, setOf("https://x.example/x")) is IptvLocal.Prune.Fail)
    check("prune: berkas di luar folder tidak berubah", out.readText().contains("https://x.example/x"))
    val txt = File(dir, "catatan.txt").also { it.writeText("x") }
    check("prune: ekstensi bukan m3u ditolak", IptvLocal.prune(dir, txt, setOf("x")) is IptvLocal.Prune.Fail)
    check("prune: berkas tidak ada ditolak", IptvLocal.prune(dir, File(dir, "hilang.m3u"), setOf("x")) is IptvLocal.Prune.Fail)

    val uv = M3uParser.parse("#EXTM3U\n#EXTINF:-1 x-unverified=\"1\" group-title=\"Nasional\",SCTV\nhttps://a.example/a.m3u8\n#EXTINF:-1,RCTI\nhttps://b.example/b.m3u8\n").channels // [UNVERIFIED-BADGE]
    check("unverified: atribut x-unverified terbaca, tidak bocor ke entri berikutnya", uv.size == 2 && uv[0].unverified && !uv[1].unverified)

    // [IPTV-BUNDLED]
    val okB = IptvLocal.loadBundled("#EXTM3U\n#EXTINF:-1,RCTI\nhttps://b.example/b.m3u8\n".toByteArray())
    check("bawaan: playlist sah terbaca", okB is com.molina.core.net.IptvLoad.Ok && okB.result.channels.size == 1)
    check("bawaan: playlist kosong ditolak", IptvLocal.loadBundled("#EXTM3U\n".toByteArray()) is com.molina.core.net.IptvLoad.Fail)
    check("bawaan: terlalu besar ditolak", IptvLocal.loadBundled(ByteArray(com.molina.core.net.IptvFetch.MAX_BYTES + 1)) is com.molina.core.net.IptvLoad.Fail)
    val assetFile = File("app/src/main/assets/molina/iptv/id.verified.m3u")
    val assetRes = if (assetFile.isFile) IptvLocal.loadBundled(assetFile.readBytes()) else null
    check("bawaan: aset feed Molina ada dan terbaca", assetRes is com.molina.core.net.IptvLoad.Ok)
    val assetNames = (assetRes as? com.molina.core.net.IptvLoad.Ok)?.result?.channels?.map { it.name }.orEmpty()
    check("bawaan: aset memuat >= 20 saluran", assetNames.size >= 20) // [IPTV-CURATED]
    // [IPTV-TRUSTED-ONLY] Aset bawaan hanya memuat stream HTTPS berdomain (tanpa HTTP, IP mentah, token/kredensial) dan bukan dari host yang ditolak.
    val deniedHosts = setOf("rplus.inisemar.my.id", "gist.githubusercontent.com")
    fun untrusted(chs: List<Pair<String, String>>): List<String> = chs.filter { (_, u) ->
        com.molina.core.net.IptvTrust.of(u) == com.molina.core.net.IptvTrust.Level.INSECURE ||
            (runCatching { java.net.URI(u.trim()).host }.getOrNull()?.lowercase() ?: "") in deniedHosts
    }.map { it.first }
    val assetPairs = (assetRes as? com.molina.core.net.IptvLoad.Ok)?.result?.channels?.map { it.name to it.url }.orEmpty()
    val badAsset = untrusted(assetPairs)
    check("bawaan: tidak ada saluran INSECURE atau dari host ditolak (pelanggar: $badAsset)", badAsset.isEmpty())
    check("bawaan: penjaga menangkap HTTP, IP mentah, dan host ditolak", untrusted(listOf(
        "A" to "http://1.2.3.4/a.m3u8", "B" to "https://rplus.inisemar.my.id/x.m3u8", "C" to "https://x.example/a.m3u8",
    )) == listOf("A", "B"))
    // [IPTV-NAT-HTTP] Daftar opsional channel nasional HTTP: terpisah dari feed tepercaya, sah, dan tepat empat nama.
    val natFile = File("app/src/main/assets/molina/iptv/nasional-http.m3u")
    val natRes = if (natFile.isFile) IptvLocal.loadBundled(natFile.readBytes()) else null
    val natNames = (natRes as? com.molina.core.net.IptvLoad.Ok)?.result?.channels?.map { it.name }.orEmpty()
    check("nasional-http: aset ada dan terbaca", natRes is com.molina.core.net.IptvLoad.Ok)
    check("nasional-http: tepat ANTV, Indosiar, SCTV, TVRI Sport", natNames.sorted() == listOf("ANTV", "Indosiar", "SCTV", "TVRI Sport"))
    check("nasional-http: tidak tumpang tindih dengan feed tepercaya", natNames.none { it in assetNames })
    base.deleteRecursively()
    if (fails > 0) { println("$fails GAGAL"); kotlin.system.exitProcess(1) }
    println("semua lulus")
}
