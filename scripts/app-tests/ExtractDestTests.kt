import com.molina.core.fileops.ExtractTarget
import com.molina.explorer.ExtractDest
import java.io.File
import java.nio.file.Files

// [ARCHIVE-DEST-2] Uji ExtractDest: folder "di sini", target, pratinjau jalur, galat tujuan, teks hasil.

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

fun main() {
    val d = Files.createTempDirectory("xd").toFile()
    try {
        val sub = File(d, "arsip").also { it.mkdirs() }
        val fallback = File(d, "lain").also { it.mkdirs() }

        check("baseDir: induk arsip dipakai", ExtractDest.baseDir(File(sub, "a.7z"), fallback) == sub)
        check("baseDir: induk tak ada -> fallback", ExtractDest.baseDir(File(d, "tidak-ada/a.7z"), fallback) == fallback)
        check("baseDir: tanpa induk -> fallback", ExtractDest.baseDir(File("a.7z"), fallback) == fallback)

        check("target: dicentang = folder bernama", ExtractDest.target(true) == ExtractTarget.NAMED_FOLDER)
        check("target: tidak dicentang = di sini", ExtractDest.target(false) == ExtractTarget.HERE)

        check("preview: folder bernama", ExtractDest.preview(File("/sdcard/Unduhan"), "film", true) == "/sdcard/Unduhan/film")
        check("preview: akar tidak ganda garis miring", ExtractDest.preview(File("/"), "film", true) == "/film")
        check("preview: langsung di folder", ExtractDest.preview(File("/sdcard/Unduhan"), "film", false) == "/sdcard/Unduhan")

        check("error: null -> pilih folder", ExtractDest.error(null) == "Pilih folder tujuan terlebih dahulu.")
        check("error: tak ada", ExtractDest.error(File(d, "tidak-ada")) == "Folder tujuan tidak ditemukan.")
        val plain = File(d, "berkas.txt").also { it.writeText("x") }
        check("error: berkas biasa bukan folder", ExtractDest.error(plain) == "Folder tujuan tidak ditemukan.")
        check("error: folder valid -> null", ExtractDest.error(sub) == null)

        check("hasil: jumlah diketahui", ExtractDest.resultLines(12, 0) == listOf("12 berkas diekstrak"))
        check("hasil: jumlah tak dihitung (-1)", ExtractDest.resultLines(-1, 0) == listOf("Semua isi arsip diekstrak"))
        check("hasil: ada yang diberi nama baru", ExtractDest.resultLines(3, 2) ==
            listOf("3 berkas diekstrak", "2 berkas diberi nama baru karena nama sudah ada"))
        check("hasil: nol berkas tetap dilaporkan", ExtractDest.resultLines(0, 0) == listOf("0 berkas diekstrak"))
    } finally {
        d.deleteRecursively()
    }
    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails > 0) System.exit(1)
}
