import com.molina.studio.cinema.ArchiveFormat
import com.molina.studio.cinema.ArchiveNames
import java.io.File

private var fails = 0
private var runs = 0
private fun check(name: String, ok: Boolean) { runs++; if (!ok) { fails++; println("GAGAL: $name") } }
private fun <T> eq(name: String, got: T, want: T) { runs++; if (got != want) { fails++; println("GAGAL: $name -> $got (seharusnya $want)") } }

fun main() {
    // deteksi: sufiks majemuk didahulukan
    eq("tar.gz", ArchiveNames.detect("a.tar.gz"), ArchiveFormat.TAR_GZ)
    eq("tgz", ArchiveNames.detect("A.TGZ"), ArchiveFormat.TAR_GZ)
    eq("tar.bz2", ArchiveNames.detect("a.tar.bz2"), ArchiveFormat.TAR_BZ2)
    eq("tbz2", ArchiveNames.detect("a.tbz2"), ArchiveFormat.TAR_BZ2)
    eq("tar.xz", ArchiveNames.detect("a.tar.xz"), ArchiveFormat.TAR_XZ)
    eq("txz", ArchiveNames.detect("a.txz"), ArchiveFormat.TAR_XZ)
    eq("tar", ArchiveNames.detect("a.tar"), ArchiveFormat.TAR)
    eq("7z", ArchiveNames.detect("a.7Z"), ArchiveFormat.SEVEN_Z)
    eq("zip", ArchiveNames.detect("a.zip"), ArchiveFormat.ZIP)
    eq("gz", ArchiveNames.detect("notes.txt.gz"), ArchiveFormat.GZ)
    eq("bz2", ArchiveNames.detect("x.bz2"), ArchiveFormat.BZ2)
    eq("xz", ArchiveNames.detect("x.xz"), ArchiveFormat.XZ)
    eq("bukan arsip", ArchiveNames.detect("film.mkv"), null)
    eq("hanya sufiks", ArchiveNames.detect(".zip"), null)
    eq("rar belum didukung", ArchiveNames.detect("a.rar"), null)
    check("flag tar", ArchiveFormat.TAR_XZ.isTar && !ArchiveFormat.GZ.isTar)
    check("flag single", ArchiveFormat.XZ.isSingle && !ArchiveFormat.TAR_GZ.isSingle)
    check("flag sandi", ArchiveFormat.SEVEN_Z.hasPassword && ArchiveFormat.ZIP.hasPassword && !ArchiveFormat.TAR.hasPassword)

    // stem + nama folder
    eq("stem tar.gz", ArchiveNames.stem("proyek.tar.gz"), "proyek")
    eq("stem gz", ArchiveNames.stem("notes.txt.gz"), "notes.txt")
    eq("stem noarsip", ArchiveNames.stem("film.mkv"), "film.mkv")
    eq("folder gz", ArchiveNames.folderName("notes.txt.gz"), "notes")
    eq("folder gz tanpa ekstensi", ArchiveNames.folderName("data.gz"), "data")
    eq("folder 7z", ArchiveNames.folderName("Foto Liburan.7z"), "Foto Liburan")
    eq("folder titik di nama", ArchiveNames.folderName("v1.2.tar.xz"), "v1.2")

    // cleanEntry
    eq("normal", ArchiveNames.cleanEntry("a/b/c.txt"), "a/b/c.txt")
    eq("awalan ./", ArchiveNames.cleanEntry("./a/b"), "a/b")
    eq("awalan /", ArchiveNames.cleanEntry("/etc/passwd"), "etc/passwd")
    eq("backslash", ArchiveNames.cleanEntry("a\\b\\c"), "a/b/c")
    eq("slash ganda", ArchiveNames.cleanEntry("a//b/"), "a/b")
    eq("akar", ArchiveNames.cleanEntry("./"), "")
    eq("kosong", ArchiveNames.cleanEntry(""), "")
    eq("dotdot", ArchiveNames.cleanEntry("../x"), null)
    eq("dotdot tengah", ArchiveNames.cleanEntry("a/../../x"), null)
    eq("dotdot backslash", ArchiveNames.cleanEntry("a\\..\\..\\x"), null)
    eq("nul", ArchiveNames.cleanEntry("a\u0000b"), null)
    eq("titik di nama ok", ArchiveNames.cleanEntry("a/..b/c.."), "a/..b/c..")

    // seleksi
    val only = setOf("docs/", "readme.txt", "src/a/x.kt")
    check("file pilih", ArchiveNames.selected("readme.txt", false, only))
    check("isi folder", ArchiveNames.selected("docs/a/b.md", false, only))
    check("folder sendiri", ArchiveNames.selected("docs", true, only))
    check("file dalam", ArchiveNames.selected("src/a/x.kt", false, only))
    check("tidak dipilih", !ArchiveNames.selected("src/a/y.kt", false, only))
    check("awalan mirip bukan folder", !ArchiveNames.selected("docs2/x", false, only))
    check("null = semua", ArchiveNames.selected("apa/saja", false, null))
    check("set kosong = tidak ada", !ArchiveNames.selected("a", false, emptySet()))

    // safeTarget + uniqueIn di folder sementara
    val tmp = java.nio.file.Files.createTempDirectory("arc").toFile()
    try {
        val base = File(tmp, "out").apply { mkdirs() }
        eq("target normal", ArchiveNames.safeTarget(base, "a/b.txt")?.path, File(base.canonicalFile, "a/b.txt").path)
        eq("target keluar", ArchiveNames.safeTarget(base, "../luar.txt"), null)
        val outside = File(tmp, "luar").apply { mkdirs() }
        try {
            java.nio.file.Files.createSymbolicLink(File(base, "tautan").toPath(), outside.toPath())
            eq("symlink keluar ditolak", ArchiveNames.safeTarget(base, "tautan/x.txt"), null)
        } catch (e: Exception) { println("(lewati uji symlink: ${e.javaClass.simpleName})") }
        File(base, "a.txt").writeText("1")
        eq("unik 1", ArchiveNames.uniqueIn(base, "a.txt").name, "a (1).txt")
        File(base, "a (1).txt").writeText("2")
        eq("unik 2", ArchiveNames.uniqueIn(base, "a.txt").name, "a (2).txt")
        eq("unik belum ada", ArchiveNames.uniqueIn(base, "b.txt").name, "b.txt")
        File(base, "Makefile").writeText("x")
        eq("unik tanpa ekstensi", ArchiveNames.uniqueIn(base, "Makefile").name, "Makefile (1)")
    } finally { tmp.deleteRecursively() }

    println(if (fails == 0) "LULUS: $runs kasus" else "$fails dari $runs kasus GAGAL")
    if (fails != 0) System.exit(1)
}
