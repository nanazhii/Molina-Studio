import com.molina.core.common.DateFilter
import com.molina.core.common.DurationFilter
import com.molina.core.common.RelativeTime
import com.molina.core.common.SearchOrder
import com.molina.core.common.SearchSort
import com.molina.core.common.StreamSearchItem

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private const val NOW = 1_000_000_000_000L
private const val DAY = 86_400_000L

private fun item(t: String, at: Long? = null, text: String? = null, up: String? = null) =
    StreamSearchItem("https://example.com/$t", t, up, null, null, at, text)

private fun titles(l: List<StreamSearchItem>) = l.joinToString(",") { it.title }

fun main() {
    check("estimate: uploadedAtMs didahulukan", RelativeTime.estimateAtMs(item("a", at = 5L, text = "3 days ago"), NOW) == 5L)
    check("estimate: 3 days ago", RelativeTime.estimateAtMs(item("a", text = "3 days ago"), NOW) == NOW - 3 * DAY)
    check("estimate: Streamed 2 weeks ago", RelativeTime.estimateAtMs(item("a", text = "Streamed 2 weeks ago"), NOW) == NOW - 14 * DAY)
    check("estimate: 1 year ago", RelativeTime.estimateAtMs(item("a", text = "1 year ago"), NOW) == NOW - 365 * DAY)
    check("estimate: teks tak dikenal -> null", RelativeTime.estimateAtMs(item("a", text = "Live"), NOW) == null)
    check("estimate: kosong -> null", RelativeTime.estimateAtMs(item("a"), NOW) == null)

    val a = item("A", text = "3 years ago")
    val b = item("B", at = NOW - DAY)
    val c = item("C")
    val d = item("D", text = "2 hours ago")
    val src = listOf(a, b, c, d)
    check("NEWEST: baru ke lama, tanpa tanggal di akhir", titles(SearchOrder.sort(src, SearchSort.NEWEST, NOW)) == "D,B,A,C")
    check("RELATED: sort() tidak mengubah urutan (disusun relatedMix)", titles(SearchOrder.sort(src, SearchSort.RELATED, NOW)) == "A,B,C,D")
    check("RELEVANCE: urutan asli", titles(SearchOrder.sort(src, SearchSort.RELEVANCE, NOW)) == "A,B,C,D")
    check("sumber tidak diubah", titles(src) == "A,B,C,D")

    val t1 = item("T1", text = "3 days ago"); val t2 = item("T2", text = "3 days ago"); val t3 = item("T3", text = "3 days ago")
    check("stabil: sewaktu mempertahankan urutan", titles(SearchOrder.sort(listOf(t1, t2, t3), SearchSort.NEWEST, NOW)) == "T1,T2,T3")
    val m1 = item("M1", at = NOW - 5 * DAY); val m2 = item("M2", text = "3 days ago")
    check("campuran ms dan teks", titles(SearchOrder.sort(listOf(m1, m2), SearchSort.NEWEST, NOW)) == "M2,M1")
    check("daftar kosong", SearchOrder.sort(emptyList(), SearchSort.NEWEST, NOW).isEmpty())

    val x = item("Satu", up = "Kanal X"); val y = item("Dua", up = "kanal x"); val z = item("Tiga", up = null)
    check("matches: judul", SearchOrder.matches(x, "sat"))
    check("matches: kanal", SearchOrder.matches(x, "kanal x"))
    check("matches: tidak cocok", !SearchOrder.matches(x, "zzz"))
    check("matches: kueri kosong", SearchOrder.matches(z, "  "))
    check("matches: tanpa kanal tidak error", !SearchOrder.matches(z, "kanal"))

    val r = listOf(item("1", up = "X"), item("2", up = "Y"), item("3", up = "X"), item("4", up = "Z"),
        item("5", up = "Y"), item("6", up = "X"), item("7", up = " "), item("8"))
    check("related: kanal terbanyak, min 2", SearchOrder.related(r) == listOf("X" to 3, "Y" to 2))
    check("related: batas jumlah", SearchOrder.related(r, limit = 1) == listOf("X" to 3))
    check("related: kosong bila tak ada pengulangan", SearchOrder.related(listOf(item("1", up = "A"), item("2", up = "B"))).isEmpty())

    // saran kata kunci terkait
    val titlesQ = listOf("Lagu Pop Terbaru 2026", "Kumpulan Lagu Pop Terbaru", "Lagu Dangdut Koplo", "Lagu Dangdut Koplo Terbaru", "Official Video Lagu Rock")
        .map { t -> StreamSearchItem("https://example.com/" + t.replace(' ', '-'), t, null, null, null, null, null) }
    check("relatedQueries: frasa dan kata, tanpa kata kueri/umum",
        SearchOrder.relatedQueries(titlesQ, "lagu") == listOf("lagu terbaru", "lagu pop terbaru", "lagu dangdut koplo"))
    check("relatedQueries: batas jumlah", SearchOrder.relatedQueries(titlesQ, "lagu", limit = 1) == listOf("lagu terbaru"))
    check("relatedQueries: kueri kosong tanpa awalan", SearchOrder.relatedQueries(titlesQ, "").first() == "lagu")
    check("relatedQueries: tidak ada pengulangan -> kosong",
        SearchOrder.relatedQueries(listOf(StreamSearchItem("https://e/1", "Satu Dua", null, null, null, null, null)), "x").isEmpty())
    check("relatedQueries: daftar kosong", SearchOrder.relatedQueries(emptyList(), "lagu").isEmpty())
    val ch = listOf("A", "B", "C", "D").flatMap { c -> listOf(item(c + "1", up = c), item(c + "2", up = c)) }
    check("related: batas bawaan 3 kanal", SearchOrder.related(ch).size == 3)

    // [RESULTS-FILTER]
    fun dv(t: String, dur: Long?, at: Long? = null) = StreamSearchItem("https://example.com/$t", t, null, dur, null, at, null)
    val fs = listOf(dv("s", 300, NOW - DAY), dv("m", 1200, NOW - 10 * DAY), dv("l", 3600, NOW - 100 * DAY),
        dv("live", null), dv("b600", 600, NOW), dv("b1800", 1800, NOW), dv("b599", 599, NOW))
    fun f(d: DurationFilter, t: DateFilter) = titles(SearchOrder.filter(fs, d, t, NOW))
    check("filter: ALL/ALL = objek asli", SearchOrder.filter(fs, DurationFilter.ALL, DateFilter.ALL, NOW) === fs)
    check("filter durasi pendek (<10 mnt, 599 masuk)", f(DurationFilter.SHORT, DateFilter.ALL) == "s,b599")
    check("filter durasi sedang (batas 600 dan 1800 masuk)", f(DurationFilter.MEDIUM, DateFilter.ALL) == "m,b600,b1800")
    check("filter durasi panjang (>30 mnt)", f(DurationFilter.LONG, DateFilter.ALL) == "l")
    check("filter durasi: durasi tak diketahui disingkirkan", !f(DurationFilter.SHORT, DateFilter.ALL).contains("live"))
    check("filter 7 hari", f(DurationFilter.ALL, DateFilter.WEEK) == "s,b600,b1800,b599")
    check("filter 30 hari", f(DurationFilter.ALL, DateFilter.MONTH) == "s,m,b600,b1800,b599")
    check("filter 1 tahun", f(DurationFilter.ALL, DateFilter.YEAR) == "s,m,l,b600,b1800,b599")
    check("filter gabungan sedang + 30 hari", f(DurationFilter.MEDIUM, DateFilter.MONTH) == "m,b600,b1800")
    check("filter: sumber tidak diubah", fs.size == 7)
    check("next berputar ke awal", DurationFilter.LONG.next() == DurationFilter.ALL && DateFilter.YEAR.next() == DateFilter.ALL)
    check("next maju satu", DurationFilter.ALL.next() == DurationFilter.SHORT && DateFilter.ALL.next() == DateFilter.WEEK)

    // [RESULTS-RELATED] relatedMix: ownChannels, relatedQuery, campur per kanal
    fun u(t: String, up: String?) = item(t, up = up)
    val jr = listOf(u("Review Xiaomi Redmi Note 14", "Jagat Review"), u("Review Xiaomi Poco X7", "Jagat Review"),
        u("Unboxing Samsung Galaxy A56", "Jagat Review"), u("Review Samsung Galaxy A36", "Jagat Review"),
        u("Tips kamera HP", "Gadgetin"))
    check("ownChannels: nama kanal sama dengan kueri", SearchOrder.ownChannels(jr, "jagat review") == setOf("jagat review"))
    check("ownChannels: kueri diawali nama kanal + kata lain", SearchOrder.ownChannels(jr, "jagat review xiaomi") == setOf("jagat review"))
    check("ownChannels: awalan nama kanal", SearchOrder.ownChannels(jr, "jagat") == setOf("jagat review"))
    check("ownChannels: topik umum bukan kanal", SearchOrder.ownChannels(jr, "review").isEmpty())
    check("ownChannels: kueri terlalu pendek", SearchOrder.ownChannels(jr, "ja").isEmpty())
    check("relatedQuery: buang nama kanal, simpan kata khas, tambah kata judul",
        SearchOrder.relatedQuery(jr, "jagat review") == "review xiaomi samsung")
    check("relatedQuery: tanpa kata baru -> null", SearchOrder.relatedQuery(listOf(u("Satu", "A")), "zzz kosong") == null)
    check("relatedQuery: daftar kosong -> null", SearchOrder.relatedQuery(emptyList(), "jagat review") == null)
    val mixBase = listOf(u("A1", "Jagat Review"), u("A2", "Jagat Review"), u("B1", "Gadgetin"))
    val mixExtra = listOf(u("G1", "Gadgetin"), u("G2", "Gadgetin"), u("G3", "Gadgetin"), u("G4", "Gadgetin"),
        u("C1", "Cupu"), u("C2", "Cupu"), u("A3", "Jagat Review"), u("B1", "Gadgetin"))
    check("relatedMix: buang kanal sendiri, gilir antar kanal, maks 3 per kanal",
        titles(SearchOrder.relatedMix(mixBase, mixExtra, "jagat review")) == "G1,C1,G2,C2,G3")
    check("relatedMix: tanpa duplikat URL", SearchOrder.relatedMix(mixBase, mixExtra, "jagat review").map { it.pageUrl }.toSet().size == 5)
    check("relatedMix: batas total", SearchOrder.relatedMix(mixBase, mixExtra, "jagat review", limit = 3).size == 3)
    val onlyOwn = listOf(u("A1", "Jagat Review"), u("A2", "Jagat Review"))
    check("relatedMix: hanya kanal sendiri -> daftar asli (objek sama)", SearchOrder.relatedMix(onlyOwn, emptyList(), "jagat review") === onlyOwn)
    check("relatedMix: kueri topik umum menyisakan semua kanal", titles(SearchOrder.relatedMix(mixBase, emptyList(), "review")) == "A1,B1,A2")

    println(if (fails == 0) "\nSEMUA LOLOS" else "\n$fails GAGAL")
    if (fails > 0) System.exit(1)
}
