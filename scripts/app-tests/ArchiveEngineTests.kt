import com.molina.studio.cinema.ArchiveEngine
import com.molina.studio.cinema.ExtractTarget
import com.molina.studio.cinema.FileOps
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import net.lingala.zip4j.ZipFile as Z4j
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod

private var fails = 0
private var runs = 0
private fun check(name: String, ok: Boolean) { runs++; if (!ok) { fails++; println("GAGAL: $name") } }
private fun <T> eq(name: String, got: T, want: T) { runs++; if (got != want) { fails++; println("GAGAL: $name -> $got (seharusnya $want)") } }
private fun ctl(cancel: AtomicBoolean = AtomicBoolean(false)) = FileOps.Ctl(cancel) { }
private inline fun <reified E : Throwable> throwsA(name: String, body: () -> Unit): E? {
    runs++
    return try { body(); fails++; println("GAGAL: $name tidak melempar ${E::class.simpleName}"); null }
    catch (e: Throwable) { if (e is E) e else { fails++; println("GAGAL: $name melempar ${e.javaClass.simpleName}: ${e.message}"); null } }
}

private fun same(a: File, b: File): Boolean {
    val fa = a.walkTopDown().filter { it.isFile }.map { it.relativeTo(a).path to it.readBytes().toList() }.sortedBy { it.first }.toList()
    val fb = b.walkTopDown().filter { it.isFile }.map { it.relativeTo(b).path to it.readBytes().toList() }.sortedBy { it.first }.toList()
    return fa == fb
}

fun main(args: Array<String>) {
    val fx = File(args[0])
    val src = File(fx, "src")
    val work = java.nio.file.Files.createTempDirectory("arceng").toFile()
    fun fresh(): File = File(work, "d" + System.nanoTime()).apply { mkdirs() }
    try {
        // 1. daftar isi + ekstrak penuh semua format
        for (n in listOf("t.tar", "t.tar.gz", "t.tgz", "t.tar.bz2", "t.tar.xz", "t.7z", "z.zip")) {
            val f = File(fx, n)
            val l = ArchiveEngine.list(f)
            val names = l.entries.map { it.name.trimStart('.', '/') }
            check("$n: daftar memuat sub/b.txt", names.contains("sub/b.txt"))
            check("$n: daftar memuat sub/deep/c.bin", names.contains("sub/deep/c.bin"))
            eq("$n: jumlah berkas", l.files, 4)
            check("$n: ada folder", l.folders >= 1)
            eq("$n: total byte", l.totalSize, src.walkTopDown().filter { it.isFile }.sumOf { it.length() })
            eq("$n: tak terenkripsi", l.encrypted, false)
            val d = fresh()
            val r = ArchiveEngine.extract(f, d, ExtractTarget.NAMED_FOLDER, ctl())
            check("$n: isi sama", same(src, r.dir))
            check("$n: folder bernama arsip", r.dir.parentFile == d && r.dir.name == (if (n == "z.zip") "z" else "t"))
            if (n != "z.zip") eq("$n: jumlah diekstrak", r.files, 4)
        }

        // 2. "di sini" dua kali: tidak menimpa, diberi nama baru
        run {
            val d = fresh()
            val f = File(fx, "t.tar.gz")
            val r1 = ArchiveEngine.extract(f, d, ExtractTarget.HERE, ctl())
            eq("here: dir", r1.dir, d); eq("here: renamed 0", r1.renamed, 0)
            check("here: a.txt ada", File(d, "a.txt").readText() == "halo molina\n")
            File(d, "a.txt").writeText("DIUBAH")
            val r2 = ArchiveEngine.extract(f, d, ExtractTarget.HERE, ctl())
            eq("here: renamed 4", r2.renamed, 4)
            eq("here: asli utuh", File(d, "a.txt").readText(), "DIUBAH")
            eq("here: salinan baru", File(d, "a (1).txt").readText(), "halo molina\n")
        }

        // 3. ekstrak sebagian
        for (n in listOf("t.tar.xz", "t.7z", "z.zip")) {
            val f = File(fx, n)
            val d = fresh()
            val r = ArchiveEngine.extract(f, d, ExtractTarget.HERE, ctl(), null, setOf("sub/", "a.txt"))
            eq("$n: sebagian 3 berkas", r.files, 3)
            check("$n: sebagian a.txt", File(d, "a.txt").isFile)
            check("$n: sebagian sub/deep/c.bin", File(d, "sub/deep/c.bin").isFile)
            check("$n: empty.txt tidak ikut", !File(d, "empty.txt").exists())
            val d2 = fresh()
            val r2 = ArchiveEngine.extract(f, d2, ExtractTarget.NAMED_FOLDER, ctl(), null, setOf("empty.txt"))
            eq("$n: satu berkas kosong", r2.files, 1)
            check("$n: berkas kosong ada", File(r2.dir, "empty.txt").let { it.isFile && it.length() == 0L })
            val d3 = fresh()
            throwsA<IOException>("$n: pilihan tak cocok") { ArchiveEngine.extract(f, d3, ExtractTarget.NAMED_FOLDER, ctl(), null, setOf("tidak-ada.txt")) }
            eq("$n: folder dibersihkan saat tak cocok", d3.listFiles()?.size ?: 0, 0)
        }

        // 4. gz/bz2/xz tunggal
        for (n in listOf("notes.txt.gz", "notes.txt.bz2", "notes.txt.xz")) {
            val f = File(fx, n)
            val l = ArchiveEngine.list(f)
            eq("$n: satu entri", l.entries.map { it.name }, listOf("notes.txt"))
            val d = fresh()
            val r = ArchiveEngine.extract(f, d, ExtractTarget.NAMED_FOLDER, ctl())
            eq("$n: folder", r.dir.name, "notes")
            check("$n: isi", File(r.dir, "notes.txt").readBytes().contentEquals(File(fx, "notes.txt").readBytes()))
            val d2 = fresh()
            ArchiveEngine.extract(f, d2, ExtractTarget.HERE, ctl())
            check("$n: di sini", File(d2, "notes.txt").isFile)
        }

        // 5. 7z berkata sandi
        run {
            val h = File(fx, "enc_header.7z")
            throwsA<FileOps.NeedPassword>("7z header: daftar tanpa sandi") { ArchiveEngine.list(h) }
            throwsA<FileOps.WrongPassword>("7z header: daftar sandi salah") { ArchiveEngine.list(h, "salah") }
            eq("7z header: daftar benar", ArchiveEngine.list(h, "rahasia").files, 4)
            val d = fresh()
            throwsA<FileOps.NeedPassword>("7z header: ekstrak tanpa sandi") { ArchiveEngine.extract(h, d, ExtractTarget.NAMED_FOLDER, ctl()) }
            throwsA<FileOps.WrongPassword>("7z header: ekstrak sandi salah") { ArchiveEngine.extract(h, d, ExtractTarget.NAMED_FOLDER, ctl(), "salah") }
            eq("7z header: tak ada sisa", d.listFiles()?.size ?: 0, 0)
            val r = ArchiveEngine.extract(h, d, ExtractTarget.NAMED_FOLDER, ctl(), "rahasia")
            check("7z header: isi sama", same(src, r.dir))

            val c = File(fx, "enc_content.7z")
            val lc = ArchiveEngine.list(c)
            check("7z isi: terdeteksi terenkripsi", lc.encrypted)
            val d2 = fresh()
            throwsA<FileOps.NeedPassword>("7z isi: ekstrak tanpa sandi") { ArchiveEngine.extract(c, d2, ExtractTarget.NAMED_FOLDER, ctl()) }
            throwsA<FileOps.WrongPassword>("7z isi: sandi salah") { ArchiveEngine.extract(c, d2, ExtractTarget.NAMED_FOLDER, ctl(), "salah") }
            eq("7z isi: tak ada sisa", d2.listFiles()?.size ?: 0, 0)
            val r2 = ArchiveEngine.extract(c, d2, ExtractTarget.NAMED_FOLDER, ctl(), "rahasia")
            check("7z isi: isi sama", same(src, r2.dir))
        }

        // 6. ZIP berenkripsi AES (dibuat zip4j): sebagian dan di sini
        run {
            val z = File(work, "aes.zip")
            val p = ZipParameters().apply { isEncryptFiles = true; encryptionMethod = EncryptionMethod.AES; aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256 }
            Z4j(z, "kunci123".toCharArray()).addFolder(src, p)
            val d = fresh()
            throwsA<FileOps.NeedPassword>("zip aes: tanpa sandi") { ArchiveEngine.extract(z, d, ExtractTarget.HERE, ctl()) }
            throwsA<FileOps.WrongPassword>("zip aes: sandi salah") { ArchiveEngine.extract(z, d, ExtractTarget.HERE, ctl(), "salah") }
            val r = ArchiveEngine.extract(z, d, ExtractTarget.HERE, ctl(), "kunci123", setOf("src/sub/"))
            eq("zip aes: sebagian 2", r.files, 2)
            check("zip aes: b.txt", File(d, "src/sub/b.txt").readText().startsWith("baris 1"))
            check("zip aes: a.txt tak ikut", !File(d, "src/a.txt").exists())
        }

        // 7. arsip jahat
        run {
            val d = fresh()
            val e = throwsA<IOException>("dotdot ditolak") { ArchiveEngine.extract(File(fx, "evil_dotdot.tar"), d, ExtractTarget.NAMED_FOLDER, ctl()) }
            check("dotdot: pesan aman", e?.message?.contains("tidak aman") == true)
            check("dotdot: tak ada berkas di luar", !File(d, "evil.txt").exists() && !File(d.parentFile, "evil.txt").exists())
            eq("dotdot: folder dibersihkan", d.listFiles()?.size ?: 0, 0)
            val d2 = fresh()
            throwsA<IOException>("dotdot mode di sini") { ArchiveEngine.extract(File(fx, "evil_dotdot.tar"), d2, ExtractTarget.HERE, ctl()) }
            check("dotdot di sini: ok.txt dibatalkan", !File(d2, "ok.txt").exists())
            val d3 = fresh()
            val r = ArchiveEngine.extract(File(fx, "evil_symlink.tar"), d3, ExtractTarget.NAMED_FOLDER, ctl())
            check("symlink: tidak ada tautan", r.dir.walkTopDown().none { java.nio.file.Files.isSymbolicLink(it.toPath()) })
            check("symlink: /tmp/pwn.txt tidak dibuat", !File("/tmp/pwn.txt").exists())
            check("symlink: fine.txt tetap ada", File(r.dir, "fine.txt").isFile)
            val d4 = fresh()
            throwsA<IOException>("zip dotdot ditolak") { ArchiveEngine.extract(File(fx, "evil.zip"), d4, ExtractTarget.HERE, ctl(), null, setOf("ok.txt", "../zzevil.txt")) }
            check("zip dotdot: tak ada di luar", !File(d4.parentFile, "zzevil.txt").exists())
        }

        // 8. batal dan rusak
        run {
            val flag = AtomicBoolean(true)
            val d = fresh()
            val e = throwsA<IOException>("batal") { ArchiveEngine.extract(File(fx, "t.tar.xz"), d, ExtractTarget.NAMED_FOLDER, ctl(flag)) }
            eq("batal: pesan", e?.message, "Dibatalkan")
            eq("batal: folder dibersihkan", d.listFiles()?.size ?: 0, 0)
            val d2 = fresh()
            val e2 = throwsA<IOException>("7z batal") { ArchiveEngine.extract(File(fx, "t.7z"), d2, ExtractTarget.NAMED_FOLDER, ctl(flag)) }
            eq("7z batal: pesan", e2?.message, "Dibatalkan")
            eq("7z batal: folder dibersihkan", d2.listFiles()?.size ?: 0, 0)
            for (n in listOf("broken.tar.gz", "broken.7z")) {
                val d3 = fresh()
                val e3 = throwsA<IOException>("$n rusak") { ArchiveEngine.extract(File(fx, n), d3, ExtractTarget.NAMED_FOLDER, ctl()) }
                check("$n: bukan galat sandi", e3 != null && e3 !is FileOps.NeedPassword && e3 !is FileOps.WrongPassword)
                eq("$n: folder dibersihkan", d3.listFiles()?.size ?: 0, 0)
            }
            throwsA<IOException>("bukan arsip") { ArchiveEngine.list(File(fx, "notes.txt")) }
            throwsA<IOException>("tidak ada") { ArchiveEngine.list(File(fx, "nope.7z")) }
            val empty = File(work, "kosong.7z").apply { writeBytes(ByteArray(0)) }
            throwsA<IOException>("kosong") { ArchiveEngine.list(empty) }
        }

        // 9. progres naik dan berakhir 100%
        run {
            val seen = ArrayList<Float>()
            val c = FileOps.Ctl(AtomicBoolean(false)) { seen.add(it) }
            ArchiveEngine.extract(File(fx, "t.tar.gz"), fresh(), ExtractTarget.NAMED_FOLDER, c)
            check("progres tar.gz naik", seen.isNotEmpty() && seen == seen.sorted() && seen.last() >= 0.99f)
            seen.clear()
            ArchiveEngine.extract(File(fx, "t.7z"), fresh(), ExtractTarget.NAMED_FOLDER, c)
            check("progres 7z naik", seen.isNotEmpty() && seen == seen.sorted() && seen.last() >= 0.99f)
        }
    } finally { work.deleteRecursively() }
    println(if (fails == 0) "LULUS: $runs kasus" else "$fails dari $runs kasus GAGAL")
    if (fails != 0) System.exit(1)
}
