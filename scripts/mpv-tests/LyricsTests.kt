import com.molina.cinema.*

private var failed = 0
private fun check(name: String, cond: Boolean) {
    if (cond) println("OK   $name") else { println("GAGAL $name"); failed++ }
}

fun main() {
    // --- LRC dasar, metadata, BOM, CRLF
    val lrc = "\uFEFF[ti:Judul]\r\n[ar:Artis]\r\n[al:Album]\r\n[00:01.50]Satu\r\n[00:03.5]Dua\r\n[00:05.123]Tiga\r\n"
    val d = LrcParser.parse(lrc)
    check("metadata ti/ar/al", d.title == "Judul" && d.artist == "Artis" && d.album == "Album")
    check("3 baris tersinkron", d.synced && d.lines.size == 3)
    check("fraksi 2 digit = 10ms", d.lines[0].startMs == 1_500L)
    check("fraksi 1 digit = 100ms", d.lines[1].startMs == 3_500L)
    check("fraksi 3 digit = ms", d.lines[2].startMs == 5_123L)

    // --- banyak tag per baris, urut, baris kosong dipertahankan
    val multi = LrcParser.parse("[00:10.00][00:02.00]Reff\n[00:06.00]\n")
    check("multi-tag terurut", multi.lines.map { it.startMs } == listOf(2_000L, 6_000L, 10_000L))
    check("baris kosong (jeda) dipertahankan", multi.lines[1].text.isEmpty())

    // --- offset: positif = lebih awal; tidak negatif
    val off = LrcParser.parse("[offset:500]\n[00:01.00]A\n[00:00.20]B\n")
    check("offset positif memajukan", off.lines.map { it.startMs } == listOf(0L, 500L))

    // --- enhanced LRC per kata
    val enh = LrcParser.parse("[00:10.00]<00:10.00>Ha <00:10.50>lo <00:11.00>dunia")
    check("teks bersih tanpa tag kata", enh.lines[0].text == "Ha lo dunia")
    check("3 kata bertimestamp", enh.lines[0].words.map { it.startMs } == listOf(10_000L, 10_500L, 11_000L))
    check("kata aktif @10.7s = indeks 1", activeWordIndex(enh.lines[0], 10_700) == 1)
    check("kata aktif sebelum mulai = -1", activeWordIndex(enh.lines[0], 9_000) == -1)

    // --- lirik biasa tanpa timestamp
    val plain = LrcParser.parse("Baris satu\nBaris dua\n")
    check("tanpa timestamp = tidak tersinkron", !plain.synced && plain.plainLines.size == 2)

    // --- sampah / injeksi: tidak crash, tag di tengah teks bukan penanda waktu
    val junk = LrcParser.parse("[99:99.99]x\n[abc]y\nteks [00:01.00] di tengah\n")
    check("tag di tengah bukan timestamp", junk.lines.none { it.text.contains("di tengah") })

    // --- activeIndex & tracker
    val ls = d.lines
    check("sebelum baris pertama = -1", ls.activeIndex(0) == -1)
    check("tepat di awal baris", ls.activeIndex(1_500) == 0)
    check("di antara baris", ls.activeIndex(4_000) == 1)
    check("setelah baris terakhir", ls.activeIndex(999_999) == 2)
    val tr = LyricsTracker(ls)
    check("emisi pertama", tr.update(1_600) == 0)
    check("tidak emit bila indeks sama", tr.update(2_000) == null)
    check("emit saat berganti", tr.update(3_600) == 1)
    check("offset pengguna +500 memajukan", tr.update(3_000, 500) == null) // 3.5s => masih indeks 1
    tr.reset()
    check("reset memaksa emisi", tr.update(3_600) == 1)

    // --- SRT
    val srt = "1\r\n00:00:01,000 --> 00:00:03,500\r\nHalo\r\ndunia\r\n\r\n2\r\n00:01:00.250 --> 00:01:02,000\r\nKedua\r\n"
    val cues = SrtCodec.parse(srt)
    check("parse 2 cue", cues.size == 2)
    check("teks multibaris", cues[0].text == "Halo\ndunia")
    check("titik sebagai pemisah ms", cues[1].startMs == 60_250L)
    val round = SrtCodec.parse(SrtCodec.write(cues))
    check("tulis lalu baca = identik", round == cues)
    check("shift -2000 tidak negatif", SrtCodec.shift(cues, -2_000)[0].startMs == 0L)
    check("shift +1000", SrtCodec.shift(cues, 1_000)[1].startMs == 61_250L)
    check("scale 2x", SrtCodec.scale(cues, 2.0)[0].endMs == 7_000L)
    check("lirik -> SRT, kosong dilewati", SrtCodec.fromLyrics(multi.lines).size == 2)
    check("SRT tanpa nomor urut tetap terbaca", SrtCodec.parse("00:00:01,000 --> 00:00:02,000\nX").size == 1)

    if (failed > 0) { println("$failed tes gagal"); kotlin.system.exitProcess(1) }
    println("Semua tes lulus")
}
