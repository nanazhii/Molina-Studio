import com.molina.studio.preview.ExplorerPreview

private var fails = 0
private var runs = 0
private fun check(name: String, ok: Boolean) { runs++; if (!ok) { fails++; println("GAGAL: $name") } }

fun main() {
    for (n in listOf("a.md", "README.markdown", "x.mdown", "index.html", "p.htm", "d.xhtml", "data.json", "A.MD", "DATA.JSON", "nama dengan spasi.md"))
        check("bisa: $n", ExplorerPreview.canPreview(n))
    for (n in listOf("a.txt", "run.sh", "main.py", "film.mkv", "foto.jpg", "noext", "x.kt", "build.gradle.kts", "archive.json.bak", "a.md.txt", ""))
        check("tidak: $n", !ExplorerPreview.canPreview(n))
    val dir = java.io.File(System.getProperty("java.io.tmpdir"), "ep_test_" + System.nanoTime()).apply { mkdirs() }
    val f = java.io.File(dir, "a.md").apply { writeText("# x") }
    check("berkas ada", ExplorerPreview.canPreview(f))
    check("folder bernama .md ditolak", !ExplorerPreview.canPreview(java.io.File(dir, "b.md").apply { mkdirs() }))
    check("berkas tak ada ditolak", !ExplorerPreview.canPreview(java.io.File(dir, "nope.md")))
    dir.deleteRecursively()
    println(if (fails == 0) "LULUS: $runs kasus" else "GAGAL: $fails dari $runs")
    if (fails != 0) kotlin.system.exitProcess(1)
}
