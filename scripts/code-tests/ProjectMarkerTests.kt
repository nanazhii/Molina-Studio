import com.molina.code.guard.MiniJson
import com.molina.code.guard.ProjectMarker
import com.molina.code.guard.RunReport
import java.io.File

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun tmp(): File = java.nio.file.Files.createTempDirectory("pm").toFile()

// [CODE-PROJECT] Uji penanda proyek lokal (.molina)
fun main() {
    val d = tmp()
    check("baru: CREATED", ProjectMarker.create(d, 1_700_000_000_000L) == ProjectMarker.Result.CREATED)
    val pj = File(d, ".molina/project.json")
    check("project.json ada", pj.isFile)
    check("project.json JSON valid", MiniJson.valid(pj.readText()))
    check("project.json memuat nama folder", pj.readText().contains("\"name\": \"" + d.name + "\""))
    check("project.json memuat tanggal UTC", pj.readText().contains("\"created\": \"2023-11-14\""))
    check("project.json memuat versi format", pj.readText().contains("\"format\": 1"))
    check("kedua kali: ALREADY", ProjectMarker.create(d, 1L) == ProjectMarker.Result.ALREADY)
    check("kedua kali tidak menimpa isi", pj.readText().contains("2023-11-14"))
    check("markerIn mengenali .molina", ProjectMarker.markerIn(d) == ".molina")

    val g = tmp()
    File(g, ".git").mkdirs()
    check("repo Git: ALREADY, tidak membuat .molina", ProjectMarker.create(g, 1L) == ProjectMarker.Result.ALREADY && !File(g, ".molina").exists())
    check("markerIn mengenali .git", ProjectMarker.markerIn(g) == ".git")

    val n = tmp()
    check("tanpa penanda: markerIn null", ProjectMarker.markerIn(n) == null)
    check("folder tidak ada: FAILED", ProjectMarker.create(File(n, "tidak-ada"), 1L) == ProjectMarker.Result.FAILED)
    val f = File(n, "berkas.txt"); f.writeText("x")
    check("bukan folder: FAILED", ProjectMarker.create(f, 1L) == ProjectMarker.Result.FAILED)
    val blocked = tmp()
    File(blocked, ".molina").writeText("berkas biasa")
    check("'.molina' berupa berkas: ALREADY (tidak ditimpa)", ProjectMarker.create(blocked, 1L) == ProjectMarker.Result.ALREADY && File(blocked, ".molina").isFile)

    val q = File(tmp(), "ab\"c\\d"); q.mkdirs()
    check("nama dengan kutip dan backslash: JSON tetap valid", ProjectMarker.create(q, 1L) == ProjectMarker.Result.CREATED && MiniJson.valid(File(q, ".molina/project.json").readText()))

    val deep = File(d, "a/b/c"); deep.mkdirs()
    val ri = RunReport.findRootInfo(deep)
    check("RunReport menemukan akar lewat .molina buatan ProjectMarker", ri.root == d && ri.how == ".molina")

    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails != 0) kotlin.system.exitProcess(1)
}
