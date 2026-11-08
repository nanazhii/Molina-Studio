import com.molina.code.history.DiffOp
import com.molina.code.history.HistoryFormat
import com.molina.code.history.HistoryKind
import com.molina.code.history.HistoryStore
import com.molina.code.history.HistoryVersion
import com.molina.code.history.GutterHunk
import com.molina.code.history.GutterHunks
import com.molina.code.history.HunkRevert
import com.molina.code.history.LineDiff
import com.molina.code.history.VersionCompare
import com.molina.code.history.VersionDelta
import com.molina.code.history.WordDiff
import java.io.File
import java.nio.file.Files
import java.util.TimeZone

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

fun main() {
    val tmp = Files.createTempDirectory("histtest").toFile()
    try {
        var t = 1_000_000L
        val store = HistoryStore(File(tmp, "history")) { t }
        val f = File(tmp, "proj/App.kt")

        // ---- record / list / read
        check("kosong di awal", store.list(f).isEmpty())
        val v1 = store.record(f, "satu\ndua\n", HistoryKind.BEFORE)
        t += 10
        val v2 = store.record(f, "satu\ndua\ntiga\n", HistoryKind.SAVED)
        check("record mengembalikan versi", v1 != null && v2 != null)
        check("metadata: baris dan karakter", v2!!.lines == 3 && v2.chars == 14 && v2.kind == HistoryKind.SAVED)
        val l = store.list(f)
        check("list: terbaru dulu", l.size == 2 && l[0].name == v2.name && l[1].name == v1!!.name)
        check("read: isi persis", store.read(f, l[1]) == "satu\ndua\n" && store.read(f, l[0]) == "satu\ndua\ntiga\n")
        check("read: unicode", store.record(f, "héllo ✓ 日本\n", HistoryKind.SAVED).let { v -> v != null && store.read(f, v) == "héllo ✓ 日本\n" })

        // ---- dedupe dan monotonik
        check("dedupe: isi sama dengan terbaru = null", store.record(f, "héllo ✓ 日本\n", HistoryKind.BEFORE) == null)
        val a = store.record(f, "x\n", HistoryKind.SAVED)
        t -= 5_000 // jam mundur
        val b = store.record(f, "y\n", HistoryKind.SAVED)
        check("waktu tetap naik walau jam mundur", a != null && b != null && b.time > a.time && store.list(f).first().name == b.name)
        check("isi lama yang kembali tetap dicatat (bukan terbaru)", store.record(f, "x\n", HistoryKind.SAVED) != null)

        // ---- berkas berbeda terpisah, path kanonik sama
        val g = File(tmp, "proj/Other.kt")
        store.record(g, "lain\n", HistoryKind.SAVED)
        check("riwayat per berkas terpisah", store.list(g).size == 1 && store.list(f).size > 3)
        check("path tidak kanonik = berkas sama", store.list(File(tmp, "proj/../proj/App.kt")).size == store.list(f).size)

        // ---- batas versi
        val h = File(tmp, "many.txt")
        for (i in 1..(HistoryStore.MAX_VERSIONS + 15)) { t += 1; store.record(h, "isi $i\n", HistoryKind.SAVED) }
        val hl = store.list(h)
        check("pangkas: maksimal MAX_VERSIONS", hl.size == HistoryStore.MAX_VERSIONS)
        check("pangkas: yang terbaru bertahan", store.read(h, hl.first()) == "isi ${HistoryStore.MAX_VERSIONS + 15}\n")

        // ---- terlalu besar, rusak, hapus
        check("terlalu besar ditolak", store.record(File(tmp, "big.txt"), "x".repeat(HistoryStore.MAX_CHARS + 1), HistoryKind.SAVED) == null)
        val vv = store.list(g).first()
        File(tmp, "history").listFiles()!!.flatMap { d -> d.listFiles()!!.toList() }.first { it.name == vv.name }.writeBytes(byteArrayOf(1, 2, 3))
        check("snapshot rusak: read = null, tanpa crash", store.read(g, vv) == null)
        store.clear(g)
        check("clear menghapus riwayat berkas", store.list(g).isEmpty() && store.list(f).isNotEmpty())

        // ---- pangkas global
        store.trimGlobal(1)
        check("trimGlobal: dibuang sampai di bawah batas", store.list(f).size < 5 && store.list(h).size < 5)
        store.trimGlobal(0)
        check("trimGlobal 0: semua kosong dan folder dibersihkan", store.list(f).isEmpty() && File(tmp, "history").listFiles()!!.none { it.name.startsWith("h") })

        // ---- [CODE-HISTORY2] sematan (pin)
        val pf = File(tmp, "pin/Main.kt")
        t += 100
        val p1 = store.record(pf, "v1\n", HistoryKind.SAVED)!!
        t += 1
        store.record(pf, "v2\n", HistoryKind.SAVED)
        check("sematan: awalnya tidak ada", store.list(pf).none { it.pinned })
        check("sematan: setPin berhasil", store.setPin(pf, p1, true))
        check("sematan: terbaca di list", store.list(pf).count { it.pinned } == 1 && store.list(pf).first { it.pinned }.name == p1.name)
        check("sematan: isi tetap terbaca", store.read(pf, store.list(pf).first { it.pinned }) == "v1\n")
        for (i in 1..(HistoryStore.MAX_VERSIONS + 20)) { t += 1; store.record(pf, "isi pin $i\n", HistoryKind.SAVED) }
        val pl = store.list(pf)
        check("sematan: selamat dari pemangkasan", pl.any { it.name == p1.name && it.pinned })
        check("sematan: tidak dihitung ke batas versi", pl.count { !it.pinned } == HistoryStore.MAX_VERSIONS && pl.size == HistoryStore.MAX_VERSIONS + 1)
        check("sematan: versi terbaru tetap ada", store.read(pf, pl.first()) == "isi pin ${HistoryStore.MAX_VERSIONS + 20}\n")
        check("sematan: lepas", store.setPin(pf, p1, false) && store.list(pf).none { it.pinned })
        t += 1
        store.record(pf, "setelah lepas\n", HistoryKind.SAVED)
        check("sematan: setelah dilepas ikut terpangkas", store.list(pf).none { it.name == p1.name })
        // batas MAX_PINS
        val pg = File(tmp, "pin/Many.kt")
        val made = ArrayList<HistoryVersion>()
        for (i in 1..(HistoryStore.MAX_PINS + 2)) { t += 1; made.add(store.record(pg, "m$i\n", HistoryKind.SAVED)!!) }
        val okPins: List<Boolean> = made.map { v -> store.setPin(pg, v, true) }
        check("sematan: batas MAX_PINS", okPins.count { ok -> ok } == HistoryStore.MAX_PINS && !okPins.last())
        check("sematan: sematkan ulang yang sudah disematkan tetap sukses", store.setPin(pg, made[0], true))
        check("sematan: lepas membuka slot", store.setPin(pg, made[0], false) && store.setPin(pg, made.last(), true))
        check("sematan: snapshot hilang = gagal", !store.setPin(File(tmp, "pin/Ghost.kt"), made[0], true))
        // trimGlobal: yang tidak disematkan dibuang dulu
        val tg1 = File(tmp, "pin/G1.kt"); val tg2 = File(tmp, "pin/G2.kt")
        t += 1; val keep = store.record(tg1, "tua disematkan\n", HistoryKind.SAVED)!!
        store.setPin(tg1, keep, true)
        t += 1; store.record(tg1, "baru satu\n", HistoryKind.SAVED)
        t += 1; store.record(tg2, "lain satu\n", HistoryKind.SAVED)
        t += 1; store.record(tg2, "lain dua\n", HistoryKind.SAVED)
        store.trimGlobal(keep.bytes + 200)
        check("trimGlobal: sematan dibuang paling akhir", store.list(tg1).any { it.name == keep.name })
        store.clear(tg1)
        check("clear: sematan ikut hilang", store.list(tg1).isEmpty())
        // pins.txt rusak tidak merusak daftar
        val dirsNow = File(tmp, "history").listFiles()!!.filter { it.isDirectory }
        dirsNow.forEach { File(it, "pins.txt").writeBytes(byteArrayOf(0, -1, 7)) }
        check("pins.txt rusak: list tetap jalan", store.list(pg).isNotEmpty())

        // ---- LineDiff
        val d1 = LineDiff.diff("a\nb\nc\n", "a\nb\nc\n")
        check("diff: identik", !d1.changed && d1.lines.size == 3)
        val d2 = LineDiff.diff("a\nb\nc\nd\ne\n", "a\nB\nc\nd\ne\nf\n")
        check("diff: ubah + tambah", d2.added == 2 && d2.removed == 1)
        check("diff: urutan DEL sebelum ADD pada baris diganti", d2.lines.map { it.op } == listOf(DiffOp.SAME, DiffOp.DEL, DiffOp.ADD, DiffOp.SAME, DiffOp.SAME, DiffOp.SAME, DiffOp.ADD))
        val del = d2.lines.first { it.op == DiffOp.DEL }
        val add = d2.lines.first { it.op == DiffOp.ADD }
        check("diff: nomor baris", del.oldNo == 2 && del.newNo == 0 && add.newNo == 2 && add.oldNo == 0)
        check("diff: dari kosong", LineDiff.diff("", "x\ny\n").let { it.added == 2 && it.removed == 0 })
        check("diff: ke kosong", LineDiff.diff("x\ny\n", "").let { it.added == 0 && it.removed == 2 })
        val d3 = LineDiff.diff("1\n2\n3\n4\n5\n6\n", "1\n3\n4\n5\n7\n6\n")
        check("diff: LCS minimal", d3.removed == 1 && d3.added == 1)
        val rebuilt = d3.lines.filter { it.op != DiffOp.DEL }.joinToString("\n") { it.text } + "\n"
        check("diff: ADD+SAME menyusun ulang teks baru", rebuilt == "1\n3\n4\n5\n7\n6\n")
        val rebuiltOld = d3.lines.filter { it.op != DiffOp.ADD }.joinToString("\n") { it.text } + "\n"
        check("diff: DEL+SAME menyusun ulang teks lama", rebuiltOld == "1\n2\n3\n4\n5\n6\n")
        val bigA = (1..3000).joinToString("\n") { "a$it" } + "\n"
        val bigB = (1..3000).joinToString("\n") { "b$it" } + "\n"
        val d4 = LineDiff.diff(bigA, bigB)
        check("diff: terlalu besar = kasar tapi benar", d4.coarse && d4.removed == 3000 && d4.added == 3000)
        val c = LineDiff.compact(LineDiff.diff((1..20).joinToString("\n") { "l$it" } + "\n", (1..20).joinToString("\n") { if (it == 10) "X" else "l$it" } + "\n"), 2)
        check("compact: konteks 2 + ringkasan", c.count { it.skipped > 0 } == 2 && c.first().skipped == 7 && c.last().skipped == 8 && c.count { it.line != null } == 6)
        check("compact: tanpa perubahan = kosong", LineDiff.compact(d1).isEmpty())
        check("diff: beda akhir baris saja = tak berubah", !LineDiff.diff("a\nb", "a\nb\n").changed)

        // ---- format
        val tz = TimeZone.getTimeZone("UTC")
        val now = 1_730_203_200_000L // 29 Okt 2024 12:00 UTC
        check("waktu: hari ini", HistoryFormat.time(now - 3_600_000, now, tz) == "Hari ini 11:00")
        check("waktu: kemarin", HistoryFormat.time(now - 86_400_000, now, tz) == "Kemarin 12:00")
        check("waktu: tanggal", HistoryFormat.time(now - 5 * 86_400_000, now, tz) == "24 Okt 12:00")
        check("waktu: beda tahun", HistoryFormat.time(now - 400L * 86_400_000, now, tz).contains("2023"))
        check("ukuran", HistoryFormat.size(850) == "850 B" && HistoryFormat.size(1229) == "1,2 KB" && HistoryFormat.size(2 * 1024 * 1024) == "2,0 MB")
        check("countLines", HistoryStore.countLines("") == 0 && HistoryStore.countLines("a") == 1 && HistoryStore.countLines("a\n") == 1 && HistoryStore.countLines("a\nb") == 2)

        // ---- [CODE-HISTORY3] diff kata
        fun sub(s: String, rs: List<IntRange>) = rs.map { s.substring(it.first, it.last + 1) }
        val w1 = WordDiff.compare("val x = foo(bar)", "val x = foo(baz)")
        check("kata: satu kata berubah", w1.similar && sub("val x = foo(bar)", w1.old) == listOf("bar") && sub("val x = foo(baz)", w1.new) == listOf("baz"))
        val w2 = WordDiff.compare("alpha beta gamma", "alpha delta gamma epsilon")
        check("kata: ganti dan tambah, spasi tepi dibuang", sub("alpha beta gamma", w2.old) == listOf("beta") && sub("alpha delta gamma epsilon", w2.new) == listOf("delta", "epsilon"))
        check("kata: beda total = tidak mirip", !WordDiff.compare("return value", "import os").similar)
        val w3 = WordDiff.compare("a ".repeat(500), "b ".repeat(500))
        check("kata: terlalu panjang = tanpa penanda", !w3.similar && w3.old.isEmpty() && w3.new.isEmpty())
        check("kata: identik = tanpa penanda", WordDiff.compare("sama saja", "sama saja").let { it.old.isEmpty() && it.new.isEmpty() && it.similarity == 1.0 })
        val dl = LineDiff.diff("a\nval x = 1\nb\n", "a\nval x = 2\nb\n").lines
        val mk = WordDiff.mark(dl)
        val delL = dl.first { it.op == DiffOp.DEL }
        val addL = dl.first { it.op == DiffOp.ADD }
        check("kata: pasangan DEL/ADD ditandai", sub(delL.text, mk[delL].orEmpty()) == listOf("1") && sub(addL.text, mk[addL].orEmpty()) == listOf("2"))
        check("kata: baris SAME tidak ditandai", dl.filter { it.op == DiffOp.SAME }.all { mk[it] == null })
        val dl2 = LineDiff.diff("foo(1)\nbar(2)\n", "bar(3)\nfoo(4)\n").lines
        val mk2 = WordDiff.mark(dl2)
        val foo1 = dl2.first { it.op == DiffOp.DEL && it.text == "foo(1)" }
        val foo4 = dl2.first { it.op == DiffOp.ADD && it.text == "foo(4)" }
        check("kata: pasangan terbaik, bukan menurut urutan", sub(foo1.text, mk2[foo1].orEmpty()) == listOf("1") && sub(foo4.text, mk2[foo4].orEmpty()) == listOf("4"))
        val dl3 = LineDiff.diff("return value\n", "import os\n").lines
        check("kata: baris beda total tanpa penanda", WordDiff.mark(dl3).isEmpty())
        val long = "x".repeat(250) + "OLD" + "y".repeat(40)
        val (ws, wr) = WordDiff.window(long, listOf(250..252))
        check("window: tanda di ujung baris panjang tetap terlihat", ws.length <= 162 && ws.startsWith("\u2026") && wr.size == 1 && ws.substring(wr[0].first, wr[0].last + 1) == "OLD")
        val (ws2, wr2) = WordDiff.window("pendek", listOf(0..2))
        check("window: baris pendek utuh", ws2 == "pendek" && wr2 == listOf(0..2))
        val (ws3, wr3) = WordDiff.window("k".repeat(300), listOf(2..4))
        check("window: tanda di awal, ujung dipotong", ws3.endsWith("\u2026") && !ws3.startsWith("\u2026") && wr3 == listOf(2..4))
        check("ringkasan versi", VersionDelta.between("a\nb\n", "a\nc\n") == "\u22121 +1" && VersionDelta.between("a\n", "a\n") == "sama" &&
            VersionDelta.between(null, "a") == "" && VersionDelta.between("a\nb", "a\nb\n") == "baris kosong" &&
            VersionDelta.between("x".repeat(VersionDelta.MAX_CHARS + 1), "y") == "besar")

        // ---- [CODE-HISTORY4] versi pembanding (satu langkah lebih tua; daftar urut terbaru-dulu)
        fun pvMk(n: String) = HistoryVersion(n, 0L, HistoryKind.SAVED, "fp", 1, 1, 1L)
        val pvList = listOf(pvMk("c"), pvMk("b"), pvMk("a"))
        check("pembanding: versi tengah -> yang lebih tua", VersionCompare.previous(pvList, pvList[0])?.name == "b" && VersionCompare.previous(pvList, pvList[1])?.name == "a")
        check("pembanding: versi tertua = null", VersionCompare.previous(pvList, pvList[2]) == null)
        check("pembanding: tidak ada di daftar / daftar kosong = null", VersionCompare.previous(pvList, pvMk("z")) == null && VersionCompare.previous(emptyList(), pvList[0]) == null)
        check("pembanding: dicocokkan lewat nama, bukan objek", VersionCompare.previous(pvList, pvMk("c"))?.name == "b")

        // ---- [CODE-HISTORY5] "Sesudah skrip" berdampingan dengan "Sebelum skrip"
        val scrF = File(tmp, "proj/Run.kt")
        t += 10
        val scrB = store.record(scrF, "a\nb\n", HistoryKind.SCRIPT)
        t += 10
        val scrA = store.record(scrF, "a\nB\n", HistoryKind.AFTER_SCRIPT)
        val scrL = store.list(scrF)
        check("sesudah skrip: tercatat dan jenisnya terbaca kembali",
            scrB != null && scrA != null && scrL.size == 2 && scrL[0].kind == HistoryKind.AFTER_SCRIPT && scrL[1].kind == HistoryKind.SCRIPT &&
                HistoryKind.AFTER_SCRIPT.label == "Sesudah skrip")
        check("sesudah skrip: pembandingnya versi sebelum skrip", VersionCompare.previous(scrL, scrL[0])?.name == scrL[1].name)
        check("sesudah skrip: ringkasan = perubahan skrip", VersionDelta.between(store.read(scrF, scrL[1]), store.read(scrF, scrL[0])) == "\u22121 +1")
        check("sesudah skrip: isi sama dengan sebelum skrip tidak dicatat dua kali", store.record(scrF, "a\nB\n", HistoryKind.AFTER_SCRIPT) == null)
        check("fokus: versi terbaru berjenis tertentu",
            VersionCompare.latestOfKind(scrL, HistoryKind.AFTER_SCRIPT)?.name == scrL[0].name &&
                VersionCompare.latestOfKind(scrL, HistoryKind.SCRIPT)?.name == scrL[1].name &&
                VersionCompare.latestOfKind(scrL, HistoryKind.SAVED) == null &&
                VersionCompare.latestOfKind(emptyList(), HistoryKind.SAVED) == null)

        // ---- [CODE-HISTORY7] Kembalikan blok per hunk
        fun hr(x: String, y: String, target: String = y, run: Int = 0): String? =
            HunkRevert.revertAt(target, LineDiff.diff(x, y).lines, run)
        val h9 = "1\n2\n3\n4\n5\n6\n7\n8\n9\n"
        val h9y = "1\nX\n3\n4\n5\n6\n7\nY\n9\n"
        check("blok: ganti satu baris", hr("a\nb\nc\n", "a\nB\nc\n") == "a\nb\nc\n")
        check("blok: jumlah blok", HunkRevert.runs(LineDiff.diff(h9, h9y).lines).size == 2)
        check("blok: hanya blok kedua dikembalikan", hr(h9, h9y, run = 1) == "1\nX\n3\n4\n5\n6\n7\n8\n9\n")
        check("blok: nomor blok di luar jangkauan = null", hr(h9, h9y, run = 2) == null)
        check("blok: baris tambahan dihapus", hr("a\nc\n", "a\nb\nc\n") == "a\nc\n")
        check("blok: baris terhapus dipulihkan", hr("a\nb\nc\n", "a\nc\n") == "a\nb\nc\n")
        check("blok: target bergeser tetap ketemu", hr("a\nb\nc\n", "a\nB\nc\n", target = "NEW\na\nB\nc\n") == "NEW\na\nb\nc\n")
        check("blok: target sudah berubah = null", hr("a\nb\nc\n", "a\nB\nc\n", target = "a\nZ\nc\n") == null)
        check("blok: ambigu = null", hr("k\nb\nk\n", "k\nB\nk\n", target = "m\nm\nk\nB\nk\nk\nB\nk\n") == null)
        check("blok: tanpa baris baru di akhir tetap tanpa", hr("a\nb\nc\n", "a\nB\nc\n", target = "a\nB\nc") == "a\nb\nc")
        check("blok: berkas kosong jadi berisi", hr("a\n", "", target = "") == "a\n")
        val hLines = LineDiff.diff(h9, h9y).lines
        val hStep = HunkRevert.revertAt(h9y, hLines, 1)
        check("blok: dua blok dikembalikan berurutan = isi lama", hStep != null && HunkRevert.revertAt(hStep, hLines, 0) == h9)
        // ---- [CODE-HISTORY8] Ketuk gutter -> blok perubahan
        fun gh(x: String, y: String, line: Int): GutterHunk? = GutterHunks.find(x, y, line, y.split('\n').size)
        check("gutter: baris diubah", gh("a\nb\nc\n", "a\nB\nc\n", 1)?.let { it.oldLines == listOf("b") && it.newLines == listOf("B") && it.index == 0 } == true)
        check("gutter: baris tanpa perubahan = null", gh("a\nb\nc\n", "a\nB\nc\n", 0) == null)
        check("gutter: baris baru", gh("a\nc\n", "a\nb\nc\n", 1)?.let { it.oldLines.isEmpty() && it.newLines == listOf("b") } == true)
        check("gutter: blok banyak baris dipetakan ke semua barisnya", gh("a\nz\n", "a\nx\ny\nz\n", 2)?.lineLabel() == "2\u20133")
        check("gutter: penghapusan murni di tengah", gh("a\nb\nc\n", "a\nc\n", 1)?.let { it.pureDelete && it.oldLines == listOf("b") } == true)
        check("gutter: penghapusan di akhir berkas tanpa newline", gh("a\nb", "a", 0)?.pureDelete == true)
        check("gutter: nomor blok cocok dengan HunkRevert", gh(h9, h9y, 7)?.index == 1 && gh(h9, h9y, 1)?.index == 0)
        check("gutter: berkas sama = null", gh("a\nb\n", "a\nb\n", 0) == null)
        val gh7 = gh(h9, h9y, 7)
        check("gutter: urungkan lewat nomor blok", gh7 != null && HunkRevert.revertAt(h9y, LineDiff.diff(h9, h9y).lines, gh7.index) == "1\nX\n3\n4\n5\n6\n7\n8\n9\n")
    } finally {
        tmp.deleteRecursively()
    }
    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails != 0) kotlin.system.exitProcess(1)
}
