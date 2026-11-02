import com.molina.studio.cinema.ArchiveEngine
import com.molina.studio.cinema.CompressFormat
import com.molina.studio.cinema.CompressNames
import com.molina.studio.cinema.CompressOptions
import com.molina.studio.cinema.ExtractTarget
import com.molina.studio.cinema.FileOps
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

private var fails = 0
private var runs = 0
private fun check(name: String, ok: Boolean) { runs++; if (!ok) { fails++; println("GAGAL: $name") } }
private fun <T> eq(name: String, got: T, want: T) { runs++; if (got != want) { fails++; println("GAGAL: $name -> $got (seharusnya $want)") } }
private fun ctl(cancel: AtomicBoolean = AtomicBoolean(false), report: (Float) -> Unit = {}) = FileOps.Ctl(cancel, report)
private inline fun <reified E : Throwable> throwsA(name: String, body: () -> Unit): E? {
    runs++
    return try { body(); fails++; println("GAGAL: $name tidak melempar ${E::class.simpleName}"); null }
    catch (e: Throwable) { if (e is E) e else { fails++; println("GAGAL: $name melempar ${e.javaClass.simpleName}: ${e.message}"); null } }
}

private fun same(a: File, b: File): Boolean {
    if (!a.isDirectory || !b.isDirectory) return false
    fun tree(d: File) = d.walkTopDown().filter { it.isFile }.map { it.relativeTo(d).path to it.readBytes().toList() }.sortedBy { it.first }.toList()
    return tree(a) == tree(b)
}

private fun opt(fmt: CompressFormat, level: Int = fmt.defaultLevel, name: String = "src", pw: String? = null) = CompressOptions(fmt, level, name, pw)

fun main(args: Array<String>) {
    val fx = File(args[0])
    val keep = File(args[1]).apply { mkdirs() } // keluaran disimpan untuk diverifikasi pustaka lain (python)
    val src = File(fx, "src")
    val work = java.nio.file.Files.createTempDirectory("arccomp").toFile()
    var seq = 0
    fun fresh(): File = File(work, "d" + (seq++)).apply { mkdirs() }
    fun keepDir(n: String): File = File(keep, n).apply { deleteRecursively(); mkdirs() }
    try {
        // 1. semua format x level (min, bawaan, maks): hasil bisa dibaca mesin sendiri dan identik dengan sumber
        for (fmt in CompressFormat.values()) {
            val levels = listOf(fmt.minLevel, fmt.defaultLevel, fmt.maxLevel).distinct()
            for (lv in levels) {
                val tag = fmt.label + "-L" + lv
                val dest = keepDir(tag)
                val out = ArchiveEngine.compress(listOf(src), dest, opt(fmt, lv), ctl())
                eq("$tag: nama", out.name, "src" + fmt.ext)
                check("$tag: berkas ada dan tidak kosong", out.isFile && out.length() > 0)
                val l = ArchiveEngine.list(out)
                val names = l.entries.map { it.name.trimEnd('/') }
                check("$tag: daftar memuat src/sub/b.txt", names.contains("src/sub/b.txt"))
                check("$tag: daftar memuat src/emptydir", names.contains("src/emptydir"))
                val ex = ArchiveEngine.extract(out, fresh(), ExtractTarget.NAMED_FOLDER, ctl())
                check("$tag: isi identik setelah ekstrak", same(src, File(ex.dir, "src")))
                check("$tag: folder kosong terjaga", File(ex.dir, "src/emptydir").isDirectory)
                eq("$tag: berkas kosong terjaga", File(ex.dir, "src/empty.txt").length(), 0L)
            }
        }

        // 2. level memengaruhi ukuran (berkas yang bisa dikompres)
        run {
            val big = File(fresh(), "teks.txt").apply { writeText("molina cinema ".repeat(200_000)) }
            val sizes = HashMap<String, Long>()
            for (fmt in listOf(CompressFormat.ZIP, CompressFormat.SEVEN_Z, CompressFormat.TAR_GZ, CompressFormat.TAR_BZ2, CompressFormat.TAR_XZ, CompressFormat.TAR)) {
                val o = ArchiveEngine.compress(listOf(big), fresh(), opt(fmt, name = "t"), ctl())
                sizes[fmt.label] = o.length()
            }
            check("TAR tidak terkompresi >= ukuran asli", sizes["TAR"]!! >= big.length())
            for (k in listOf("ZIP", "7Z", "TAR.GZ", "TAR.BZ2", "TAR.XZ")) check("$k lebih kecil dari TAR untuk teks berulang", sizes[k]!! < sizes["TAR"]!! / 10)
            val store = ArchiveEngine.compress(listOf(big), fresh(), opt(CompressFormat.ZIP, 0, "t"), ctl())
            val best = ArchiveEngine.compress(listOf(big), fresh(), opt(CompressFormat.ZIP, 9, "t"), ctl())
            check("ZIP level 0 (simpan) >= ukuran asli", store.length() >= big.length())
            check("ZIP level 9 jauh lebih kecil dari level 0", best.length() < store.length() / 10)
            val s7store = ArchiveEngine.compress(listOf(big), fresh(), opt(CompressFormat.SEVEN_Z, 0, "t"), ctl())
            check("7z level 0 (COPY) >= ukuran asli", s7store.length() >= big.length())
            val zs = ArchiveEngine.compress(listOf(big), fresh(), opt(CompressFormat.ZIP, 0, "t", "rahasia"), ctl())
            check("ZIP AES level 0 >= ukuran asli", zs.length() >= big.length())
            val ze = ArchiveEngine.extract(zs, fresh(), ExtractTarget.HERE, ctl(), "rahasia")
            eq("ZIP AES level 0 isi benar", File(ze.dir, "teks.txt").readText().length, big.length().toInt())
        }

        // 3. ZIP berkata sandi (AES-256): tanpa/salah/benar
        for (lv in listOf(0, 6, 9)) {
            val dest = keepDir("ZIP-AES-L$lv")
            val z = ArchiveEngine.compress(listOf(src), dest, opt(CompressFormat.ZIP, lv, "src", "rahasia"), ctl())
            check("zip aes L$lv: terdeteksi terenkripsi", FileOps.isEncryptedZip(z))
            throwsA<FileOps.NeedPassword>("zip aes L$lv: tanpa sandi") { ArchiveEngine.extract(z, fresh(), ExtractTarget.NAMED_FOLDER, ctl()) }
            throwsA<FileOps.WrongPassword>("zip aes L$lv: sandi salah") { ArchiveEngine.extract(z, fresh(), ExtractTarget.NAMED_FOLDER, ctl(), "salah") }
            val ex = ArchiveEngine.extract(z, fresh(), ExtractTarget.NAMED_FOLDER, ctl(), "rahasia")
            check("zip aes L$lv: isi identik", same(src, File(ex.dir, "src")))
        }
        // format lain menolak sandi dengan pesan jelas
        for (fmt in listOf(CompressFormat.SEVEN_Z, CompressFormat.TAR, CompressFormat.TAR_GZ)) {
            val e = throwsA<IOException>("${fmt.label}: sandi ditolak") { ArchiveEngine.compress(listOf(src), fresh(), opt(fmt, pw = "x1234"), ctl()) }
            check("${fmt.label}: pesan sandi jelas", e?.message?.contains("kata sandi") == true)
        }
        run { // [ARCHIVE6] FAIL-CLOSED: engine bawaan tidak boleh menulis 7z TANPA sandi saat sandi diminta
            val dd = fresh()
            throwsA<IOException>("7z+sandi: ditolak engine") { ArchiveEngine.compress(listOf(src), dd, opt(CompressFormat.SEVEN_Z, pw = "x1234"), ctl()) }
            check("7z+sandi: tidak ada berkas tertulis", (dd.listFiles()?.size ?: 0) == 0)
        }

        // 4. penamaan: bentrok diberi (1), nama kustom, ekstensi ganda dicegah
        run {
            val d = fresh()
            for (fmt in listOf(CompressFormat.ZIP, CompressFormat.TAR_GZ, CompressFormat.SEVEN_Z)) {
                val a = ArchiveEngine.compress(listOf(src), d, opt(fmt), ctl())
                val b = ArchiveEngine.compress(listOf(src), d, opt(fmt), ctl())
                val c = ArchiveEngine.compress(listOf(src), d, opt(fmt), ctl())
                eq("${fmt.label}: nama 1", a.name, "src" + fmt.ext)
                eq("${fmt.label}: nama 2", b.name, "src (1)" + fmt.ext)
                eq("${fmt.label}: nama 3", c.name, "src (2)" + fmt.ext)
            }
            val k = ArchiveEngine.compress(listOf(src), d, opt(CompressFormat.SEVEN_Z, name = "Arsip Uji.7z"), ctl())
            eq("ekstensi ganda dicegah", k.name, "Arsip Uji.7z")
            val k2 = ArchiveEngine.compress(listOf(src), d, opt(CompressFormat.TAR_XZ, name = "  cadangan.TAR.XZ "), ctl())
            eq("ekstensi huruf besar + spasi", k2.name, "cadangan.tar.xz")
        }

        // 5. validasi masukan
        run {
            throwsA<IOException>("daftar kosong") { ArchiveEngine.compress(emptyList(), fresh(), opt(CompressFormat.ZIP), ctl()) }
            throwsA<IOException>("tujuan tidak ada") { ArchiveEngine.compress(listOf(src), File(work, "tidak-ada"), opt(CompressFormat.TAR_GZ), ctl()) }
            throwsA<IOException>("sumber tidak ada") { ArchiveEngine.compress(listOf(File(work, "hilang.txt")), fresh(), opt(CompressFormat.TAR_GZ), ctl()) }
            for (bad in listOf("", "   ", "..", "a/b", "a\\b")) {
                throwsA<IOException>("nama tidak valid '$bad'") { ArchiveEngine.compress(listOf(src), fresh(), opt(CompressFormat.TAR_GZ, name = bad), ctl()) }
            }
            throwsA<IOException>("nama terlalu panjang") { ArchiveEngine.compress(listOf(src), fresh(), opt(CompressFormat.ZIP, name = "x".repeat(300)), ctl()) }
            // dua item bernama sama dari folder berbeda
            val p1 = File(fresh(), "sama.txt").apply { writeText("1") }
            val p2 = File(fresh(), "sama.txt").apply { writeText("2") }
            val e = throwsA<IOException>("nama bentrok antar-folder") { ArchiveEngine.compress(listOf(p1, p2), fresh(), opt(CompressFormat.SEVEN_Z, name = "x"), ctl()) }
            check("pesan bentrok jelas", e?.message?.contains("bentrok") == true)
        }

        // 6. banyak item terpilih sekaligus + satu berkas tunggal
        run {
            val parent = fresh()
            File(parent, "a.txt").writeText("aaa")
            File(parent, "b.txt").writeText("bbb")
            File(parent, "sub").mkdirs(); File(parent, "sub/c.txt").writeText("ccc")
            val items = listOf(File(parent, "a.txt"), File(parent, "sub"), File(parent, "b.txt"))
            for (fmt in CompressFormat.values()) {
                val d = keepDir("multi-" + fmt.label)
                val o = ArchiveEngine.compress(items, d, opt(fmt, name = "pilihan"), ctl())
                val names = ArchiveEngine.list(o).entries.map { it.name.trimEnd('/') }.sorted()
                eq("${fmt.label}: banyak item", names, listOf("a.txt", "b.txt", "sub", "sub/c.txt"))
            }
            val one = ArchiveEngine.compress(listOf(File(parent, "a.txt")), fresh(), opt(CompressFormat.TAR_GZ, name = "satu"), ctl())
            val ex = ArchiveEngine.extract(one, fresh(), ExtractTarget.HERE, ctl())
            eq("berkas tunggal", File(ex.dir, "a.txt").readText(), "aaa")
        }

        // 7. progres sampai 100% dan pembatalan menghapus keluaran
        run {
            val bigDir = fresh()
            val big = File(bigDir, "acak.bin")
            val rnd = java.util.Random(11)
            big.outputStream().buffered().use { o -> val b = ByteArray(1 shl 20); repeat(24) { rnd.nextBytes(b); o.write(b) } }
            var last = 0f
            val o = ArchiveEngine.compress(listOf(bigDir), fresh(), opt(CompressFormat.TAR_GZ, name = "p"), ctl { last = it })
            check("progres mencapai 100%", last >= 0.999f)
            check("hasil gz berukuran wajar", o.length() > 20L * 1024 * 1024)
            for (fmt in CompressFormat.values()) {
                val d = fresh()
                val cancel = AtomicBoolean(false)
                val e = throwsA<IOException>("${fmt.label}: batal") {
                    ArchiveEngine.compress(listOf(bigDir), d, opt(fmt, name = "b"), ctl(cancel) { if (it > 0.1f) cancel.set(true) })
                }
                eq("${fmt.label}: pesan batal", e?.message, "Dibatalkan")
                eq("${fmt.label}: keluaran dibersihkan", d.listFiles()?.size, 0)
            }
        }

        // 8. tautan simbolik dilewati; arsip tidak memasukkan dirinya sendiri; nama unicode/panjang
        run {
            val tree = fresh()
            val root = File(tree, "proyek").apply { mkdirs() }
            File(root, "isi.txt").writeText("isi")
            val outside = File(fresh(), "luar.txt").apply { writeText("rahasia luar") }
            var linked = false
            try { java.nio.file.Files.createSymbolicLink(File(root, "tautan.txt").toPath(), outside.toPath()); linked = true }
            catch (e: Exception) { println("(lewati uji symlink: ${e.javaClass.simpleName})") }
            val uni = "ünïcødé 日本語 \u2764 ".trim() + ".txt"
            File(root, uni).writeText("unicode")
            val long = "n".repeat(180) + ".txt"
            File(root, long).writeText("panjang")
            for (fmt in CompressFormat.values()) {
                val d = keepDir("sym-" + fmt.label)
                // keluaran ditaruh DI DALAM folder sumber: tidak boleh ikut terkompres
                val o = ArchiveEngine.compress(listOf(root), root, opt(fmt, name = "proyek"), ctl())
                val names = ArchiveEngine.list(o).entries.map { it.name.trimEnd('/') }
                check("${fmt.label}: tidak memuat dirinya sendiri", names.none { it.endsWith(fmt.ext) })
                if (linked) check("${fmt.label}: tautan dilewati", names.none { it.endsWith("tautan.txt") })
                val ex = ArchiveEngine.extract(o, fresh(), ExtractTarget.NAMED_FOLDER, ctl())
                eq("${fmt.label}: unicode", File(ex.dir, "proyek/$uni").takeIf { it.exists() }?.readText(), "unicode")
                eq("${fmt.label}: nama panjang", File(ex.dir, "proyek/$long").takeIf { it.exists() }?.readText(), "panjang")
                eq("${fmt.label}: isi", File(ex.dir, "proyek/isi.txt").readText(), "isi")
                o.copyTo(File(d, o.name), overwrite = true)
                o.delete()
            }
            if (linked) {
                val only = File(fresh(), "hanya-tautan").apply { mkdirs() }
                java.nio.file.Files.createSymbolicLink(File(only, "t").toPath(), outside.toPath())
                val e = throwsA<IOException>("hanya tautan: tidak ada yang dikompres") {
                    ArchiveEngine.compress(listOf(File(only, "t")), fresh(), opt(CompressFormat.SEVEN_Z, name = "t"), ctl())
                }
                check("pesan tautan jelas", e?.message?.contains("tautan") == true)
            }
        }

        // 9. banyak berkas kecil (kinerja 7z: kamus per-berkas dikecilkan) dan tar.xz
        run {
            val many = File(fresh(), "banyak").apply { mkdirs() }
            for (i in 0 until 3000) File(many, "f%04d.txt".format(i)).writeText("baris $i\n".repeat(20))
            for (fmt in listOf(CompressFormat.SEVEN_Z, CompressFormat.TAR_XZ, CompressFormat.ZIP)) {
                val t0 = System.nanoTime()
                val o = ArchiveEngine.compress(listOf(many), keepDir("banyak-" + fmt.label), opt(fmt, name = "banyak"), ctl())
                val ms = (System.nanoTime() - t0) / 1_000_000
                println("   [info] 3000 berkas kecil ${fmt.label}: $ms ms, ${o.length()} B")
                check("${fmt.label}: 3000 berkas kecil selesai < 60 dtk", ms < 60_000)
                val ex = ArchiveEngine.extract(o, fresh(), ExtractTarget.NAMED_FOLDER, ctl())
                check("${fmt.label}: 3000 berkas identik", same(many, File(ex.dir, "banyak")))
            }
        }

        // 10. level di luar rentang dijepit (tidak melempar)
        run {
            for (lv in listOf(-5, 99)) for (fmt in CompressFormat.values()) {
                val o = ArchiveEngine.compress(listOf(src), fresh(), opt(fmt, lv), ctl())
                check("${fmt.label}: level $lv dijepit", o.isFile && o.length() > 0)
            }
        }

        // 11. logika nama/level murni
        run {
            eq("clean .zip", CompressNames.cleanBaseName("data.zip", CompressFormat.ZIP), "data")
            eq("clean tidak buang ekstensi format lain", CompressNames.cleanBaseName("data.zip", CompressFormat.TAR_GZ), "data.zip")
            eq("clean hanya ekstensi", CompressNames.cleanBaseName(".zip", CompressFormat.ZIP), ".zip")
            eq("outputName", CompressNames.outputName(" x.TAR.GZ ", CompressFormat.TAR_GZ), "x.tar.gz")
            eq("default satu berkas", CompressNames.defaultBaseName("lagu.mp3", false, "Musik"), "lagu")
            eq("default satu folder", CompressNames.defaultBaseName("Foto.2024", true, "DCIM"), "Foto.2024")
            eq("default banyak", CompressNames.defaultBaseName(null, false, "Unduhan"), "Unduhan")
            eq("default banyak tanpa induk", CompressNames.defaultBaseName(null, false, ""), "Arsip")
            eq("default tanpa ekstensi", CompressNames.defaultBaseName("Makefile", false, "p"), "Makefile")
            eq("default dotfile", CompressNames.defaultBaseName(".bashrc", false, "p"), ".bashrc")
            check("level jepit xz", CompressFormat.TAR_XZ.clampLevel(9) == 6 && CompressFormat.SEVEN_Z.clampLevel(9) == 6)
            check("level jepit gz", CompressFormat.TAR_GZ.clampLevel(0) == 1 && CompressFormat.ZIP.clampLevel(-3) == 0)
            check("TAR tanpa level", !CompressFormat.TAR.hasLevel && CompressFormat.TAR.clampLevel(5) == 0)
            check("label level simpan", CompressNames.levelLabel(CompressFormat.ZIP, 0).contains("Simpan"))
            check("label level seimbang", CompressNames.levelLabel(CompressFormat.ZIP, 6).contains("Seimbang"))
            check("label level terkecil", CompressNames.levelLabel(CompressFormat.ZIP, 9).contains("Terkecil"))
            eq("label tar kosong", CompressNames.levelLabel(CompressFormat.TAR, 0), "")
            eq("ukuran B", CompressNames.sizeText(512), "512 B")
            eq("ukuran MB", CompressNames.sizeText(5L * 1048576), "5.0 MB")
            val dd = fresh()
            File(dd, "a.zip").writeText("1")
            eq("unik pertama", CompressNames.uniqueOutput(dd, "a", ".zip").name, "a (1).zip")
            eq("unik bebas", CompressNames.uniqueOutput(dd, "b", ".zip").name, "b.zip")
        }
    } finally { work.deleteRecursively() }

    println(if (fails == 0) "LULUS: $runs kasus" else "$fails dari $runs kasus GAGAL")
    if (fails != 0) System.exit(1)
}
