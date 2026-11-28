import com.molina.code.guard.ProjectLog
import com.molina.code.guard.ProjectMarker
import com.molina.code.guard.RunReport
import java.io.File

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun tmp(): File = java.nio.file.Files.createTempDirectory("pl").toFile()

private fun proj(): File = tmp().also { ProjectMarker.create(it, 1_700_000_000_000L) }

private fun entry(t: Long, msg: String = "Skrip: a.py", n: Int = 2): ProjectLog.Entry =
    ProjectLog.Entry(t, ProjectLog.SRC_SCRIPT, msg, (1..n).map { ProjectLog.FileChange("d/f$it.txt", 'M', it, 0) })

// [CODE-PROJLOG] Uji riwayat per proyek (.molina/log.jsonl)
fun main() {
    val d = proj()
    check("proyek .molina: isEnabled", ProjectLog.isEnabled(d))
    check("belum ada log: read kosong", ProjectLog.read(d).isEmpty())
    check("append pertama berhasil", ProjectLog.append(d, entry(1000L)))
    check("append kedua berhasil", ProjectLog.append(d, entry(2000L, "Skrip: b.py", 1)))
    val r = ProjectLog.read(d)
    check("read: 2 entri, terbaru dulu", r.size == 2 && r[0].time == 2000L && r[1].time == 1000L)
    check("read: pesan dan sumber utuh", r[1].message == "Skrip: a.py" && r[1].source == ProjectLog.SRC_SCRIPT)
    check("read: berkas utuh", r[1].files.size == 2 && r[1].files[1].path == "d/f2.txt" && r[1].files[1].added == 2 && r[1].files[1].kind == 'M')
    check("log.jsonl: satu baris per entri", File(d, ".molina/log.jsonl").readLines().size == 2)

    val n = tmp()
    check("tanpa penanda: isEnabled false", !ProjectLog.isEnabled(n))
    check("tanpa penanda: append ditolak dan tidak membuat .molina", !ProjectLog.append(n, entry(1L)) && !File(n, ".molina").exists())
    val g = tmp(); File(g, ".git").mkdirs()
    check("repo Git: append ditolak", !ProjectLog.isEnabled(g) && !ProjectLog.append(g, entry(1L)) && !File(g, ".molina").exists())
    val gm = proj(); File(gm, ".git").mkdirs()
    check("Git + .molina: tetap ditolak (Git punya riwayat sendiri)", !ProjectLog.append(gm, entry(1L)))

    val c = proj()
    ProjectLog.append(c, entry(10L))
    File(c, ".molina/log.jsonl").appendText("{rusak tidak lengkap\n\n")
    ProjectLog.append(c, entry(20L))
    val rc = ProjectLog.read(c)
    check("baris rusak dilewati, entri sehat tetap terbaca", rc.size == 2 && rc[0].time == 20L && rc[1].time == 10L)

    val t = proj()
    ProjectLog.append(t, entry(1L))
    File(t, ".molina/log.jsonl").appendText("{\"v\":1,\"t\":2,\"s\":\"scr")
    check("ekor terpotong tanpa newline: append menyambung di baris baru", ProjectLog.append(t, entry(3L)) && ProjectLog.read(t).map { it.time } == listOf(3L, 1L))

    val w = proj()
    val odd = ProjectLog.Entry(5L, "script", "Skrip: \"x\" \\ \n tab\t é 日本 \u0001", listOf(ProjectLog.FileChange("a/\"b\\c é.txt", 'A', 3, 0)))
    ProjectLog.append(w, odd)
    val ro = ProjectLog.read(w)[0]
    check("karakter khusus dan unicode bolak-balik", ro.message == odd.message && ro.files[0].path == odd.files[0].path)
    check("encode satu baris (tanpa newline mentah)", !ProjectLog.encode(odd).contains('\n'))

    check("decode: kosong/sampah -> null", ProjectLog.decode("") == null && ProjectLog.decode("bukan json") == null && ProjectLog.decode("[1,2]") == null)
    check("decode: tanpa t -> null", ProjectLog.decode("{\"s\":\"script\",\"m\":\"x\"}") == null)
    check("decode: kind tak dikenal dilewati", ProjectLog.decode("{\"t\":1,\"s\":\"script\",\"m\":\"x\",\"f\":[{\"p\":\"a\",\"k\":\"Z\"},{\"p\":\"b\",\"k\":\"A\"}]}")!!.files.size == 1)

    val big = proj()
    val many = (1..ProjectLog.MAX_FILES_PER_ENTRY + 30).map { ProjectLog.FileChange("f$it", 'A', 1, 0) }
    val rep = RunReport.Report(big, many.map { RunReport.Change(it.path, RunReport.Kind.ADDED, 1, 0, true, "x", emptyList()) })
    val e = ProjectLog.fromReport(rep, 99L, ProjectLog.SRC_SCRIPT, "Skrip: besar")
    check("fromReport: dibatasi MAX_FILES_PER_ENTRY, sisanya di omitted", e.files.size == ProjectLog.MAX_FILES_PER_ENTRY && e.omitted == 30 && e.fileCount == ProjectLog.MAX_FILES_PER_ENTRY + 30)
    check("fromReport: jenis dipetakan", ProjectLog.fromReport(
        RunReport.Report(big, listOf(
            RunReport.Change("a", RunReport.Kind.ADDED, 1, 0, true, null, emptyList()),
            RunReport.Change("b", RunReport.Kind.MODIFIED, 1, 1, true, null, emptyList()),
            RunReport.Change("c", RunReport.Kind.DELETED, 0, 4, true, null, emptyList()))),
        1L, ProjectLog.SRC_SCRIPT, "x").let { it.addedFiles == 1 && it.modifiedFiles == 1 && it.deletedFiles == 1 })
    ProjectLog.append(big, e)
    check("omitted bolak-balik lewat berkas", ProjectLog.read(big)[0].omitted == 30)
    val long = ProjectLog.note(1L, ProjectLog.SRC_UNDO, "a".repeat(500) + "\nbaris")
    check("pesan dipotong dan satu baris", long.message.length == ProjectLog.MAX_MESSAGE && !long.message.contains('\n'))

    val p = proj()
    for (i in 1..(ProjectLog.MAX_ENTRIES + 80)) ProjectLog.append(p, entry(i.toLong(), "e$i", 1))
    val rp = ProjectLog.read(p)
    check("pemangkasan: jumlah terbatas", rp.size <= ProjectLog.MAX_ENTRIES + 50 && rp.size >= ProjectLog.MAX_ENTRIES)
    check("pemangkasan: yang terbaru dipertahankan", rp[0].time == (ProjectLog.MAX_ENTRIES + 80).toLong())

    val s = proj()
    File(s, "a.txt").writeText("1\n")
    val snap = RunReport.capture(s, System.currentTimeMillis(), ".molina")!!
    ProjectLog.append(s, entry(1L))
    Thread.sleep(20)
    File(s, "a.txt").writeText("2\n")
    val rr = RunReport.compare(snap)!!
    check("RunReport mengabaikan .molina (log tidak jadi 'perubahan')", rr.changes.map { it.path } == listOf("a.txt"))

    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails != 0) kotlin.system.exitProcess(1)
}
