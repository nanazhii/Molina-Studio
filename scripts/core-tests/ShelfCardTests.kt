import com.molina.core.common.*
import java.io.File
import java.nio.file.Files

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun put(dir: File, name: String, text: String = "x"): File = File(dir, name).also { it.writeText(text) }

fun main() {
    // ---- clock
    check("clock: 75 -> 1:15", ShelfCard.clock(75) == "1:15")
    check("clock: 3725 -> 1:02:05", ShelfCard.clock(3725) == "1:02:05")
    check("clock: negatif -> 0:00", ShelfCard.clock(-5) == "0:00")
    check("clock: 59 -> 0:59", ShelfCard.clock(59) == "0:59")

    // ---- monogram
    check("monogram: huruf kecil -> besar", ShelfCard.monogram("film") == "F")
    check("monogram: lewati simbol", ShelfCard.monogram("  [x] Judul") == "X")
    check("monogram: angka", ShelfCard.monogram("2001") == "2")
    check("monogram: kosong -> M", ShelfCard.monogram("") == "M" && ShelfCard.monogram("!!!") == "M")
    check("monogram: unicode", ShelfCard.monogram("\u00E9cole") == "\u00C9")

    val d = Files.createTempDirectory("shelf").toFile()
    try {
        // ---- video lokal dengan sidecar lengkap
        val v = put(d, "Film [abc].mp4")
        put(d, "Film [abc].jpg")
        put(d, "Film [abc].info.json", """{"title":"Pelajaran Bab 1","channel":"Kanal Belajar","duration":3725,"upload_date":"20240131"}""")
        val c = ShelfCard.of(ShelfCard.Entry(v.path, "Film [abc].mp4", true))
        check("of: judul dari info.json", c.title == "Pelajaran Bab 1")
        check("of: subtitle kanal + tahun", c.subtitle == "Kanal Belajar \u00B7 2024")
        check("of: durasi", c.duration == "1:02:05")
        check("of: poster terdeteksi + thumbPath", c.hasPoster && c.thumbPath == v.path)
        check("of: monogram dari judul info", c.monogram == "P")

        // ---- video lokal tanpa sidecar
        val plain = put(d, "Polos.mkv")
        val p = ShelfCard.of(ShelfCard.Entry(plain.path, "Polos.mkv", true))
        check("of: tanpa sidecar memakai judul riwayat", p.title == "Polos.mkv" && p.duration == null && !p.hasPoster)
        check("of: tanpa sidecar subtitle = nama folder", p.subtitle == d.name)
        check("of: tanpa sidecar tetap thumbPath video", p.thumbPath == plain.path)

        // ---- info.json rusak
        val bad = put(d, "Rusak.mp4")
        put(d, "Rusak.info.json", "bukan json {{{")
        val b = ShelfCard.of(ShelfCard.Entry(bad.path, "Rusak.mp4", true))
        check("of: info rusak tidak melempar, judul riwayat", b.title == "Rusak.mp4" && b.duration == null)

        // ---- audio: sidecar diabaikan, tanpa thumbPath
        val a = put(d, "Lagu.mp3")
        put(d, "Lagu.jpg")
        val ac = ShelfCard.of(ShelfCard.Entry(a.path, "Lagu.mp3", false))
        check("of: audio tanpa thumbPath dan tanpa poster sidecar", ac.thumbPath == null && !ac.hasPoster)

        // ---- URL
        val u = ShelfCard.of(ShelfCard.Entry("https://example.test/live/stream.m3u8", "", true))
        check("of: URL judul kosong -> host", u.title == "example.test")
        check("of: URL subtitle host, tanpa thumbPath", u.subtitle == "example.test" && u.thumbPath == null)
        val u2 = ShelfCard.of(ShelfCard.Entry("https://example.test/a", "Siaran", true))
        check("of: URL memakai judul riwayat", u2.title == "Siaran")

        // ---- berkas hilang
        val gone = ShelfCard.of(ShelfCard.Entry("/tidak/ada/Hilang.mp4", "", true))
        check("of: berkas hilang tidak melempar, judul dari nama", gone.title == "Hilang.mp4" && !gone.hasPoster)

        // ---- build
        val many = (1..12).map { ShelfCard.Entry("https://example.test/$it", "T$it", true) }
        val shelf = ShelfCard.build(many)
        check("build: dibatasi MAX_CARDS", shelf.size == ShelfCard.MAX_CARDS)
        check("build: urutan dipertahankan", shelf.first().title == "T1" && shelf.last().title == "T8")
        check("build: ref kosong disaring", ShelfCard.build(listOf(ShelfCard.Entry(" ", "x", true))).isEmpty())
        check("build: max negatif -> kosong", ShelfCard.build(many, -1).isEmpty())
    } finally {
        d.deleteRecursively()
    }

    // ---- ResumeMark / ShelfScan / SidecarRows
    check("resume: key MD5 huruf besar 32 hex", ShelfCard.Entry("/a", "", true).ref.let { ResumeMark.key(it) }.matches(Regex("[0-9A-F]{32}")))
    check("resume: key vektor MD5 'abc'", ResumeMark.key("abc") == "900150983CD24FB0D6963F7D28E17F72")
    check("resume: parse start", ResumeMark.parseStart("start=75.5\n") == 75.5)
    check("resume: start < 1 detik diabaikan", ResumeMark.parseStart("start=0.4") == null)
    check("resume: bukan angka -> null", ResumeMark.parseStart("start=abc") == null && ResumeMark.parseStart("x=1") == null)
    check("resume: NaN/Inf -> null", ResumeMark.parseStart("start=NaN") == null && ResumeMark.parseStart("start=Infinity") == null)
    check("resume: fraction normal", ResumeMark.fraction(50.0, 100) == 0.5f)
    check("resume: fraction durasi tak diketahui", ResumeMark.fraction(50.0, null) == null && ResumeMark.fraction(50.0, 0) == null)
    check("resume: fraction posisi >= durasi -> null", ResumeMark.fraction(120.0, 100) == null)
    check("resume: fraction dijepit", ResumeMark.fraction(1.0, 10000) == 0.02f && ResumeMark.fraction(9999.0, 10000) == 0.98f)

    val d2 = Files.createTempDirectory("shelf2").toFile()
    try {
        val wl = File(d2, "watch_later").also { it.mkdirs() }
        val wl2 = File(d2, "state/watch_later").also { it.mkdirs() }
        val vid = put(d2, "Lanjut.mp4")
        put(d2, "Lanjut.info.json", """{"title":"Lanjut","duration":200}""")
        File(wl2, ResumeMark.key(vid.path)).writeText("start=50.0\nspeed=1.0\n")
        check("resume: readStart mencari di beberapa folder", ResumeMark.readStart(listOf(wl, wl2), vid.path) == 50.0)
        check("resume: readStart tanpa berkas -> null", ResumeMark.readStart(listOf(wl), vid.path) == null)
        check("resume: readStart ref kosong -> null", ResumeMark.readStart(listOf(wl2), " ") == null)
        File(wl, ResumeMark.key("/rusak")).writeText("a".repeat(5000))
        check("resume: berkas terlalu besar diabaikan", ResumeMark.readStart(listOf(wl), "/rusak") == null)
        val rc = ShelfCard.of(ShelfCard.Entry(vid.path, "Lanjut.mp4", true), listOf(wl, wl2))
        check("card: resumeSec + fraction", rc.resumeSec == 50 && rc.resumeFraction == 0.25f)
        val nc = ShelfCard.of(ShelfCard.Entry(vid.path, "Lanjut.mp4", true))
        check("card: tanpa folder resume -> null", nc.resumeSec == null && nc.resumeFraction == null)
        val au = ShelfCard.of(ShelfCard.Entry(vid.path, "x", false), listOf(wl, wl2))
        check("card: audio tidak diberi penanda lanjut", au.resumeSec == null)
        val plainv = put(d2, "TanpaInfo.mkv")
        File(wl, ResumeMark.key(plainv.path)).writeText("start=30")
        val pc = ShelfCard.of(ShelfCard.Entry(plainv.path, "TanpaInfo.mkv", true), listOf(wl))
        check("card: lanjut tanpa durasi -> sec ada, fraction null", pc.resumeSec == 30 && pc.resumeFraction == null)
        val bc = ShelfCard.build(listOf(ShelfCard.Entry(vid.path, "L", true)), 8, listOf(wl, wl2))
        check("build: meneruskan resumeDirs", bc.single().resumeSec == 50)

        // ---- clear ("Mulai dari awal")
        val keepKey = ResumeMark.key("/lain/film.mp4")
        File(wl, keepKey).writeText("start=99")
        check("resume: clear menghapus di semua folder", ResumeMark.clear(listOf(wl, wl2), vid.path) && ResumeMark.readStart(listOf(wl, wl2), vid.path) == null)
        check("resume: clear tidak menyentuh kunci lain", File(wl, keepKey).isFile)
        check("resume: clear tanpa berkas -> false", !ResumeMark.clear(listOf(wl, wl2), vid.path))
        check("resume: clear ref kosong -> false", !ResumeMark.clear(listOf(wl), " "))
        check("resume: clear tanpa folder -> false", !ResumeMark.clear(emptyList(), "/x"))

        // ---- move (ganti nama / pindah)
        File(wl, ResumeMark.key("/a/lama.mp4")).writeText("start=42")
        check("resume: move memindah penanda", ResumeMark.move(listOf(wl, wl2), "/a/lama.mp4", "/b/baru.mp4") &&
            ResumeMark.readStart(listOf(wl), "/b/baru.mp4") == 42.0 && ResumeMark.readStart(listOf(wl), "/a/lama.mp4") == null)
        File(wl, ResumeMark.key("/c/x.mp4")).writeText("start=10")
        File(wl, ResumeMark.key("/c/y.mp4")).writeText("start=20")
        check("resume: move tidak menimpa penanda tujuan", !ResumeMark.move(listOf(wl), "/c/x.mp4", "/c/y.mp4") &&
            ResumeMark.readStart(listOf(wl), "/c/y.mp4") == 20.0 && ResumeMark.readStart(listOf(wl), "/c/x.mp4") == 10.0)
        check("resume: move tanpa sumber/ref sama/kosong -> false",
            !ResumeMark.move(listOf(wl), "/tidak/ada.mp4", "/z.mp4") && !ResumeMark.move(listOf(wl), "/q", "/q") && !ResumeMark.move(listOf(wl), " ", "/q"))

        // ---- ShelfScan
        val dl = File(d2, "dl").also { it.mkdirs() }
        val old = put(dl, "lama.mp4").also { it.setLastModified(1_000_000_000_000L) }
        val neu = put(dl, "baru.mkv").also { it.setLastModified(1_700_000_000_000L) }
        put(dl, "catatan.txt"); put(dl, "film.mp4.part"); put(dl, "poster.jpg"); put(dl, ".tersembunyi.mp4")
        val sub = File(dl, "playlist").also { it.mkdirs() }
        val mid = put(sub, "tengah.webm").also { it.setLastModified(1_500_000_000_000L) }
        val deep = File(File(sub, "a"), "b").also { it.mkdirs() }
        put(deep, "terlalu-dalam.mp4")
        val sc = ShelfScan.scan(dl)
        check("scan: hanya video, terbaru dulu", sc.map { it.title } == listOf("baru", "tengah", "lama"))
        check("scan: entri berjenis video dengan path", sc.all { it.video } && sc.first().ref == neu.path)
        check("scan: kedalaman dibatasi", sc.none { it.title == "terlalu-dalam" })
        check("scan: dibatasi max", ShelfScan.scan(dl, 2).size == 2 && ShelfScan.scan(dl, 0).isEmpty())
        check("scan: folder tidak ada -> kosong", ShelfScan.scan(File(d2, "tidak-ada")).isEmpty())
        check("scan: ekstensi besar-kecil", put(dl, "BESAR.MP4").let { ShelfScan.scan(dl).any { e -> e.title == "BESAR" } })
        check("scan: mp4 sama dgn lama", old.exists() && mid.exists())

        // ---- SidecarRows
        val rows = SidecarRows.of(MediaSidecar.Info("T", "K", 10, 2023, "Desk", "https://x.test/v"))
        check("rows: urutan dan label", rows.map { it.first } == listOf("Judul unduhan", "Kanal", "Tahun", "Deskripsi", "Sumber"))
        check("rows: nilai", rows[2].second == "2023" && rows[4].second == "https://x.test/v")
        check("rows: null -> kosong", SidecarRows.of(null).isEmpty())
        check("rows: field null dilewati", SidecarRows.of(MediaSidecar.Info(null, "K", null, null, null, null)).map { it.first } == listOf("Kanal"))
    } finally {
        d2.deleteRecursively()
    }
    if (fails > 0) { println("$fails GAGAL"); System.exit(1) }
    println("semua lulus")
}
