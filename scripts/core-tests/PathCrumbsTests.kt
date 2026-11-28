import com.molina.core.files.PathCrumbs

// [CODE-PATHBAR] [DRAWER-CRUMBS] Uji breadcrumb path (JVM murni).
private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun show(path: String, keep: Int = 2): String {
    val t = PathCrumbs.tail(path, keep)
    return (if (t.truncated) "\u2026 " else "") + t.parts.joinToString(" > ")
}

private fun paths(path: String, keep: Int = 2): String = PathCrumbs.tail(path, keep).crumbs.joinToString(",") { it.path }

fun main() {
    check("akar penyimpanan = Internal", show("/storage/emulated/0") == "Internal")
    check("folder langsung di Internal", show("/storage/emulated/0/Molina-Studio") == "Internal > Molina-Studio")
    check("berkas dalam folder: dua segmen terakhir, tanda terpotong", show("/storage/emulated/0/Molina-Studio/a.kt") == "\u2026 Molina-Studio > a.kt")
    check("bertingkat dalam: hanya ekor", show("/storage/emulated/0/a/b/c/d.kt") == "\u2026 c > d.kt")
    check("awalan /sdcard juga Internal", show("/sdcard/Molina-Studio") == "Internal > Molina-Studio")
    check("awalan mirip tidak salah cocok", show("/storage/emulated/01/x") == "\u2026 01 > x" && show("/sdcardx/y") == "sdcardx > y")
    check("jalur lain tanpa Internal", show("/data/data/app/files/n.txt") == "\u2026 files > n.txt")
    check("jalur akar", show("/") == "/" && show("") == "/")
    check("garis miring ganda/akhir diabaikan", show("/storage/emulated/0//Molina-Studio/") == "Internal > Molina-Studio")
    check("keep 3 menampilkan lebih banyak", show("/storage/emulated/0/a/b.kt", 3) == "Internal > a > b.kt")
    check("keep <= 0 diperlakukan 1", show("/storage/emulated/0/a/b.kt", 0) == "\u2026 b.kt")
    check("tepat sebanyak keep: tidak terpotong", !PathCrumbs.tail("/storage/emulated/0/a", 2).truncated)
    // jalur tiap segmen untuk navigasi
    check("path: Internal -> akar penyimpanan", paths("/storage/emulated/0/Molina") == "/storage/emulated/0,/storage/emulated/0/Molina")
    check("path: bertingkat, hanya ekor", paths("/storage/emulated/0/a/b/c") == "/storage/emulated/0/a/b,/storage/emulated/0/a/b/c")
    check("path: /sdcard mempertahankan awalan /sdcard", paths("/sdcard/Molina") == "/sdcard,/sdcard/Molina")
    check("path: tanpa Internal", paths("/data/app/files") == "/data/app,/data/app/files")
    check("path: garis miring ganda dinormalkan", paths("/storage/emulated/0//Molina/") == "/storage/emulated/0,/storage/emulated/0/Molina")
    check("path: akar", paths("/") == "/")
    if (fails > 0) { println("$fails GAGAL"); kotlin.system.exitProcess(1) }
    println("semua lulus")
}
