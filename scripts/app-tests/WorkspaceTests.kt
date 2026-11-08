import com.molina.workspace.WorkspaceFiles
import com.molina.workspace.WorkspaceRootPolicy
import java.io.File
import java.nio.file.Files

private var total = 0
private var failed = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    total++
    if (!ok) {
        failed++
        println("GAGAL: $name ${if (detail.isNotEmpty()) "($detail)" else ""}")
    }
}

private fun <T> eq(name: String, actual: T, expected: T) = check(name, actual == expected, "dapat=$actual harap=$expected")

private fun tmp(): File = Files.createTempDirectory("ws-test").toFile()

private fun listing(d: File) = WorkspaceFiles.listDir(d)!!.map { it.name }

fun main() {
    val base = tmp()
    try {
        // ---- listDir
        eq("listDir folder tak ada = null", WorkspaceFiles.listDir(File(base, "nope")), null)
        val plain = File(base, "plain.txt").also { it.writeText("x") }
        eq("listDir berkas biasa = null", WorkspaceFiles.listDir(plain), null)
        val empty = File(base, "kosong").also { it.mkdirs() }
        check("listDir folder kosong = list kosong (bukan null)", WorkspaceFiles.listDir(empty)?.isEmpty() == true)

        val d1 = File(base, "urut").also { it.mkdirs() }
        File(d1, "b.txt").writeText("")
        File(d1, "A.txt").writeText("")
        File(d1, "zdir").mkdirs()
        File(d1, "Adir").mkdirs()
        File(d1, ".hid").writeText("")
        eq("urutan: folder dulu, tanpa peka huruf", listing(d1), listOf("Adir", "zdir", ".hid", "A.txt", "b.txt"))
        val e = WorkspaceFiles.listDir(d1)!!
        check("flag isDir", e[0].isDir && e[1].isDir && !e[2].isDir)
        check("flag hidden", e.first { it.name == ".hid" }.hidden && !e.first { it.name == "A.txt" }.hidden)

        val big = File(base, "besar").also { it.mkdirs() }
        for (i in 0 until 2005) File(big, "f%04d".format(i)).createNewFile()
        eq("listDir dibatasi 2000 entri", WorkspaceFiles.listDir(big)!!.size, 2000)

        // ---- validate
        eq("validate kosong", WorkspaceFiles.validate(""), "Nama kosong")
        eq("validate spasi saja", WorkspaceFiles.validate("   "), "Nama kosong")
        eq("validate .", WorkspaceFiles.validate("."), "Nama tidak valid")
        eq("validate ..", WorkspaceFiles.validate(".."), "Nama tidak valid")
        eq("validate slash", WorkspaceFiles.validate("a/b"), "Nama tidak boleh mengandung /")
        eq("validate NUL", WorkspaceFiles.validate("a\u0000b"), "Nama tidak boleh mengandung /")
        eq("validate 240 byte ok", WorkspaceFiles.validate("a".repeat(240)), null)
        eq("validate 241 byte", WorkspaceFiles.validate("a".repeat(241)), "Nama terlalu panjang")
        eq("validate hitung byte UTF-8 (121 x 2 byte)", WorkspaceFiles.validate("é".repeat(121)), "Nama terlalu panjang")
        eq("validate nama biasa", WorkspaceFiles.validate("skrip.sh"), null)
        eq("validate dipangkas", WorkspaceFiles.validate("  x  "), null)
        eq("validate titik awal boleh", WorkspaceFiles.validate(".bashrc"), null)

        // ---- createFile / createFolder
        val c = File(base, "buat").also { it.mkdirs() }
        val r1 = WorkspaceFiles.createFile(c, "  a.sh ")
        check("createFile ok", r1.ok && r1.file == File(c, "a.sh") && File(c, "a.sh").isFile)
        val r2 = WorkspaceFiles.createFile(c, "a.sh")
        eq("createFile sudah ada", r2.error, "Sudah ada: a.sh")
        check("createFile gagal tidak punya file", r2.file == null && !r2.ok)
        eq("createFile nama tak valid", WorkspaceFiles.createFile(c, "x/y").error, "Nama tidak boleh mengandung /")
        val r3 = WorkspaceFiles.createFile(File(c, "baru/dalam"), "z.txt")
        check("createFile membuat folder induk", r3.ok && File(c, "baru/dalam/z.txt").isFile)
        val r4 = WorkspaceFiles.createFile(File(c, "a.sh"), "x.txt")
        check("createFile di dalam berkas = galat", !r4.ok && r4.error != null)
        val f1 = WorkspaceFiles.createFolder(c, "sub")
        check("createFolder ok", f1.ok && File(c, "sub").isDirectory)
        eq("createFolder sudah ada", WorkspaceFiles.createFolder(c, "sub").error, "Sudah ada: sub")
        eq("createFolder nama kosong", WorkspaceFiles.createFolder(c, " ").error, "Nama kosong")
        check("createFolder berjenjang tak diizinkan (slash)", !WorkspaceFiles.createFolder(c, "p/q").ok)

        // ---- rename
        val rn = File(base, "ganti").also { it.mkdirs() }
        val src = File(rn, "lama.txt").also { it.writeText("isi") }
        File(rn, "ada.txt").writeText("")
        val m1 = WorkspaceFiles.rename(src, "baru.txt")
        check("rename ok", m1.ok && m1.file == File(rn, "baru.txt") && File(rn, "baru.txt").readText() == "isi" && !src.exists())
        eq("rename ke nama yang ada", WorkspaceFiles.rename(File(rn, "baru.txt"), "ada.txt").error, "Sudah ada: ada.txt")
        check("rename nama sama = sukses tanpa perubahan", WorkspaceFiles.rename(File(rn, "ada.txt"), "ada.txt").let { it.ok && it.file == File(rn, "ada.txt") })
        eq("rename nama tak valid", WorkspaceFiles.rename(File(rn, "ada.txt"), "../x").error, "Nama tidak boleh mengandung /")
        eq("rename sumber tak ada", WorkspaceFiles.rename(File(rn, "hilang"), "apa").error, "Gagal mengganti nama")
        File(rn, "Beda.TXT").writeText("")
        eq("rename sumber tak ada, target ada = Sudah ada (bukan galat mentah)", WorkspaceFiles.rename(File(rn, "beda.txt"), "Beda.TXT").error, "Sudah ada: Beda.TXT")
        File(rn, "beda.txt").writeText("")
        eq("rename beda huruf = berkas lain di FS peka huruf", WorkspaceFiles.rename(File(rn, "beda.txt"), "Beda.TXT").error, "Sudah ada: Beda.TXT")
        File(rn, "kec.txt").writeText("")
        eq("rename ke nama berbeda huruf yang sudah ada", WorkspaceFiles.rename(File(rn, "kec.txt"), "Beda.TXT").error, "Sudah ada: Beda.TXT")
        val d = File(rn, "dirlama").also { File(it, "isi").mkdirs() }
        check("rename folder membawa isinya", WorkspaceFiles.rename(d, "dirbaru").ok && File(rn, "dirbaru/isi").isDirectory)

        // ---- delete
        val del = File(base, "hapus").also { it.mkdirs() }
        val root = File(del, "kerja").also { it.mkdirs() }
        val one = File(root, "satu.txt").also { it.writeText("") }
        check("delete berkas", WorkspaceFiles.delete(one, root).ok && !one.exists())
        val tree = File(root, "pohon").also { File(it, "a/b").mkdirs() }
        File(tree, "a/b/f.txt").writeText("")
        File(tree, "a/g.txt").writeText("")
        check("delete pohon beserta isi", WorkspaceFiles.delete(tree, root).ok && !tree.exists())
        eq("delete folder utama ditolak", WorkspaceFiles.delete(root, root).error, "Folder ini tidak boleh dihapus")
        check("folder utama masih ada", root.isDirectory)
        eq("delete induk folder utama ditolak", WorkspaceFiles.delete(del, root).error, "Folder ini tidak boleh dihapus")
        check("induk masih ada", del.isDirectory)
        eq("delete /storage/emulated/0 ditolak", WorkspaceFiles.delete(File("/storage/emulated/0"), root).error, "Folder ini tidak boleh dihapus")
        eq("delete /sdcard ditolak (alias)", WorkspaceFiles.delete(File("/sdcard"), root).error, "Folder ini tidak boleh dihapus")
        eq("delete / ditolak", WorkspaceFiles.delete(File("/"), root).error, "Folder ini tidak boleh dihapus")
        eq("delete yang tak ada", WorkspaceFiles.delete(File(root, "tak-ada"), root).error, "Gagal menghapus tak-ada")
        val sibling = File(del, "kerja2").also { it.mkdirs() }
        check("delete saudara berawalan sama dengan root diizinkan", WorkspaceFiles.delete(sibling, root).ok && !sibling.exists())

        val target = File(del, "target").also { File(it, "penting.txt").apply { parentFile.mkdirs(); writeText("jangan hapus") } }
        val link = File(root, "tautan")
        val linked = try {
            Files.createSymbolicLink(link.toPath(), target.toPath()); true
        } catch (e: Exception) {
            false
        }
        if (linked) {
            check("delete tautan simbolik: tautan hilang", WorkspaceFiles.delete(link, root).ok && !Files.isSymbolicLink(link.toPath()))
            check("delete tautan simbolik: isi target utuh", File(target, "penting.txt").readText() == "jangan hapus")
        } else {
            println("LEWATI: symlink tidak didukung di sini")
        }

        // ---- policy.resolve
        val fb = File(base, "cadangan").also { it.mkdirs() }
        val real = File(base, "nyata").also { it.mkdirs() }
        check("resolve null = cadangan tanpa peringatan", WorkspaceRootPolicy.resolve(null, fb).let { it.dir == fb && !it.fellBack })
        check("resolve kosong = cadangan tanpa peringatan", WorkspaceRootPolicy.resolve("  ", fb).let { it.dir == fb && !it.fellBack })
        check("resolve folder ada", WorkspaceRootPolicy.resolve(real.path, fb).let { it.dir == real && !it.fellBack })
        check("resolve folder hilang = cadangan + peringatan", WorkspaceRootPolicy.resolve(File(base, "hilang").path, fb).let { it.dir == fb && it.fellBack })
        check("resolve path berkas = cadangan + peringatan", WorkspaceRootPolicy.resolve(plain.path, fb).let { it.dir == fb && it.fellBack })

        // ---- policy.normalize / parent / contains
        eq("normalize /sdcard", WorkspaceRootPolicy.normalize(File("/sdcard")).path, "/storage/emulated/0")
        eq("normalize /sdcard/A/B", WorkspaceRootPolicy.normalize(File("/sdcard/A/B")).path, "/storage/emulated/0/A/B")
        eq("normalize /sdcardx tidak disentuh", WorkspaceRootPolicy.normalize(File("/sdcardx/A")).path, "/sdcardx/A")
        eq("normalize path lain", WorkspaceRootPolicy.normalize(File("/data/x")).path, "/data/x")
        eq("parent dari root penyimpanan = null", WorkspaceRootPolicy.parent(File("/storage/emulated/0")), null)
        eq("parent satu level", WorkspaceRootPolicy.parent(File("/storage/emulated/0/Molina"))?.path, "/storage/emulated/0")
        eq("parent dua level", WorkspaceRootPolicy.parent(File("/storage/emulated/0/Molina/Scripts"))?.path, "/storage/emulated/0/Molina")
        eq("parent alias /sdcard/A", WorkspaceRootPolicy.parent(File("/sdcard/A"))?.path, "/storage/emulated/0")
        eq("parent /sdcard = null", WorkspaceRootPolicy.parent(File("/sdcard")), null)
        eq("parent di luar penyimpanan = null", WorkspaceRootPolicy.parent(File("/data/x")), null)
        eq("parent / = null", WorkspaceRootPolicy.parent(File("/")), null)
        eq("parent /storage/emulated/01/x = null (bukan awalan sebenarnya)", WorkspaceRootPolicy.parent(File("/storage/emulated/01/x")), null)
        check("contains sama", WorkspaceRootPolicy.contains(File("/a/b"), File("/a/b")))
        check("contains di bawah", WorkspaceRootPolicy.contains(File("/a/b"), File("/a/b/c/d")))
        check("contains saudara berawalan sama = tidak", !WorkspaceRootPolicy.contains(File("/a/b"), File("/a/bc")))
        check("contains induk = tidak", !WorkspaceRootPolicy.contains(File("/a/b"), File("/a")))
        check("contains root / = semua", WorkspaceRootPolicy.contains(File("/"), File("/a/b")))
        check("contains lintas alias /sdcard", WorkspaceRootPolicy.contains(File("/sdcard/A"), File("/storage/emulated/0/A/x")))

        // ---- policy.recent
        eq("pushRecent ke daftar kosong", WorkspaceRootPolicy.pushRecent(emptyList(), "/a"), listOf("/a"))
        eq("pushRecent terbaru di depan", WorkspaceRootPolicy.pushRecent(listOf("/a", "/b"), "/c"), listOf("/c", "/a", "/b"))
        eq("pushRecent duplikat naik ke depan", WorkspaceRootPolicy.pushRecent(listOf("/a", "/b", "/c"), "/c"), listOf("/c", "/a", "/b"))
        eq("pushRecent maks 5", WorkspaceRootPolicy.pushRecent(listOf("/1", "/2", "/3", "/4", "/5"), "/6"), listOf("/6", "/1", "/2", "/3", "/4"))
        eq("pushRecent alias /sdcard dianggap sama", WorkspaceRootPolicy.pushRecent(listOf("/storage/emulated/0/X", "/b"), "/sdcard/X"), listOf("/storage/emulated/0/X", "/b"))
        eq("pushRecent max kustom", WorkspaceRootPolicy.pushRecent(listOf("/a", "/b"), "/c", 2), listOf("/c", "/a"))
        eq("encode/decode bolak-balik", WorkspaceRootPolicy.decode(WorkspaceRootPolicy.encode(listOf("/a", "/b c", "/d"))), listOf("/a", "/b c", "/d"))
        eq("encode membuang baris baru dan kosong", WorkspaceRootPolicy.encode(listOf("/a", "", "/b\n/c", "  ", "/d")), "/a\n/d")
        eq("decode null", WorkspaceRootPolicy.decode(null), emptyList<String>())
        eq("decode kosong", WorkspaceRootPolicy.decode(""), emptyList<String>())
        eq("decode melewati baris kosong", WorkspaceRootPolicy.decode("/a\n\n/b\n"), listOf("/a", "/b"))
    } finally {
        base.deleteRecursively()
    }
    println("Selesai: $total kasus, $failed gagal")
    if (failed > 0) System.exit(1)
}
