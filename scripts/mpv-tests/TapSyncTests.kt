import com.molina.cinema.*
import java.io.File

private var failed = 0
private fun check(name: String, cond: Boolean) {
    if (cond) println("OK   $name") else { println("GAGAL $name"); failed++ }
}

fun main() {
    // --- format waktu
    check("format 0", LrcWriter.formatTime(0) == "00:00.00")
    check("format 62_345 ms -> pembulatan cs", LrcWriter.formatTime(62_345) == "01:02.35")
    check("format negatif dijepit", LrcWriter.formatTime(-50) == "00:00.00")
    check("format 100 menit (3 digit)", LrcWriter.formatTime(6_000_000) == "100:00.00")

    // --- sesi dasar + kompensasi reaksi
    val s = TapSession(listOf("Satu", "", "  Dua ", "Tiga"), reactionMs = 100)
    check("baris kosong dibuang", s.size == 3 && !s.isComplete)
    val r1 = s.tap(1_000) as TapResult.Ok
    check("reaksi dikurangi", r1.stampMs == 900L && r1.index == 0)
    check("tap awal sebelum reaksi dijepit 0", (TapSession(listOf("A"), 500).tap(200) as TapResult.Ok).stampMs == 0L)
    val r2 = s.tap(950) as TapResult.Ok
    check("tidak mundur dari baris sebelumnya", r2.stampMs == 900L)
    s.tap(5_000)
    check("selesai setelah semua baris", s.isComplete)
    check("tap setelah selesai ditolak", (s.tap(6_000) as TapResult.Rejected).reason == TapReject.COMPLETE)
    check("sesi kosong ditolak", (TapSession(listOf("", " ")).tap(1) as TapResult.Rejected).reason == TapReject.EMPTY)

    // --- undo
    val u = TapSession(listOf("A", "B", "C"), 0)
    check("undo tanpa riwayat false", !u.undo())
    u.tap(1_000); u.tap(2_000)
    check("undo berhasil", u.undo() && u.cursor == 1 && u.stampOf(1) == null && u.stampOf(0) == 1_000L)
    u.tap(2_500)
    check("tap ulang setelah undo", u.stampOf(1) == 2_500L)

    // --- jeda (instrumental)
    val b = TapSession(listOf("A", "B"), 0)
    b.tap(1_000)
    b.tapBreak(4_000)
    b.tap(6_000)
    check("jeda tersisip di urutan", b.isComplete && b.size == 3 && b.isBreak(1) && b.textOf(2) == "B")
    check("undo jeda menghapus entri", run { val c = TapSession(listOf("A"), 0); c.tapBreak(500); c.undo() && c.size == 1 && c.cursor == 0 })
    val lrcB = b.toLrc()!!
    val docB = LrcParser.parse(lrcB)
    check("jeda round-trip jadi baris kosong", docB.lines.size == 3 && docB.lines[1].text.isEmpty() && docB.lines[1].startMs == 4_000L)

    // --- rewindTo
    val w = TapSession(listOf("A", "B", "C", "D"), 0)
    w.tap(1_000); w.tap(2_000); w.tapBreak(2_500); w.tap(3_000)
    w.rewindTo(1)
    check("rewind: kursor, cap, dan jeda dibersihkan", w.cursor == 1 && w.size == 4 && w.stampOf(0) == 1_000L && w.stampOf(1) == null)
    check("undo setelah rewind hanya sampai batas", w.undo() && w.cursor == 0 && !w.undo())

    // --- nudge dijepit tetangga
    val n = TapSession(listOf("A", "B", "C"), 0)
    n.tap(1_000); n.tap(2_000); n.tap(3_000)
    check("nudge normal", n.nudge(1, 300) && n.stampOf(1) == 2_300L)
    check("nudge dijepit tetangga atas", n.nudge(1, 5_000) && n.stampOf(1) == 3_000L)
    check("nudge dijepit tetangga bawah", n.nudge(1, -9_000) && n.stampOf(1) == 1_000L)
    check("nudge baris belum bercap false", !TapSession(listOf("A"), 0).nudge(0, 10))

    // --- belum selesai tidak bisa disimpan
    check("toLrc null bila belum selesai", TapSession(listOf("A", "B"), 0).also { it.tap(1) }.toLrc() == null)

    // --- round-trip dengan parser, termasuk meta dan karakter khusus
    val rt = TapSession(listOf("Halo [dunia]", "Baris dua"), 0)
    rt.tap(1_230); rt.tap(65_400)
    val out = rt.toLrc("Judul ]x", "Artis", null)!!
    val back = LrcParser.parse(out)
    check("round-trip waktu", back.lines.map { it.startMs } == listOf(1_230L, 65_400L))
    check("round-trip teks", back.lines.map { it.text } == listOf("Halo [dunia]", "Baris dua"))
    check("round-trip meta", back.title == "Judul )x" && back.artist == "Artis" && back.album == null)

    // --- penyimpan berkas
    val tmp = File(System.getProperty("java.io.tmpdir"), "tapsync_" + System.nanoTime()).apply { mkdirs() }
    try {
        val media = File(tmp, "lagu.mp3").apply { writeText("x") }
        val userDir = File(tmp, "user").path
        check("simpan menolak LRC tak valid", LyricsSaver.save(media.path, "bukan lrc", userDir) is LyricsSaver.Result.Failed)
        val res = LyricsSaver.save(media.path, out, userDir) as LyricsSaver.Result.Saved
        check("simpan baru di samping lagu", res.file.name == "lagu.lrc" && res.backup == null && res.file.readText() == out)
        check("tidak ada sisa tmp", tmp.listFiles()!!.none { it.name.endsWith(".tmp") })
        // lirik polos lama dicadangkan sekali
        File(tmp, "lagu.lrc").writeText("baris polos satu\nbaris polos dua\n")
        val res2 = LyricsSaver.save(media.path, out, userDir) as LyricsSaver.Result.Saved
        check("cadangan polos dibuat", res2.backup?.readText() == "baris polos satu\nbaris polos dua\n")
        val out2 = out.replace("01:", "02:")
        val res3 = LyricsSaver.save(media.path, out2, userDir) as LyricsSaver.Result.Saved
        check("cadangan pertama tidak ditimpa", res3.backup == null && File(tmp, "lagu.lrc.bak").readText().startsWith("baris polos"))
        check("isi terbaru tertulis", File(tmp, "lagu.lrc").readText() == out2)
    } finally { tmp.deleteRecursively() }

    if (failed > 0) { println("$failed tes GAGAL"); System.exit(1) } else println("Semua tes lulus")
}
