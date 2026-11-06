import com.molina.cinema.*

private var failed = 0
internal fun check(name: String, cond: Boolean) {
    if (cond) println("OK   $name") else { println("GAGAL $name"); failed++ }
}

fun main() {
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
    check("SRT tanpa nomor urut tetap terbaca", SrtCodec.parse("00:00:01,000 --> 00:00:02,000\nX").size == 1)

    styleTests(::check)
    textFileTests(::check)
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

// --- SubTextFiles (berkas sementara)
internal fun textFileTests(check: (String, Boolean) -> Unit) {
    val root = java.nio.file.Files.createTempDirectory("molina-sub").toFile()
    try {
        val utf8 = java.io.File(root, "u.srt").also { it.writeText("1\ncaf\u00e9", Charsets.UTF_8) }
        check("UTF-8 terbaca", SubTextFiles.readText(utf8) == "1\ncaf\u00e9")
        val latin = java.io.File(root, "l.srt").also { it.writeBytes("1\ncaf\u00e9".toByteArray(charset("windows-1252"))) }
        check("fallback windows-1252", SubTextFiles.readText(latin) == "1\ncaf\u00e9")
        val big = java.io.File(root, "besar.srt").also { java.io.RandomAccessFile(it, "rw").use { r -> r.setLength(SubTextFiles.MAX_BYTES + 1) } }
        check("berkas terlalu besar diabaikan", SubTextFiles.readText(big) == null)
        check("berkas tidak ada = null", SubTextFiles.readText(java.io.File(root, "tidak-ada.srt")) == null)
    } finally { root.deleteRecursively() }
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
