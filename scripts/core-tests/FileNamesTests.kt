import com.molina.core.fileops.FileNames

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

fun main() {
    check("berkas biasa: sebelum titik terakhir", FileNames.stemEnd("film.mkv", false) == 4)
    check("beberapa titik: titik terakhir", FileNames.stemEnd("a.b.c.srt", false) == 5)
    check("tanpa titik: seluruh nama", FileNames.stemEnd("README", false) == 6)
    check("titik di awal: seluruh nama", FileNames.stemEnd(".bashrc", false) == 7)
    check("folder: seluruh nama walau ada titik", FileNames.stemEnd("v1.2", true) == 4)
    check("nama kosong", FileNames.stemEnd("", false) == 0)
    if (fails > 0) { println("$fails gagal"); kotlin.system.exitProcess(1) }
    println("semua lulus")
}
