import com.molina.code.guard.ChangeFeed // [EXT-FEED8]
import com.molina.code.guard.FeedSummary
import com.molina.code.guard.ProjectLog

// [EXT-FEED7] Uji ringkasan butir notifikasi perubahan (JVM murni).
private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun fc(p: String, k: Char, a: Int, r: Int) = ProjectLog.FileChange(p, k, a, r)

fun main() {
    val e = ProjectLog.Entry(1L, "external", "pesan", listOf(fc("src/a.kt", 'M', 12, 3), fc("b.kt", 'A', 5, 0), fc("c.kt", 'D', 0, 0)))
    val p = FeedSummary.plain("/sdcard/work", "10 Nov 2026 14:32", "dari luar", e).lines()
    check("plain: judul memuat folder, waktu, sumber", p[0] == "Perubahan di work \u00B7 10 Nov 2026 14:32 \u00B7 dari luar")
    check("plain: baris M dengan +/-", p[1] == "M  src/a.kt  +12 -3")
    check("plain: baris A hanya tambah, +0 -0 tidak tampil untuk D", p[2] == "A  b.kt  +5 -0" && p[3] == "D  c.kt")
    check("plain: total berkas dan +/-", p[4] == "Total 3 berkas, +17 -3" && p.size == 5)
    check("plain: tanpa sumber tidak ada pemisah ganda", FeedSummary.plain("/x/y", "t", "", e).lines()[0] == "Perubahan di y \u00B7 t")
    check("plain: folder akar memakai jalur utuh", FeedSummary.plain("/", "t", "", e).startsWith("Perubahan di / "))

    val o = ProjectLog.Entry(2L, "external", "m", listOf(fc("a", 'M', 1, 1)), omitted = 4)
    val po = FeedSummary.plain("/w", "t", "", o)
    check("plain omitted: catatan dan total jujur", po.contains("(+4 berkas lain tidak dimuat)") && po.endsWith("Total 5 berkas, +1 -1 (hanya berkas yang dimuat)"))

    val none = ProjectLog.Entry(3L, "nano", "skrip jalan", emptyList())
    check("plain tanpa berkas: pakai pesan, tanpa Total", FeedSummary.plain("/w", "t", "", none) == "Perubahan di w \u00B7 t\nskrip jalan")

    val inj = ProjectLog.Entry(4L, "x", "m", listOf(fc("a\nb\r.kt", 'M', 1, 0)))
    check("path dengan baris baru dinetralkan", FeedSummary.plain("/w", "t", "", inj).lines().size == 3)

    val c = FeedSummary.commit(e).lines()
    check("commit: subjek ubah (campuran) dengan total", c[0] == "chore: ubah 3 berkas (+17 -3)" && c[1] == "")
    check("commit: butir berkas", c[2] == "- M src/a.kt +12 -3" && c[3] == "- A b.kt +5 -0" && c[4] == "- D c.kt")
    val ca = FeedSummary.commit(ProjectLog.Entry(5L, "x", "m", listOf(fc("a", 'A', 1, 0), fc("b", 'A', 2, 0))))
    check("commit: semua A = tambah", ca.startsWith("chore: tambah 2 berkas (+3 -0)"))
    val cd = FeedSummary.commit(ProjectLog.Entry(6L, "x", "m", listOf(fc("a", 'D', 0, 0))))
    check("commit: semua D = hapus", cd == "chore: hapus 1 berkas (+0 -0)\n\n- D a")
    check("commit omitted: jumlah memuat berkas tak dimuat", FeedSummary.commit(o).let { it.startsWith("chore: ubah 5 berkas") && it.endsWith("- dan 4 berkas lain") })
    check("commit tanpa berkas: hanya subjek", FeedSummary.commit(none) == "chore: ubah 0 berkas (+0 -0)")

    // [EXT-FEED8] laporan gabungan per folder
    val i1 = ChangeFeed.Item("/sdcard/work", true, ProjectLog.Entry(300L, ChangeFeed.SRC_EXTERNAL, "m", listOf(fc("a", 'M', 1, 2))))
    val i2 = ChangeFeed.Item("/sdcard/lain", false, ProjectLog.Entry(200L, ChangeFeed.SRC_NANO, "m", listOf(fc("x", 'A', 3, 0), fc("y", 'D', 0, 0)), omitted = 1))
    val i3 = ChangeFeed.Item("/sdcard/work", false, ProjectLog.Entry(100L, ProjectLog.SRC_SCRIPT, "m", listOf(fc("b", 'M', 5, 1))))
    val rp = FeedSummary.report(listOf(i1, i2, i3), "now") { "t$it" }.lines()
    check("report: judul dan ringkasan total", rp[0] == "Laporan perubahan \u00B7 now" && rp[1] == "3 peristiwa, 2 folder, 5 berkas, +9 -3")
    check("report: folder terbaru di atas, butir satu folder berdampingan", rp[3] == "Folder: /sdcard/work" && rp[4] == "t300 \u00B7 dari luar" && rp[6] == "t100 \u00B7 dari Code" && rp[9] == "Folder: /sdcard/lain")
    check("report: baris berkas dan catatan omitted terindentasi", rp[5] == "  M  a  +1 -2" && rp[11] == "  A  x  +3 -0" && rp[12] == "  D  y" && rp[13] == "  (+1 berkas lain tidak dimuat)" && rp.size == 14)
    check("report: daftar kosong menghasilkan teks kosong", FeedSummary.report(emptyList(), "now") { "t$it" } == "")
    val many = (1..60).map { ChangeFeed.Item("/f", false, ProjectLog.Entry(it.toLong(), ChangeFeed.SRC_NANO, "m", listOf(fc("p$it", 'M', 1, 0)))) }
    val rm = FeedSummary.report(many, "now") { "t$it" }
    check("report: dibatasi 50 butir terbaru dengan catatan sisanya", rm.contains("50 peristiwa, 1 folder") && rm.contains("(10 peristiwa lebih lama tidak disertakan)") && rm.contains("p50") && !rm.contains("p51"))
    val rn = FeedSummary.report(listOf(ChangeFeed.Item("/w", false, ProjectLog.Entry(1L, ChangeFeed.SRC_NANO, "skrip\njalan", emptyList()))), "now") { "t$it" }
    check("report: butir tanpa berkas memakai pesan satu baris", rn.endsWith("t1 \u00B7 via nano\n  skrip jalan"))

    if (fails > 0) { println("$fails GAGAL"); kotlin.system.exitProcess(1) }
    println("semua lulus")
}
