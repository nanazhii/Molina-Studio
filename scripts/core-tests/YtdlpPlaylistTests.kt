import com.molina.core.download.YtdlpCommand
import com.molina.core.stream.PlaylistPlan
import com.molina.core.stream.RollingQueue
import com.molina.core.stream.StreamEntry
import com.molina.core.stream.YtdlpParse

// [PAGE-PLAYLIST] Uji JVM murni: playlist halaman yt-dlp (video multi-bagian) -> antrean bergulir.
private var fails = 0
private fun check(name: String, ok: Boolean) { if (ok) println("ok   $name") else { fails++; println("GAGAL $name") } }

private const val O = "https://www.bilibili.com/video/BV1dQ1AzPE8k"
private fun parts(n: Int, base: String = O) = (1..n).map { StreamEntry("$base?p=$it", "P$it") }

fun main() {
    val p4 = PlaylistPlan.pageEntries(O, parts(4))
    check("4 bagian halaman diterima", p4 != null && p4.size == 4 && p4[1].url == "$O?p=2")
    check("host www. dan tanpa www. dianggap sama", PlaylistPlan.pageEntries("https://bilibili.com/video/BV1", parts(3)) != null)
    check("satu entri ditolak", PlaylistPlan.pageEntries(O, parts(1)) == null)
    check("kosong ditolak", PlaylistPlan.pageEntries(O, emptyList()) == null)
    check("batas 200 diterima", PlaylistPlan.pageEntries(O, parts(PlaylistPlan.MAX_PAGE_ITEMS)) != null)
    check("lebih dari 200 ditolak", PlaylistPlan.pageEntries(O, parts(PlaylistPlan.MAX_PAGE_ITEMS + 1)) == null)
    check("host beda ditolak", PlaylistPlan.pageEntries(O, parts(2) + StreamEntry("https://evil.example/x?p=3", "x")) == null)
    check("entri media langsung (m3u8) ditolak: daftar IPTV tidak berubah",
        PlaylistPlan.pageEntries(O, listOf(StreamEntry("https://www.bilibili.com/a.m3u8"), StreamEntry("https://www.bilibili.com/b.m3u8"))) == null)
    check("skema bukan http(s) ditolak", PlaylistPlan.pageEntries(O, listOf(StreamEntry("rtmp://www.bilibili.com/a"), StreamEntry("rtmp://www.bilibili.com/b"))) == null)
    check("file:// ditolak", PlaylistPlan.pageEntries(O, listOf(StreamEntry("file:///sdcard/a"), StreamEntry("file:///sdcard/b"))) == null)
    check("duplikat ditolak", PlaylistPlan.pageEntries(O, listOf(StreamEntry("$O?p=1"), StreamEntry("$O?p=1"))) == null)
    check("URL terlalu panjang ditolak", PlaylistPlan.pageEntries(O, listOf(StreamEntry("$O?p=1"), StreamEntry("$O?x=" + "a".repeat(3000)))) == null)
    check("judul dipotong", PlaylistPlan.pageEntries(O, listOf(StreamEntry("$O?p=1", "t".repeat(500)), StreamEntry("$O?p=2")))!![0].title!!.length == PlaylistPlan.MAX_TITLE_LENGTH)
    check("origin tanpa host ditolak", PlaylistPlan.pageEntries("bukan url", parts(2)) == null)

    val plan = PlaylistPlan.parsePages(O, parts(4), 0, "$O?p=1")
    check("parsePages indeks 0 valid", plan != null && plan.urls.size == 4 && plan.index == 0 && plan.titleAt(2) == "P3")
    check("parsePages: current tidak cocok ditolak", PlaylistPlan.parsePages(O, parts(4), 0, "$O?p=2") == null)
    check("parsePages: indeks di luar rentang ditolak", PlaylistPlan.parsePages(O, parts(4), 4, "$O?p=4") == null)
    check("parsePages: indeks negatif ditolak", PlaylistPlan.parsePages(O, parts(4), -1, "$O?p=1") == null)

    check("parse (Intent) tetap menolak halaman bilibili",
        PlaylistPlan.parse(parts(4).map { it.url }, null, 0, "$O?p=1") == null)
    check("parse (Intent) tetap menerima YouTube", PlaylistPlan.parse(
        listOf("https://www.youtube.com/watch?v=AAAAAAAAAAA", "https://www.youtube.com/watch?v=BBBBBBBBBBB"), null, 0,
        "https://www.youtube.com/watch?v=AAAAAAAAAAA") != null)

    val q = RollingQueue(plan!!)
    check("antrean: mulai dari bagian 2", q.pending() == 1 && q.pendingIndices() == listOf(1, 2, 3))
    check("antrean: tidak prefetch bila mpv masih punya item sesudah posisi", q.begin(2, 0) == null)
    check("antrean: prefetch bagian 2 saat mpv habis", q.begin(1, 0) == 1 && q.url(1) == "$O?p=2")
    q.succeeded()
    check("antrean: maju ke bagian 3", q.pending() == 2 && q.appended == 1)
    check("antrean: bagian 3 gagal -> lewati, masih ada bagian 4", q.begin(2, 1) == 2 && q.failed() && q.pending() == 3 && q.skipped == 1)

    val json = """{"_type":"playlist","id":"BV1dQ1AzPE8k","title":"Judul","entries":[
        {"_type":"url","ie_key":"BiliBili","url":"$O?p=1","title":"P1"},
        {"_type":"url","ie_key":"BiliBili","url":"$O?p=2","title":"P2"},
        {"_type":"url","ie_key":"BiliBili","url":"$O?p=3","title":"P3"},
        {"_type":"url","ie_key":"BiliBili","url":"$O?p=4","title":"P4"}]}"""
    val r = YtdlpParse.resolve(json)
    check("parse playlist: 4 entri, url = entri pertama", r != null && r.entries.size == 4 && r.url == "$O?p=1")
    check("parse playlist -> pageEntries -> rencana", r != null && PlaylistPlan.parsePages(O, r.entries, 0, r.entries[0].url) != null)
    val iptv = """{"_type":"playlist","entries":[{"url":"https://tv.example/ch1.m3u8","title":"A"},{"url":"https://tv.example/ch2.m3u8","title":"B"}]}"""
    val ri = YtdlpParse.resolve(iptv)
    check("playlist IPTV langsung bukan playlist halaman", ri != null && PlaylistPlan.pageEntries("https://tv.example/list", ri.entries) == null)

    val dflt = YtdlpCommand.resolveArgs(O)
    check("resolveArgs bawaan: --flat-playlist", "--flat-playlist" in dflt && "--no-playlist" !in dflt)
    val one = YtdlpCommand.resolveArgs("$O?p=2", noPlaylist = true)
    check("resolveArgs noPlaylist: --no-playlist", "--no-playlist" in one && "--flat-playlist" !in one)
    check("resolveArgs list= tetap --no-playlist", "--no-playlist" in YtdlpCommand.resolveArgs("https://x.example/v?list=PL1"))
    check("resolveArgs: URL tetap setelah --", one.last() == "$O?p=2" && one[one.size - 2] == "--")

    if (fails > 0) { println("$fails GAGAL"); kotlin.system.exitProcess(1) }
    println("semua lulus")
}
