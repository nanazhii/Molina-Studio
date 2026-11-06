import com.molina.cinema.*

private var failed = 0
internal fun check(name: String, cond: Boolean) {
    if (cond) println("OK   $name") else { println("GAGAL $name"); failed++ }
}

fun main() {
    embeddedTests()
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

    styleTests(::check)
    filesTests(::check)
    karaokeTests(::check)
    fillTests(::check)
    offsetsTests(::check)
    assTests(::check)
    convertPlanTests(::check)
    extractCommandTests(::check)

    if (failed > 0) { println("$failed tes gagal"); kotlin.system.exitProcess(1) }
    println("Semua tes lulus")
}

// --- SubStyle & SubSpeeds (dipanggil dari main lewat styleTests)
internal fun styleTests(check: (String, Boolean) -> Unit) {
    val ids = SubStyles.PRESETS.map { it.id }
    check("id preset unik", ids.toSet().size == ids.size)
    check("preset bawaan ada", SubStyles.byId(SubStyles.DEFAULT_ID) != null)
    val keys = SubStyles.PRESETS.map { p -> p.props.map { it.first }.toSet() }
    check("semua preset memuat kunci yang sama", keys.toSet().size == 1)
    check("tanpa kunci ganda dalam satu preset", SubStyles.PRESETS.all { p -> p.props.map { it.first }.toSet().size == p.props.size })
    check("warna valid hex", SubStyles.PRESETS.all { p -> p.props.filter { it.first.endsWith("color") }.all { SubStyles.isHexColor(it.second) } })
    check("angka valid", SubStyles.PRESETS.all { p -> p.props.filter { it.first.endsWith("size") || it.first.endsWith("offset") }.all { it.second.toDoubleOrNull() != null } })
    check("flag bold yes/no", SubStyles.PRESETS.all { p -> p.props.first { it.first == "sub-bold" }.second in setOf("yes", "no") })
    check("byId tak dikenal = null", SubStyles.byId("tidak-ada") == null && SubStyles.byId(null) == null)

    check("NORMAL = 1.0", SubSpeeds.NORMAL.speed == 1.0)
    // Contoh manual mpv: 25/23.976 untuk subtitle asumsi 23.976 diputar 25 fps
    val ntscTo25 = SubSpeeds.PRESETS[1].speed
    check("23.976 -> 25 = 1.0427", kotlin.math.abs(ntscTo25 - 1.042709) < 1e-5)
    check("25 -> 23.976 = 0.95904", kotlin.math.abs(SubSpeeds.PRESETS[2].speed - 0.95904) < 1e-5)
    check("preset berlawanan saling membalik", kotlin.math.abs(ntscTo25 * SubSpeeds.PRESETS[2].speed - 1.0) < 1e-9)
    check("indexOf cocok", SubSpeeds.indexOf(1.0) == 0 && SubSpeeds.indexOf(25.0 / 24.0) == 3)
    check("indexOf kustom/null = -1", SubSpeeds.indexOf(1.2) == -1 && SubSpeeds.indexOf(null) == -1)
    check("label normal = null", SubSpeeds.label(SubSpeeds.NORMAL) == null)
    check("label memuat fps & faktor", SubSpeeds.label(SubSpeeds.PRESETS[3])!!.let { "24" in it && "25" in it && "1.0417" in it.replace(',', '.') })
}

// --- LyricsFiles (berkas sementara)
internal fun filesTests(check: (String, Boolean) -> Unit) {
    val root = java.nio.file.Files.createTempDirectory("molina-lrc").toFile()
    try {
        val music = java.io.File(root, "music").also { it.mkdirs() }
        val lib = java.io.File(root, "lib").also { it.mkdirs() }
        val song = java.io.File(music, "Lagu Satu.mp3").also { it.writeText("x") }
        check("tanpa lrc = null", LyricsFiles.find(song.path, lib.path) == null)

        java.io.File(lib, "Lagu Satu.lrc").writeText("[00:01.00]dari pustaka")
        check("fallback ke folder pustaka", LyricsFiles.find(song.path, lib.path)?.parentFile == lib)

        java.io.File(music, "lagu satu.LRC").writeText("[00:01.00]sebelah lagu")
        check("sebelah lagu didahulukan, huruf tak dibedakan", LyricsFiles.find(song.path, lib.path)?.parentFile == music)

        val utf8 = java.io.File(music, "u.lrc").also { it.writeText("[00:01.00]café", Charsets.UTF_8) }
        check("UTF-8 terbaca", LyricsFiles.readText(utf8) == "[00:01.00]café")
        val latin = java.io.File(music, "l.lrc").also { it.writeBytes("[00:01.00]caf\u00e9".toByteArray(charset("windows-1252"))) }
        check("fallback windows-1252", LyricsFiles.readText(latin) == "[00:01.00]caf\u00e9")

        val big = java.io.File(lib, "Besar.lrc").also { java.io.RandomAccessFile(it, "rw").use { r -> r.setLength(LyricsFiles.MAX_BYTES + 1) } }
        check("berkas terlalu besar diabaikan", LyricsFiles.find(java.io.File(music, "Besar.mp3").path, lib.path) == null && LyricsFiles.readText(big) == null)
        java.io.File(lib, "Kosong.lrc").createNewFile()
        check("berkas kosong diabaikan", LyricsFiles.find(java.io.File(music, "Kosong.mp3").path, lib.path) == null)
        check("nama menyerupai traversal tetap aman", LyricsFiles.find("/tidak/ada/../../x.mp3", lib.path) == null)
        check("path kosong aman", LyricsFiles.find("", lib.path) == null)
    } finally { root.deleteRecursively() }
}

internal fun karaokeTests(check: (String, Boolean) -> Unit) {
    val pre = LrcParser.parse("[00:10.00]Hai <00:10.50>dunia <00:11.00>baru").lines[0]
    check("teks sebelum tag pertama jadi kata", pre.words.map { it.text } == listOf("Hai ", "dunia ", "baru"))
    check("kata awalan di waktu baris", pre.words[0].startMs == 10_000L)
    val sp = LrcParser.parse("[00:01.00]<00:01.00>A<00:01.50> <00:02.00>B").lines[0]
    check("spasi mandiri digabung ke kata sebelumnya", sp.words.map { it.text } == listOf("A ", "B"))
    check("gabungan kata = teks baris", pre.words.joinToString("") { it.text }.trim() == pre.text)

    val line = LrcParser.parse("[00:10.00]<00:10.00>Ha <00:10.50>lo <00:11.00>dunia").lines[0]
    check("belum ada kata mulai: semua belum", line.segments(-1).none { it.sung })
    check("kata 1 aktif: dua pertama sung", line.segments(1).map { it.sung } == listOf(true, true, false))
    check("kata terakhir: semua sung", line.segments(2).all { it.sung })
    check("segmen mempertahankan teks", line.segments(0).joinToString("") { it.text } == "Ha lo dunia")
    val plain = LyricLine(0, "tanpa kata")
    check("tanpa kata: satu segmen utuh", plain.segments(-1) == listOf(LyricSegment("tanpa kata", true)))

    val tr = LyricsTracker(listOf(line))
    check("tracker.current awal = -1", tr.current == -1)
    tr.update(10_600); check("tracker.current = 0", tr.current == 0)
    tr.reset(); check("reset -> current -1", tr.current == -1)
    // alur karaoke: kata aktif berubah sepanjang baris, offset pengguna ikut
    val seq = listOf(9_900L, 10_100L, 10_600L, 11_200L).map { activeWordIndex(line, it) }
    check("kata aktif berurutan", seq == listOf(-1, 0, 1, 2))
    check("offset +500 memajukan kata", activeWordIndex(line, 10_100L + 500) == 1)
}

internal fun fillTests(check: (String, Boolean) -> Unit) {
    val line = LrcParser.parse("[00:10.00]<00:10.00>Hai <00:11.00>dunia").lines[0] // "Hai " (1 s), "dunia" (kata terakhir)
    check("fill: sebelum kata pertama = (-1,0)", karaokeFill(line, 14_000, 9_000) == KaraokeFill(-1, 0))
    check("fill: awal kata 0 = 0 huruf", karaokeFill(line, 14_000, 10_000) == KaraokeFill(0, 0))
    check("fill: kata 0 terisi bertahap (1 -> 2 -> 3 huruf)", listOf(10_340L, 10_700L, 10_990L).map { karaokeFill(line, 14_000, it).chars } == listOf(1, 2, 3))
    check("fill: kata terakhir dibatasi estimasi, bukan jeda sampai baris berikutnya", karaokeFill(line, 14_000, 12_000) == KaraokeFill(1, 5))
    check("fill: kata terakhir tanpa batas baris memakai estimasi", karaokeFill(line, null, 11_150) == KaraokeFill(1, 1))
    check("fill: awal baris berikutnya lebih dekat dari estimasi", karaokeFill(line, 11_400, 11_200) == KaraokeFill(1, 3))
    check("fill: spasi akhir tidak dihitung", karaokeFill(line, 14_000, 10_999).chars <= 3)
    check("fill: segmen kata aktif terpecah", line.fillSegments(KaraokeFill(0, 1)).map { it.text to it.sung } == listOf("H" to true, "ai " to false, "dunia" to false))
    check("fill: kata sebelumnya sung penuh, sesudahnya belum", line.fillSegments(KaraokeFill(1, 0)).map { it.text to it.sung } == listOf("Hai " to true, "dunia" to false))
    check("fill: semua sung di akhir", line.fillSegments(KaraokeFill(1, 5)).all { it.sung })
    check("fill: teks utuh untuk semua kombinasi", (-1..1).all { w -> (0..5).all { c -> line.fillSegments(KaraokeFill(w, c)).joinToString("") { it.text } == "Hai dunia" } })
    check("fill: tanpa kata = satu segmen utuh", LyricLine(0, "x").fillSegments(KaraokeFill(-1, 0)) == listOf(LyricSegment("x", true)))
    val emo = LyricLine(0, "\uD83D\uDE00a", listOf(LyricWord(0, "\uD83D\uDE00a")))
    check("fill: pasangan surrogate tidak dibelah", emo.fillSegments(KaraokeFill(0, 1)).first().text == "\uD83D\uDE00")
    val seq = (9_000L..15_000L step 50L).map { karaokeFill(line, 14_000, it) }
    check("fill: monoton naik sepanjang waktu", seq.zipWithNext().all { (a, b) -> b.word > a.word || (b.word == a.word && b.chars >= a.chars) })
}

internal fun offsetsTests(check: (String, Boolean) -> Unit) {
    val o = LyricsOffsets(3)
    check("default 0", o.get("/a.mp3") == 0L)
    o.put("/a.mp3", 1_500); check("put/get", o.get("/a.mp3") == 1_500L)
    o.put("/a.mp3", 0); check("0 menghapus entri", o.size == 0)
    o.put("/b", 100_000); check("dijepit ke +-60 s", o.get("/b") == 60_000L)
    o.put("/b", -100_000); check("negatif dijepit", o.get("/b") == -60_000L)

    val e = LyricsOffsets(3)
    for (k in listOf("1", "2", "3")) e.put(k, 10)
    e.put("2", 20) // 2 menjadi yang terbaru
    e.put("4", 10) // buang yang terlama (1)
    check("entri terlama dibuang", e.get("1") == 0L && e.get("2") == 20L && e.get("3") == 10L && e.get("4") == 10L)
    check("ukuran dibatasi", e.size == 3)

    val weird = "/sdcard/Lagu\ttab\nbaris%25 dan 100%.mp3"
    val r = LyricsOffsets()
    r.put(weird, -750); r.put("/biasa.mp3", 300)
    val back = LyricsOffsets.parse(r.serialize())
    check("serialisasi bolak-balik (kunci aneh)", back.get(weird) == -750L && back.get("/biasa.mp3") == 300L)
    val bad = LyricsOffsets.parse("tanpa-tab\n\t5\n/x\tabc\n/ok\t42\n")
    check("baris rusak diabaikan", bad.size == 1 && bad.get("/ok") == 42L)
    check("parse null/kosong aman", LyricsOffsets.parse(null).size == 0 && LyricsOffsets.parse("").size == 0)
}

internal fun assTests(check: (String, Boolean) -> Unit) {
    val ass = "\uFEFF[Script Info]\r\nTitle: x\r\n\r\n[V4+ Styles]\r\nFormat: Name\r\nStyle: Default\r\n\r\n[Events]\r\n" +
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\r\n" +
        "Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,{\\i1}Halo{\\i0}, dunia\\Nbaris dua\r\n" +
        "Comment: 0,0:00:04.00,0:00:05.00,Default,,0,0,0,,diabaikan\r\n" +
        "Dialogue: 0,0:00:00.20,0:00:01.00,Default,,0,0,0,,Awal\\hspasi\r\n"
    val c = AssCodec.parse(ass)
    check("2 dialog (komentar dilewati)", c.size == 2)
    check("terurut waktu", c[0].startMs == 200L && c[1].startMs == 1_500L)
    check("tag override dibuang, koma di teks utuh", c[1].text == "Halo, dunia\nbaris dua")
    check("\\h jadi spasi", c[0].text == "Awal spasi")

    // Format kustom: urutan kolom berbeda
    val custom = "[Events]\nFormat: Start, End, Text\nDialogue: 0:00:01.00,0:00:02.00,Satu, dua\n"
    check("Format kustom dihormati", AssCodec.parse(custom).let { it.size == 1 && it[0].text == "Satu, dua" })
    check("tanpa [Events] = kosong", AssCodec.parse("Dialogue: 0,0:00:01.00,0:00:02.00,D,,0,0,0,,x").isEmpty())

    check("waktu 1 digit pecahan", AssCodec.parseTime("0:00:01.5") == 1_500L)
    check("waktu salah = null", AssCodec.parseTime("abc") == null)
    check("format sentisekon dibulatkan", AssCodec.formatTime(1_234) == "0:00:01.23" && AssCodec.formatTime(1_235) == "0:00:01.24")
    check("format jam", AssCodec.formatTime(3_723_450) == "1:02:03.45")
    check("pembulatan carry ke detik", AssCodec.formatTime(1_999) == "0:00:02.00")

    // SRT -> ASS -> SRT
    val srt = "1\n00:00:01,000 --> 00:00:03,500\nHalo\ndunia\n\n2\n00:01:00,250 --> 00:01:02,000\n{kurung} & koma, ok\n"
    val toAss = SubtitleConvert.convert(srt, SubFormat.ASS)!!
    check("hasil ASS dikenali", SubtitleConvert.detect(toAss) == SubFormat.ASS && toAss.contains("[Events]") && toAss.contains("Style: Default"))
    check("baris baru jadi \\N", toAss.contains("Halo\\Ndunia"))
    val back = SrtCodec.parse(SubtitleConvert.convert(toAss, SubFormat.SRT)!!)
    check("bolak-balik: jumlah & teks", back.size == 2 && back[0].text == "Halo\ndunia")
    check("kurung kurawal tidak hilang diam-diam", back[1].text == "(kurung) & koma, ok")
    check("waktu bolak-balik dalam 10 ms", back.zip(SrtCodec.parse(srt)).all { (a, b) -> kotlin.math.abs(a.startMs - b.startMs) <= 10 && kotlin.math.abs(a.endMs - b.endMs) <= 10 })

    check("deteksi SRT", SubtitleConvert.detect(srt) == SubFormat.SRT)
    check("deteksi tak dikenal", SubtitleConvert.detect("teks biasa") == SubFormat.UNKNOWN)
    check("convert sumber tak dikenal = null", SubtitleConvert.convert("teks biasa", SubFormat.SRT) == null)
    check("convert kosong = null", SubtitleConvert.convert("[Events]\nFormat: Start, End, Text\n", SubFormat.SRT) == null)

    // Gaya
    check("warna #FFEB3B -> &H003BEBFF (BGR)", AssCodec.write(listOf(SubCue(0, 1, "x")), AssStyle(primaryHex = "#FFEB3B")).contains("&H003BEBFF"))
    check("warna rusak aman", AssCodec.write(listOf(SubCue(0, 1, "x")), AssStyle(primaryHex = "bukan-warna")).contains("&H00FFFFFF"))
    val anime = AssStyle.fromPreset(SubStyles.byId("anime")!!)
    check("preset anime: kuning, tebal, outline 3.5", anime.primaryHex == "#FFEB3B" && anime.bold && anime.outline == 3.5)
    check("tebal -> -1 di ASS", AssCodec.write(emptyList(), anime).contains(",&H80000000,-1,0,0,0,"))
}

internal fun convertPlanTests(check: (String, Boolean) -> Unit) {
    fun tr(external: Boolean, file: String? = null, codec: String? = "subrip", ff: Int? = 3, lang: String? = "id") =
        SubTrackInfo(1, external, file, codec, ff, lang)
    val P = SubConvertPlanner
    check("eksternal lokal -> FromFile", P.plan(tr(true, "/sdcard/a.srt"), false) == ConvertPlan.FromFile("/sdcard/a.srt"))
    check("eksternal file:// dinormalkan", P.plan(tr(true, "file:///sdcard/a.ass"), false) == ConvertPlan.FromFile("/sdcard/a.ass"))
    check("eksternal memory:// tidak didukung", P.plan(tr(true, "memory://x"), true) == ConvertPlan.Unsupported)
    check("eksternal http tidak didukung", P.plan(tr(true, "https://x/a.srt"), true) == ConvertPlan.Unsupported)
    check("eksternal tanpa nama tidak didukung", P.plan(tr(true, null), true) == ConvertPlan.Unsupported)
    check("tertanam + toggle mati", P.plan(tr(false), false) == ConvertPlan.EmbeddedDisabled)
    check("tertanam + toggle hidup", P.plan(tr(false), true) == ConvertPlan.NeedsExtraction(3))
    check("tertanam ASS + toggle hidup", P.plan(tr(false, codec = "ass", ff = 5), true) == ConvertPlan.NeedsExtraction(5))
    check("PGS = bitmap (tak peduli toggle)", P.plan(tr(false, codec = "hdmv_pgs_subtitle"), true) == ConvertPlan.Bitmap)
    check("VobSub = bitmap", P.plan(tr(false, codec = "dvd_subtitle"), false) == ConvertPlan.Bitmap)
    check("bitmap eksternal juga bitmap", P.plan(tr(true, "/sdcard/a.sup", codec = "hdmv_pgs_subtitle"), true) == ConvertPlan.Bitmap)
    check("kodek asing tidak didukung", P.plan(tr(false, codec = "aneh"), true) == ConvertPlan.Unsupported)
    check("tanpa ff-index tidak bisa diekstrak", P.plan(tr(false, ff = null), true) == ConvertPlan.Unsupported)
    check("kodek huruf besar dikenali", P.plan(tr(false, codec = "SubRip"), true) == ConvertPlan.NeedsExtraction(3))
    check("kodek null tertanam tetap diproses", P.plan(tr(false, codec = null), true) == ConvertPlan.NeedsExtraction(3))

    val none: (String) -> Boolean = { false }
    check("nama dasar dari media + bahasa", P.outputPath("/d", "/m/Film Bagus.mkv", "id", SubFormat.SRT, none) == "/d/Film Bagus.id.srt")
    check("ekstensi ASS", P.outputPath("/d", "/m/f.mkv", null, SubFormat.ASS, none) == "/d/f.ass")
    check("dir berakhiran / dirapikan", P.outputPath("/d/", "/m/f.mkv", null, SubFormat.SRT, none) == "/d/f.srt")
    check("tanpa media = subtitle", P.outputPath("/d", null, null, SubFormat.SRT, none) == "/d/subtitle.srt")
    val taken = setOf("/d/f.srt", "/d/f-2.srt")
    check("tidak menimpa: -2, -3", P.outputPath("/d", "/m/f.mkv", null, SubFormat.SRT) { it in taken } == "/d/f-3.srt")
    check("berhenti di -99", P.outputPath("/d", "/m/f.mkv", null, SubFormat.SRT) { true } == "/d/f-99.srt")
    check("karakter terlarang disanitasi", P.sanitize("a/b:c*d?\"e<f>g|h") == "a_b_c_d__e_f_g_h")
    check("titik di tepi dibuang", P.sanitize("..rahasia..") == "rahasia")
    check("nama traversal tetap di dir", !P.outputPath("/d", "/m/../../etc/passwd.mkv", "../x", SubFormat.SRT, none).removePrefix("/d/").contains('/'))
    check("bahasa disaring (; dan spasi dibuang, - boleh)", P.outputPath("/d", "/m/f.mkv", "ja;rm -rf", SubFormat.SRT, none) == "/d/f.jarm-rf.srt")
    check("bahasa dibatasi 8 karakter", P.outputPath("/d", "/m/f.mkv", "abcdefghijkl", SubFormat.SRT, none) == "/d/f.abcdefgh.srt")
    check("localPath relatif = null", P.localPath("sub.srt") == null && P.localPath("/") == null && P.localPath(null) == null)
}

internal fun extractCommandTests(check: (String, Boolean) -> Unit) {
    val C = SubtitleExtractCommand
    val IN = "/sdcard/Molina/Film Bagus.mkv"
    val OUT = "/sdcard/Molina/Subtitles/Film Bagus.id.srt"
    check("argv SRT lengkap", C.argv(IN, 3, SubFormat.SRT, OUT) == listOf(
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
        "-i", IN, "-map", "0:3", "-c:s", "srt", "-f", "srt", OUT))
    val ass = C.argv(IN, 0, SubFormat.ASS, "/sdcard/Molina/Subtitles/a.ass")
    check("argv ASS: codec dan muxer ass", ass != null && ass.contains("ass") && ass.indexOf("-c:s") + 1 == ass.indexOf("ass"))
    check("target UNKNOWN ditolak", C.argv(IN, 3, SubFormat.UNKNOWN, OUT) == null)
    check("indeks negatif ditolak", C.argv(IN, -1, SubFormat.SRT, OUT) == null)
    check("indeks 1023 boleh, 1024 ditolak", C.argv(IN, 1023, SubFormat.SRT, OUT) != null && C.argv(IN, 1024, SubFormat.SRT, OUT) == null)
    check("jalur relatif ditolak", C.argv("film.mkv", 3, SubFormat.SRT, OUT) == null && C.argv(IN, 3, SubFormat.SRT, "out.srt") == null)
    check("traversal .. ditolak", C.argv("/sdcard/Molina/../DCIM/x.mkv", 3, SubFormat.SRT, OUT) == null)
    check("baris baru / NUL ditolak", C.argv("/sdcard/a\nb.mkv", 3, SubFormat.SRT, OUT) == null && C.argv(IN, 3, SubFormat.SRT, "/sdcard/a\u0000.srt") == null)
    check("masuk == keluar ditolak (tidak menimpa video)", C.argv(IN, 3, SubFormat.SRT, IN) == null)
    val odd = "/sdcard/Molina/a b;rm -rf \"x\" \$(id).mkv"
    check("spasi, ; dan kutip tetap harfiah (tanpa shell)", C.argv(odd, 2, SubFormat.SRT, OUT)?.contains(odd) == true)
    check("tidak ada argumen berawalan - dari jalur", C.argv(IN, 3, SubFormat.SRT, OUT)!!.filter { it.startsWith("/") }.size == 2)
    check("errorLine: baris terakhir yang tidak kosong", C.errorLine("a\r\nGalat: stream 3 tidak ada\r\n\r\n") == "Galat: stream 3 tidak ada")
    check("errorLine: kosong", C.errorLine("") == "" && C.errorLine(" \n \n") == "")
    check("errorLine: dipotong 160", C.errorLine("x".repeat(500)).length == 160)
}

private fun embeddedTests() {
    val E = LyricsEmbedded
    check("tertanam: tanpa tag lirik -> null", E.pick(listOf("title" to "x", "artist" to "y")) == null)
    check("tertanam: kunci lyrics/USLT dikenali", E.pick(listOf("USLT" to "Halo")) == "Halo" && E.pick(listOf("lyrics-eng" to "Hai")) == "Hai")
    check("tertanam: nilai kosong diabaikan", E.pick(listOf("lyrics" to "  \n ")) == null)
    check("tertanam: utamakan yang bertimestamp", E.pick(listOf("unsyncedlyrics" to "polos", "lyrics" to "[00:01.00]Satu")) == "[00:01.00]Satu")
    check("tertanam: BOM dibuang", E.pick(listOf("lyrics" to "\uFEFFabc")) == "abc")
    check("tertanam: dipotong MAX_CHARS", E.pick(listOf("lyrics" to "x".repeat(E.MAX_CHARS + 50)))!!.length == E.MAX_CHARS)
}
