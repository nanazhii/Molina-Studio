import com.molina.core.common.SevenZipCommand
import com.molina.core.common.SevenZipCommand.Failure
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}
private fun throwsIae(block: () -> Unit): Boolean = try { block(); false } catch (e: IllegalArgumentException) { true }

/** Keluaran nyata `7z l -slt -ba` (7-Zip 23.01) untuk arsip berisi folder, berkas, dan tautan yang diikuti. */
private val REAL_LIST = """
Path = src
Size = 0
Packed Size = 0
Modified = 2026-10-30 02:05:51.8757346
Attributes = D drwxr-xr-x
CRC = 
Encrypted = -
Method = 
Block = 

Path = src/a.txt
Size = 6
Packed Size = 5017
Modified = 2026-10-30 02:05:51.8705890
Attributes = A -rw-r--r--
CRC = 363A3020
Encrypted = -
Method = LZMA2:6k
Block = 0

Path = src/lnk
Size = 6
Packed Size = 
Modified = 2026-10-30 02:05:51.8705890
Attributes = A -rw-r--r--
CRC = 363A3020
Encrypted = -
Method = LZMA2:6k
Block = 0

Path = src/spasi nama é.txt
Size = 1
Packed Size = 
Attributes = A -rw-r--r--
Encrypted = +
Method = LZMA2:6k 7zAES:19
Block = 0

""".trimIndent()

private fun run7z(bin: String, args: List<String>, stdin: ByteArray?, cwd: File): Pair<Int, String> {
    val pb = ProcessBuilder(listOf(bin) + args).directory(cwd).redirectErrorStream(true)
    pb.environment()["LANG"] = "C.UTF-8"
    val p = pb.start()
    if (stdin != null) p.outputStream.write(stdin)
    try { p.outputStream.close() } catch (e: Exception) { }
    val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
    if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); return -9 to out }
    return p.exitValue() to out
}

fun main() {
    // guestPath
    check("guestPath: /storage/emulated/0/x -> /sdcard/x", SevenZipCommand.guestPath("/storage/emulated/0/Download/a.7z") == "/sdcard/Download/a.7z")
    check("guestPath: /sdcard/x tetap", SevenZipCommand.guestPath("/sdcard/a.7z") == "/sdcard/a.7z")
    check("guestPath: akar penyimpanan", SevenZipCommand.guestPath("/storage/emulated/0") == "/sdcard")
    check("guestPath: di luar penyimpanan bersama -> null", SevenZipCommand.guestPath("/data/data/com.molina/files/a.7z") == null)
    check("guestPath: awalan mirip tapi beda folder -> null", SevenZipCommand.guestPath("/storage/emulated/01/a.7z") == null && SevenZipCommand.guestPath("/sdcardx/a") == null)
    check("guestPath: '..' ditolak", SevenZipCommand.guestPath("/sdcard/../data/x") == null)
    check("guestPath: NUL/baris baru ditolak", SevenZipCommand.guestPath("/sdcard/a\u0000b") == null && SevenZipCommand.guestPath("/sdcard/a\nb") == null)
    check("guestPath: kosong -> null", SevenZipCommand.guestPath("") == null)

    // argv
    val la = SevenZipCommand.listArgs("/sdcard/a*b.7z")
    check("listArgs: -spd dan -- sebelum jalur", la == listOf("l", "-slt", "-ba", "-spd", "--", "/sdcard/a*b.7z"))
    val xa = SevenZipCommand.extractArgs("/sdcard/-x.7z", "/sdcard/.st ag")
    check("extractArgs: -o<dir> tanpa spasi pemisah", "-o/sdcard/.st ag" in xa && "-o" !in xa)
    check("extractArgs: -spd, -- lalu jalur arsip terakhir", "-spd" in xa && xa[xa.size - 2] == "--" && xa.last() == "/sdcard/-x.7z")
    check("extractArgs: tidak ada -p (sandi bukan di argumen)", xa.none { it.startsWith("-p") })
    check("argv: jalur relatif ditolak", throwsIae { SevenZipCommand.listArgs("a.7z") } && throwsIae { SevenZipCommand.extractArgs("/sdcard/a.7z", "out") })
    check("argv: baris baru di jalur ditolak", throwsIae { SevenZipCommand.listArgs("/sdcard/a\nb.7z") })

    // stdin sandi
    check("stdinFor: null/kosong -> null", SevenZipCommand.stdinFor(null) == null && SevenZipCommand.stdinFor("") == null)
    check("stdinFor: satu baris UTF-8", SevenZipCommand.stdinFor("sandi é")!!.toString(Charsets.UTF_8) == "sandi é\n")
    check("stdinFor: baris baru di sandi ditolak", throwsIae { SevenZipCommand.stdinFor("a\nb") } && throwsIae { SevenZipCommand.stdinFor("a\rb") })

    // prootArgs
    val pa = SevenZipCommand.prootArgs("/data/rootfs", "/storage/emulated/0", "/usr/bin/7z", SevenZipCommand.listArgs("/sdcard/a.7z"))
    check("prootArgs: bind /sdcard dan /storage/emulated/0", "--bind=/storage/emulated/0:/sdcard" in pa && "--bind=/storage/emulated/0:/storage/emulated/0" in pa)
    check("prootArgs: biner langsung, tanpa python3", "/usr/bin/7z" in pa && pa.none { it.contains("python") })
    check("prootArgs: env bersih + UTF-8", pa.indexOf("/usr/bin/env") < pa.indexOf("/usr/bin/7z") && "-i" in pa && "LANG=C.UTF-8" in pa)
    check("prootArgs: argumen 7z di akhir, urut", pa.takeLast(6) == SevenZipCommand.listArgs("/sdcard/a.7z"))
    check("prootArgs: kill-on-exit", "--kill-on-exit" in pa)

    // parseList (keluaran nyata)
    val es = SevenZipCommand.parseList(REAL_LIST)
    check("parseList: 4 entri", es.size == 4)
    check("parseList: folder terdeteksi", es[0].path == "src" && es[0].isDir)
    check("parseList: berkas, ukuran, metode", es[1].path == "src/a.txt" && !es[1].isDir && es[1].size == 6L && es[1].method == "LZMA2:6k")
    check("parseList: Packed Size kosong -> 0", es[2].packed == 0L)
    check("parseList: nama unicode dan spasi", es[3].path == "src/spasi nama é.txt")
    check("parseList: Encrypted = +", es[3].encrypted && !es[1].encrypted)
    check("parseList: prompt sandi dan baris sampah dilewati", SevenZipCommand.parseList("\nEnter password (will not be echoed):\n" + REAL_LIST).size == 4)
    check("parseList: kosong -> kosong", SevenZipCommand.parseList("").isEmpty() && SevenZipCommand.parseList("ERROR: x\n").isEmpty())
    check("parseList: CRLF", SevenZipCommand.parseList(REAL_LIST.replace("\n", "\r\n")).size == 4)
    val lnk = SevenZipCommand.parseList("Path = l\nSize = 1\nAttributes = A lrwxrwxrwx\n")
    check("parseList: tautan terdeteksi dari mode unix", lnk.single().isLink)
    val dirFlag = SevenZipCommand.parseList("Path = d\nFolder = +\n")
    check("parseList: Folder = + terdeteksi", dirFlag.single().isDir)
    check("parseList: tanpa baris kosong antar blok tetap terpisah", SevenZipCommand.parseList("Path = a\nSize = 1\nPath = b\nSize = 2\n").map { it.path } == listOf("a", "b"))

    // safeEntryPath
    check("safeEntryPath: normal", SevenZipCommand.safeEntryPath("a/b/c.txt") == "a/b/c.txt")
    check("safeEntryPath: ./ dan // dibersihkan", SevenZipCommand.safeEntryPath("./a//b") == "a/b")
    check("safeEntryPath: .. ditolak", SevenZipCommand.safeEntryPath("a/../../x") == null && SevenZipCommand.safeEntryPath("..") == null)
    check("safeEntryPath: absolut ditolak", SevenZipCommand.safeEntryPath("/etc/passwd") == null)
    check("safeEntryPath: drive dan backslash ditolak", SevenZipCommand.safeEntryPath("C:/x") == null && SevenZipCommand.safeEntryPath("a\\b") == null)
    check("safeEntryPath: NUL dan kosong ditolak", SevenZipCommand.safeEntryPath("a\u0000b") == null && SevenZipCommand.safeEntryPath("") == null && SevenZipCommand.safeEntryPath("./") == null)
    check("safeEntryPath: nama berisi titik dua ganda tetap sah", SevenZipCommand.safeEntryPath("a..b/c") == "a..b/c")

    // classify (pesan nyata)
    check("classify: prompt tanpa sandi -> NEED_PASSWORD", SevenZipCommand.classify(255, "\nEnter password (will not be echoed):\n\n\nBreak signaled\n", false) == Failure.NEED_PASSWORD)
    check("classify: header terenkripsi, sandi salah", SevenZipCommand.classify(2, "\nERROR: enc_he.7z : Cannot open encrypted archive. Wrong password?\n", true) == Failure.WRONG_PASSWORD)
    check("classify: berkas terenkripsi, sandi salah (bukan DATA_ERROR)", SevenZipCommand.classify(2, "ERROR: Data Error in encrypted file. Wrong password? : src/sub/b.bin\n\nSub items Errors: 4\n", true) == Failure.WRONG_PASSWORD)
    check("classify: volume hilang", SevenZipCommand.classify(2, "\nERRORS:\nUnexpected end of archive\n\nCan't open as archive: 1\n", false) == Failure.MISSING_VOLUME)
    check("classify: bukan arsip", SevenZipCommand.classify(2, "\nERROR: junk.rar : Cannot open the file as archive\n", false) == Failure.NOT_ARCHIVE)
    check("classify: argumen salah", SevenZipCommand.classify(7, "\n\nCommand Line Error:\nToo short switch:\n-o\n", false) == Failure.BAD_COMMAND)
    check("classify: metode tidak didukung", SevenZipCommand.classify(2, "ERROR: Unsupported Method : a.txt\n", false) == Failure.UNSUPPORTED_METHOD)
    check("classify: ruang penuh", SevenZipCommand.classify(2, "ERROR: No space left on device : x\n", false) == Failure.DISK_FULL)
    check("classify: memori via kode 8", SevenZipCommand.classify(8, "", false) == Failure.OUT_OF_MEMORY)
    check("classify: CRC", SevenZipCommand.classify(2, "CRC Failed : a.txt\n", false) == Failure.DATA_ERROR)
    check("classify: dihentikan 255 tanpa prompt -> CANCELLED", SevenZipCommand.classify(255, "", true) == Failure.CANCELLED)
    check("classify: tak dikenal", SevenZipCommand.classify(2, "sesuatu yang aneh", false) == Failure.UNKNOWN)
    check("classify: sandi benar + ruang penuh tidak salah dilabeli sandi", SevenZipCommand.classify(2, "Enter password (will not be echoed):\nERROR: No space left on device : x\n", true) == Failure.DISK_FULL)
    check("isSuccess: hanya 0", SevenZipCommand.isSuccess(0) && !SevenZipCommand.isSuccess(1) && !SevenZipCommand.isSuccess(2))
    check("Failure.hint: semua terisi", Failure.values().all { it.hint.isNotBlank() })

    // progres
    check("parseProgress: persen terakhir dari keluaran -bsp1 nyata", SevenZipCommand.parseProgress("  0M Scan\b\b\b\b\b\b\b\b\b         \b\b\b\b\b\b\b\b\b  0%\b\b\b\b    \b\b\b\b 37%\b\b\b\b 64%") == 64)
    check("parseProgress: tanpa persen -> null", SevenZipCommand.parseProgress("Scan\b\b") == null)
    check("parseProgress: 100", SevenZipCommand.parseProgress(" 100%") == 100)
    check("parseProgress: >100 diabaikan", SevenZipCommand.parseProgress("250%") == null)
    check("cleanOutput: prompt dan persen dibuang, galat tetap", SevenZipCommand.cleanOutput("  0M Scan\b\b  5%\b\b\r\nERROR: x : y\n\n").contains("ERROR: x : y") && !SevenZipCommand.cleanOutput("  5%\b\b").contains("%"))

    // [ARCHIVE4] bind berlingkup, ekstrak sebagian, pembaca daftar bertahap
    val scoped = SevenZipCommand.prootArgsScoped("/r", listOf("/sdcard/Download" to SevenZipCommand.GUEST_SRC, "/sdcard/Download/.molina-stage-x" to SevenZipCommand.GUEST_OUT), "/usr/bin/7z", listOf("l", "--", "/mnt/molina-src/a.rar"))
    check("scoped: hanya dua bind penyimpanan, tidak ada bind /sdcard penuh", scoped.count { it.startsWith("--bind=/sdcard") } == 2 && scoped.none { it == "--bind=/storage/emulated/0:/sdcard" })
    check("scoped: bind host:guest benar", "--bind=/sdcard/Download:/mnt/molina-src" in scoped && "--bind=/sdcard/Download/.molina-stage-x:/mnt/molina-out" in scoped)
    check("scoped: biner dan args di akhir, env bersih", scoped.takeLast(4) == listOf("/usr/bin/7z", "l", "--", "/mnt/molina-src/a.rar") && "-i" in scoped && "--kill-on-exit" in scoped)
    check("scoped: host mengandung ':' ditolak", throwsIae { SevenZipCommand.prootArgsScoped("/r", listOf("/sdcard/a:b" to "/mnt/x"), "/usr/bin/7z", emptyList()) })
    check("scoped: host relatif/.. ditolak", throwsIae { SevenZipCommand.prootArgsScoped("/r", listOf("sdcard" to "/mnt/x"), "/usr/bin/7z", emptyList()) } && throwsIae { SevenZipCommand.prootArgsScoped("/r", listOf("/a/../b" to "/mnt/x"), "/usr/bin/7z", emptyList()) })
    check("scoped: guest relatif ditolak", throwsIae { SevenZipCommand.prootArgsScoped("/r", listOf("/sdcard/a" to "mnt/x"), "/usr/bin/7z", emptyList()) })
    check("bindHostOk", SevenZipCommand.bindHostOk("/storage/emulated/0/Download") && !SevenZipCommand.bindHostOk("/a\nb") && !SevenZipCommand.bindHostOk(""))

    val partial = SevenZipCommand.extractArgs("/mnt/molina-src/a.7z", "/mnt/molina-out", listOf("src/a.txt", "src/sub/"))
    check("extractArgs sebagian: nama setelah arsip, akhiran / dibuang", partial.takeLast(3) == listOf("/mnt/molina-src/a.7z", "src/a.txt", "src/sub") && partial.indexOf("--") < partial.indexOf("src/a.txt"))
    check("extractArgs sebagian: kosong = sama dengan penuh", SevenZipCommand.extractArgs("/a.7z", "/o", emptyList()) == SevenZipCommand.extractArgs("/a.7z", "/o"))
    check("extractArgs sebagian: '..' ditolak", throwsIae { SevenZipCommand.extractArgs("/a.7z", "/o", listOf("../x")) })
    check("extractArgs sebagian: '@' dan '!' ditolak", throwsIae { SevenZipCommand.extractArgs("/a.7z", "/o", listOf("@list")) } && throwsIae { SevenZipCommand.extractArgs("/a.7z", "/o", listOf("!x")) })
    check("extractArgs sebagian: nama berawalan '-' aman setelah --", SevenZipCommand.extractArgs("/a.7z", "/o", listOf("-rf")).takeLast(2) == listOf("/a.7z", "-rf"))

    run {
        val all = ArrayList<SevenZipCommand.Entry>()
        val trunc = SevenZipCommand.parseListStream(java.io.BufferedReader(java.io.StringReader(REAL_LIST)), 1000) { all.add(it) }
        check("parseListStream: sama dengan parseList, tidak terpotong", !trunc && all.map { it.path } == SevenZipCommand.parseList(REAL_LIST).map { it.path } && all.isNotEmpty())
        val two = ArrayList<SevenZipCommand.Entry>()
        val t2 = SevenZipCommand.parseListStream(java.io.BufferedReader(java.io.StringReader(REAL_LIST)), 2) { two.add(it) }
        check("parseListStream: batas entri -> terpotong", t2 && two.size == 2)
        val exact = ArrayList<SevenZipCommand.Entry>()
        val t3 = SevenZipCommand.parseListStream(java.io.BufferedReader(java.io.StringReader(REAL_LIST)), all.size) { exact.add(it) }
        check("parseListStream: batas pas jumlah -> tidak terpotong", !t3 && exact.size == all.size)
        val noisy = "Enter password (will not be echoed):\n" + REAL_LIST
        val n = ArrayList<SevenZipCommand.Entry>()
        SevenZipCommand.parseListStream(java.io.BufferedReader(java.io.StringReader(noisy)), 1000) { n.add(it) }
        check("parseListStream: prompt di depan tidak merusak entri pertama", n.map { it.path } == all.map { it.path })
    }

    // [ARCHIVE6] createArgs: bentuk argumen, sandi tidak pernah di argumen, penolakan masukan berbahaya
    run {
        val g = listOf("/mnt/molina-in/0/a.txt", "/mnt/molina-in/0/-aneh (1).txt", "/mnt/molina-in/1/dir")
        val out = SevenZipCommand.GUEST_OUT + "/" + SevenZipCommand.OUT_FILE
        val a = SevenZipCommand.createArgs(out, g, 5, hasPassword = true, encryptNames = true)
        check("createArgs: bentuk penuh (isi + nama)", a == listOf("a", "-t7z", "-mx=5", "-snl", "-spd", "-bsp1", "-y", "-p", "-mhe=on", "--", out) + g)
        val b = SevenZipCommand.createArgs(out, g, 0, hasPassword = true, encryptNames = false)
        check("createArgs: isi saja tanpa -mhe", "-mhe=on" !in b && "-p" in b && "-mx=0" in b)
        check("createArgs: -p tanpa nilai (sandi hanya lewat stdin)", a.none { it.startsWith("-p") && it.length > 2 })
        check("createArgs: -- mendahului keluaran dan sumber", a.indexOf("--") < a.indexOf(out) && g.all { a.indexOf("--") < a.indexOf(it) })
        check("createArgs: -spd dan -snl ada", "-spd" in a && "-snl" in a)
        val c = SevenZipCommand.createArgs(out, g, 3, hasPassword = false, encryptNames = false)
        check("createArgs: tanpa sandi tanpa -p", "-p" !in c)
        check("createArgs: tolak -mhe tanpa sandi", throwsIae { SevenZipCommand.createArgs(out, g, 5, hasPassword = false, encryptNames = true) })
        check("createArgs: tolak level 10 dan -1", throwsIae { SevenZipCommand.createArgs(out, g, 10, true, false) } && throwsIae { SevenZipCommand.createArgs(out, g, -1, true, false) })
        check("createArgs: tolak sumber kosong", throwsIae { SevenZipCommand.createArgs(out, emptyList(), 5, true, false) })
        check("createArgs: tolak sumber relatif", throwsIae { SevenZipCommand.createArgs(out, listOf("rel/a.txt"), 5, true, false) })
        check("createArgs: tolak keluaran relatif", throwsIae { SevenZipCommand.createArgs("archive.7z", g, 5, true, false) })
        val ex = SevenZipCommand.createArgs(out, g, 5, true, true, listOf("foto/ln-dir", "a b/ln *.txt"))
        check("createArgs: -x! per pengecualian sebelum --", ex.count { it.startsWith("-x!") } == 2 && "-x!a b/ln *.txt" in ex && ex.indexOf("-x!foto/ln-dir") < ex.indexOf("--"))
        check("createArgs: tolak pengecualian absolut/kosong/baris baru", listOf("/etc", "", "a\nb").all { bad -> throwsIae { SevenZipCommand.createArgs(out, g, 5, true, true, listOf(bad)) } })
        check("createArgs: tolak * atau ? di komponen terakhir sumber (7z diam-diam abaikan sumber hilang)", listOf("/mnt/molina-in/0/a*b", "/mnt/molina-in/0/x?y", "/mnt/molina-in/0/-aneh *.txt").all { bad -> throwsIae { SevenZipCommand.createArgs(out, listOf(bad), 5, true, false) } })
        check("createArgs: * di folder induk guest (bukan komponen terakhir) tidak ditolak", SevenZipCommand.createArgs(out, listOf("/mnt/molina-in/0/d*/in"), 5, true, false).isNotEmpty())
        check("createArgs: tolak baris baru di sumber", throwsIae { SevenZipCommand.createArgs(out, listOf("/mnt/molina-in/0/a\nb"), 5, true, false) })
        check("konstanta: GUEST_IN tidak sama dengan SRC/OUT", SevenZipCommand.GUEST_IN != SevenZipCommand.GUEST_SRC && SevenZipCommand.GUEST_IN != SevenZipCommand.GUEST_OUT)
        // bind berlingkup untuk kompres: n folder sumber + staging, tanpa bind penyimpanan lain
        val argv = SevenZipCommand.prootArgsScoped("/rootfs", listOf("/sdcard/A" to "/mnt/molina-in/0", "/sdcard/B" to "/mnt/molina-in/1", "/sdcard/.molina-stage-x" to SevenZipCommand.GUEST_OUT), "/usr/bin/7z", a)
        check("proot kompres: tepat 3 bind tambahan + /dev /proc /sys", argv.count { it.startsWith("--bind=") } == 6)
        check("proot kompres: tidak ada bind /sdcard penuh", argv.none { it == "--bind=/sdcard:/sdcard" || it.endsWith(":/storage/emulated/0") })
        // classifyCreate: exit 1 fatal; sisanya seperti classify
        check("classifyCreate: exit 1 = SOURCE_UNREADABLE", SevenZipCommand.classifyCreate(1, "WARNING: No such file", true) == Failure.SOURCE_UNREADABLE)
        check("classifyCreate: disk penuh", SevenZipCommand.classifyCreate(2, "ERROR: No space left on device", true) == Failure.DISK_FULL)
        check("classifyCreate: memori", SevenZipCommand.classifyCreate(8, "", true) == Failure.OUT_OF_MEMORY)
        check("classifyCreate: dihentikan", SevenZipCommand.classifyCreate(255, "Break signaled", true) == Failure.CANCELLED)
        check("classifyCreate: prompt sandi TIDAK jadi NEED_PASSWORD saat sandi sudah diberikan", SevenZipCommand.classifyCreate(2, "Enter password (will not be echoed):\nERROR", true) != Failure.NEED_PASSWORD)
    }

    // integrasi opsional dengan 7z asli: SEVENZIP_BIN=/usr/bin/7z
    val bin = System.getenv("SEVENZIP_BIN")
    if (bin.isNullOrBlank()) {
        println("lewati integrasi: SEVENZIP_BIN tidak diset")
    } else {
        val tmp = Files.createTempDirectory("sz3").toFile().canonicalFile
        try {
            val src = File(tmp, "src/sub").also { it.mkdirs() }
            File(tmp, "src/a.txt").writeText("hello\n")
            File(src, "b.bin").writeBytes(ByteArray(5000).also { java.util.Random(42).nextBytes(it) }) // acak: tidak terkompresi, sehingga -v2k menghasilkan 3 bagian
            File(tmp, "src/spasi nama é.txt").writeText("x")
            fun sz(vararg a: String, pw: String? = null) = run7z(bin, a.toList(), SevenZipCommand.stdinFor(pw), tmp)
            check("integrasi: buat plain.7z", sz("a", "-bd", "-y", "plain.7z", "src").first == 0)
            check("integrasi: buat enc.7z (sandi)", sz("a", "-bd", "-y", "-pSECRET", "enc.7z", "src").first == 0)
            check("integrasi: buat enc_he.7z (header terenkripsi)", sz("a", "-bd", "-y", "-pSECRET", "-mhe=on", "enc_he.7z", "src").first == 0)
            check("integrasi: buat split", sz("a", "-bd", "-y", "-v2k", "split.7z", "src/sub/b.bin").first == 0)

            val (rc, out) = run7z(bin, SevenZipCommand.listArgs(File(tmp, "plain.7z").path), null, tmp)
            val ents = SevenZipCommand.parseList(out)
            val names = ents.map { it.path }.toSet()
            check("integrasi: list plain rc=0 dan entri lengkap", rc == 0 && setOf("src", "src/sub", "src/a.txt", "src/sub/b.bin", "src/spasi nama é.txt") == names)
            if (names != setOf("src", "src/sub", "src/a.txt", "src/sub/b.bin", "src/spasi nama é.txt")) println("  diagnostik: rc=$rc names=$names")
            check("integrasi: ukuran b.bin = 5000 dan folder bertanda", ents.first { it.path == "src/sub/b.bin" }.size == 5000L && ents.first { it.path == "src/sub" }.isDir)
            check("integrasi: semua jalur lolos safeEntryPath", ents.all { SevenZipCommand.safeEntryPath(it.path) != null })

            val (rn, on) = run7z(bin, SevenZipCommand.listArgs(File(tmp, "enc_he.7z").path), null, tmp)
            check("integrasi: header terenkripsi tanpa sandi -> NEED_PASSWORD", rn != 0 && SevenZipCommand.classify(rn, on, false) == Failure.NEED_PASSWORD)
            val (rw, ow) = run7z(bin, SevenZipCommand.listArgs(File(tmp, "enc_he.7z").path), SevenZipCommand.stdinFor("SALAH"), tmp)
            check("integrasi: sandi salah -> WRONG_PASSWORD", rw != 0 && SevenZipCommand.classify(rw, ow, true) == Failure.WRONG_PASSWORD)
            val (rk, ok) = run7z(bin, SevenZipCommand.listArgs(File(tmp, "enc_he.7z").path), SevenZipCommand.stdinFor("SECRET"), tmp)
            check("integrasi: sandi benar lewat stdin, entri terbaca meski ada prompt", rk == 0 && SevenZipCommand.parseList(ok).any { it.path == "src/a.txt" })

            val o1 = File(tmp, "o1").also { it.mkdirs() }
            val (rx, ox) = run7z(bin, SevenZipCommand.extractArgs(File(tmp, "plain.7z").path, o1.path), null, tmp)
            check("integrasi: ekstrak rc=0, isi sama, unicode utuh", rx == 0 && File(o1, "src/a.txt").readText() == "hello\n" && File(o1, "src/spasi nama é.txt").readText() == "x" && File(o1, "src/sub/b.bin").length() == 5000L)
            check("integrasi: keluaran ekstrak memuat persen progres", SevenZipCommand.parseProgress(ox) != null)

            val o2 = File(tmp, "o2").also { it.mkdirs() }
            val (rs, os) = run7z(bin, SevenZipCommand.extractArgs(File(tmp, "enc.7z").path, o2.path), SevenZipCommand.stdinFor("SALAH"), tmp)
            check("integrasi: ekstrak sandi salah -> WRONG_PASSWORD dan tidak ada berkas utuh", rs != 0 && SevenZipCommand.classify(rs, os, true) == Failure.WRONG_PASSWORD && !File(o2, "src/sub/b.bin").exists())
            val o3 = File(tmp, "o3").also { it.mkdirs() }
            val (rg, _) = run7z(bin, SevenZipCommand.extractArgs(File(tmp, "enc.7z").path, o3.path), SevenZipCommand.stdinFor("SECRET"), tmp)
            check("integrasi: ekstrak sandi benar via stdin", rg == 0 && File(o3, "src/sub/b.bin").length() == 5000L)

            // multi-bagian: bagian pertama OK, bagian tengah hilang -> MISSING_VOLUME
            val full = run7z(bin, SevenZipCommand.listArgs(File(tmp, "split.7z.001").path), null, tmp)
            check("integrasi: split .001 lengkap terbaca", full.first == 0 && SevenZipCommand.parseList(full.second).any { it.path == "src/sub/b.bin" })
            val miss = File(tmp, "miss").also { it.mkdirs() }
            File(tmp, "split.7z.001").copyTo(File(miss, "split.7z.001"))
            File(tmp, "split.7z.003").copyTo(File(miss, "split.7z.003"))
            val om = File(tmp, "om").also { it.mkdirs() }
            val (rm, omsg) = run7z(bin, SevenZipCommand.extractArgs(File(miss, "split.7z.001").path, om.path), null, tmp)
            check("integrasi: volume tengah hilang -> MISSING_VOLUME", rm != 0 && SevenZipCommand.classify(rm, omsg, false) == Failure.MISSING_VOLUME)

            // bukan arsip
            File(tmp, "junk.rar").writeText("bukan arsip")
            val (rj, oj) = run7z(bin, SevenZipCommand.listArgs(File(tmp, "junk.rar").path), null, tmp)
            check("integrasi: berkas sampah -> NOT_ARCHIVE", rj != 0 && SevenZipCommand.classify(rj, oj, false) == Failure.NOT_ARCHIVE)

            // wildcard + dash: -spd mencegah perluasan, -- mencegah nama dibaca sebagai opsi
            File(tmp, "plain.7z").copyTo(File(tmp, "a*b.7z"))
            File(tmp, "plain.7z").copyTo(File(tmp, "aXb.7z"))
            val (rwc, owc) = run7z(bin, SevenZipCommand.listArgs(File(tmp, "a*b.7z").path), null, tmp)
            check("integrasi: nama a*b.7z tidak diperluas (hanya 1 arsip)", rwc == 0 && SevenZipCommand.parseList(owc).size == ents.size)
            File(tmp, "plain.7z").copyTo(File(tmp, "-dash.7z"))
            val (rd, od) = run7z(bin, SevenZipCommand.listArgs(File(tmp, "-dash.7z").path), null, tmp)
            check("integrasi: nama berawalan '-' aman (jalur absolut)", rd == 0 && SevenZipCommand.parseList(od).size == ents.size)

            // [ARCHIVE4] ekstrak sebagian lewat nama setelah `--` (berkas, folder, dengan spasi/unicode)
            val p1 = File(tmp, "p1").also { it.mkdirs() }
            val (r1, _) = run7z(bin, SevenZipCommand.extractArgs(File(tmp, "plain.7z").path, p1.path, listOf("src/a.txt", "src/spasi nama é.txt")), null, tmp)
            check("integrasi: sebagian berkas saja", r1 == 0 && File(p1, "src/a.txt").readText() == "hello\n" && File(p1, "src/spasi nama é.txt").exists() && !File(p1, "src/sub/b.bin").exists())
            val p2 = File(tmp, "p2").also { it.mkdirs() }
            val (r2, _) = run7z(bin, SevenZipCommand.extractArgs(File(tmp, "plain.7z").path, p2.path, listOf("src/sub/")), null, tmp)
            check("integrasi: sebagian folder ikut isinya", r2 == 0 && File(p2, "src/sub/b.bin").length() == 5000L && !File(p2, "src/a.txt").exists())
            val p3 = File(tmp, "p3").also { it.mkdirs() }
            val (r3, o3x) = run7z(bin, SevenZipCommand.extractArgs(File(tmp, "plain.7z").path, p3.path, listOf("src/tidak-ada.txt")), null, tmp)
            check("integrasi: nama tak ada -> rc 0 TAPI nol berkas (pemanggil wajib menganggap 0 berkas = galat)", r3 == 0 && (p3.listFiles()?.isEmpty() ?: true))
            val p4 = File(tmp, "p4").also { it.mkdirs() }
            val (r4, _) = run7z(bin, SevenZipCommand.extractArgs(File(tmp, "plain.7z").path, p4.path, listOf("src/*.txt")), null, tmp)
            check("integrasi: wildcard di nama TIDAK diperluas (-spd), nol berkas", r4 == 0 && !File(p4, "src/a.txt").exists() && (p4.listFiles()?.isEmpty() ?: true))
            val ps = File(tmp, "ps").also { it.mkdirs() }
            val (r5, _) = run7z(bin, SevenZipCommand.extractArgs(File(tmp, "enc.7z").path, ps.path, listOf("src/a.txt")), SevenZipCommand.stdinFor("SECRET"), tmp)
            check("integrasi: sebagian + sandi via stdin", r5 == 0 && File(ps, "src/a.txt").readText() == "hello\n" && !File(ps, "src/sub/b.bin").exists())
            val stream = ArrayList<SevenZipCommand.Entry>()
            val proc = ProcessBuilder(listOf(bin) + SevenZipCommand.listArgs(File(tmp, "plain.7z").path)).redirectErrorStream(true).start()
            val tr = proc.inputStream.bufferedReader().use { SevenZipCommand.parseListStream(it, 2) { e -> stream.add(e) } }
            proc.waitFor()
            check("integrasi: parseListStream pada keluaran nyata terpotong di batas", tr && stream.size == 2)

            // jalur relatif berbahaya tak pernah dibuat builder
            check("integrasi: builder menolak jalur relatif -dash.7z", throwsIae { SevenZipCommand.listArgs("-dash.7z") })

            run {  // [ARCHIVE6-SCOPE] cakupan sendiri: nama variabel tidak bentrok dengan blok integrasi lama
            // [ARCHIVE6] pembuatan 7z bersandi: argumen dari createArgs dijalankan terhadap 7z asli (jalur host = jalur "guest" di sini)
            val mk = File(tmp, "mk").also { it.mkdirs() }
            File(mk, "isi.txt").writeText("rahasia isi\n")
            File(mk, "-aneh (1).txt").writeText("n\n")
            val dirA = File(mk, "dirA").also { File(it, "sub").mkdirs() }
            File(dirA, "sub/b.txt").writeText("bb\n")
            File(dirA, "d *x").mkdirs(); File(dirA, "d *x/in.txt").writeText("q\n") // nama bersarang berisi * : aman
            val other = File(tmp, "other").also { it.mkdirs() }
            File(other, "lain.txt").writeText("lain\n")
            val linked = try {
                Files.createSymbolicLink(File(dirA, "tautan").toPath(), File("/etc/hostname").toPath())
                Files.createSymbolicLink(File(dirA, "dirlink").toPath(), File("/etc").toPath())
                Files.createSymbolicLink(File(dirA, "d *x/ln *.txt").toPath(), File("/etc/hostname").toPath()) // tanpa pengecualian 7z MENGIKUTI ini dan mengarsipkan isi /etc
                true
            } catch (e: Exception) { false }
            val skip = if (linked) listOf("dirA/tautan", "dirA/dirlink", "dirA/d *x/ln *.txt") else emptyList()
            val srcs = listOf(File(mk, "isi.txt"), File(mk, "-aneh (1).txt"), dirA, File(other, "lain.txt")).map { it.path }
            val want = buildSet {
                addAll(listOf("isi.txt", "-aneh (1).txt", "dirA", "dirA/sub", "dirA/sub/b.txt", "lain.txt", "dirA/d *x", "dirA/d *x/in.txt"))
            }
            fun mkArc(name: String, pw: String, enc: Boolean, sources: List<String> = srcs): Pair<Int, String> =
                run7z(bin, SevenZipCommand.createArgs(File(tmp, name).path, sources, 1, true, enc, skip), SevenZipCommand.stdinFor(pw), tmp)
            fun lsArc(name: String, pw: String?) = run7z(bin, SevenZipCommand.listArgs(File(tmp, name).path), SevenZipCommand.stdinFor(pw), tmp)

            check("integrasi6: buat isi-saja rc=0", mkArc("c_data.7z", "rahasia", false).first == 0)
            check("integrasi6: buat isi+nama rc=0", mkArc("c_names.7z", "rahasia", true).first == 0)

            val (rd, od) = lsArc("c_data.7z", null)
            val ed = SevenZipCommand.parseList(od)
            check("integrasi6: isi-saja: nama TERLIHAT tanpa sandi, hanya nama item (tanpa jalur induk)", rd == 0 && ed.map { it.path }.toSet() == want)
            check("integrasi6: isi-saja: berkas terenkripsi, folder tidak", ed.filter { !it.isDir && !it.isLink }.all { it.encrypted })
            check("integrasi6: tautan (file dan folder->/etc) DILEWATI, isi target tidak ikut", ed.none { it.path.startsWith("dirA/tautan") || it.path.startsWith("dirA/dirlink") })

            val (rn, on) = lsArc("c_names.7z", null)
            check("integrasi6: isi+nama: tanpa sandi TIDAK ada nama (NEED_PASSWORD)", SevenZipCommand.parseList(on).isEmpty() && SevenZipCommand.classify(rn, on, false) == Failure.NEED_PASSWORD)
            val (rn2, on2) = lsArc("c_names.7z", "rahasia")
            check("integrasi6: isi+nama: dengan sandi daftar lengkap", rn2 == 0 && SevenZipCommand.parseList(on2).map { it.path }.toSet() == want)
            val (rw, ow) = lsArc("c_names.7z", "salah")
            check("integrasi6: isi+nama: sandi salah -> WRONG_PASSWORD", SevenZipCommand.classify(rw, ow, true) == Failure.WRONG_PASSWORD)

            val xo = File(tmp, "xo_names").also { it.mkdirs() }
            val (rx, _) = run7z(bin, SevenZipCommand.extractArgs(File(tmp, "c_names.7z").path, xo.path), SevenZipCommand.stdinFor("rahasia"), tmp)
            check("integrasi6: ekstrak isi+nama dengan sandi: isi utuh dari dua folder induk", rx == 0 &&
                File(xo, "isi.txt").readText() == "rahasia isi\n" && File(xo, "lain.txt").readText() == "lain\n" &&
                File(xo, "dirA/sub/b.txt").readText() == "bb\n" && File(xo, "-aneh (1).txt").readText() == "n\n" && File(xo, "dirA/d *x/in.txt").readText() == "q\n")
            val xd = File(tmp, "xo_data").also { it.mkdirs() }
            val (rxd, oxd) = run7z(bin, SevenZipCommand.extractArgs(File(tmp, "c_data.7z").path, xd.path), SevenZipCommand.stdinFor("salah"), tmp)
            check("integrasi6: isi-saja: sandi salah -> WRONG_PASSWORD", rxd != 0 && SevenZipCommand.classify(rxd, oxd, true) == Failure.WRONG_PASSWORD)

            val uni = "k\u00e4t\u0101-\u5bc6\u7801-\u00e9"
            check("integrasi6: sandi non-ASCII: buat", mkArc("c_uni.7z", uni, true).first == 0)
            val xu = File(tmp, "xo_uni").also { it.mkdirs() }
            val (ru, _) = run7z(bin, SevenZipCommand.extractArgs(File(tmp, "c_uni.7z").path, xu.path), SevenZipCommand.stdinFor(uni), tmp)
            check("integrasi6: sandi non-ASCII: ekstrak balik utuh", ru == 0 && File(xu, "isi.txt").readText() == "rahasia isi\n")

            val (rp, op) = mkArc("c_part.7z", "rahasia", true, srcs + File(mk, "tidak-ada.txt").path)
            check("integrasi6: sumber hilang -> rc=1 (arsip TETAP tertulis; pemanggil wajib membuangnya)", rp == 1 && File(tmp, "c_part.7z").exists())
            check("integrasi6: classifyCreate(rc=1) = SOURCE_UNREADABLE", SevenZipCommand.classifyCreate(rp, op, true) == Failure.SOURCE_UNREADABLE)
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails > 0) System.exit(1)
}
