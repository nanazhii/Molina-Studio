import com.molina.code.guard.ChangeFeed
import com.molina.code.guard.ProjectLog
import java.io.File
import java.nio.file.Files

// [CODE-FEED] Uji penyimpanan notifikasi perubahan berkas (JVM murni).
private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun entry(t: Long, src: String, vararg files: Pair<String, Char>, omitted: Int = 0) =
    ProjectLog.Entry(t, src, "pesan $t", files.map { ProjectLog.FileChange(it.first, it.second, 1, 0) }, omitted)

fun main() {
    val tmp = Files.createTempDirectory("changefeed").toFile()
    try {
        val f = File(tmp, "priv/changes.jsonl")
        check("kosong: read/unread aman bila berkas belum ada", ChangeFeed.read(f).isEmpty() && ChangeFeed.unreadCount(f) == 0)

        check("append: berhasil, dibuat beserta folder induk", ChangeFeed.append(f, File("/sdcard/work"), entry(1, ChangeFeed.SRC_NANO, "a.sh" to 'M')) && f.isFile)
        ChangeFeed.append(f, File("/sdcard/lain"), entry(2, ProjectLog.SRC_SCRIPT, "x.py" to 'A', "y.py" to 'D'))
        val r = ChangeFeed.read(f)
        check("read: terbaru dulu, folder dan sumber benar", r.size == 2 && r[0].folder == "/sdcard/lain" && r[0].entry.source == ProjectLog.SRC_SCRIPT && r[1].folder == "/sdcard/work")
        check("read: isi berkas utuh (jenis, +/-)", r[0].entry.files.map { it.path to it.kind } == listOf("x.py" to 'A', "y.py" to 'D'))
        check("unread: semua belum dibaca", r.all { it.unread } && ChangeFeed.unreadCount(f) == 2)

        ChangeFeed.markAllRead(f)
        val r2 = ChangeFeed.read(f)
        check("markAllRead: semua dibaca, urutan dan isi tetap", r2.none { it.unread } && r2.size == 2 && r2[0].entry.time == 2L && ChangeFeed.unreadCount(f) == 0)
        ChangeFeed.append(f, File("/sdcard/work"), entry(3, ChangeFeed.SRC_NANO, "b.sh" to 'M'))
        check("append sesudah dibaca: hanya yang baru belum dibaca", ChangeFeed.unreadCount(f) == 1 && ChangeFeed.read(f)[0].unread && !ChangeFeed.read(f)[1].unread)

        check("folder dengan tab ditolak", !ChangeFeed.append(f, File("/sdcard/a\tb"), entry(4, "nano", "a" to 'M')))
        check("folder dengan baris baru ditolak", !ChangeFeed.append(f, File("/sdcard/a\nb"), entry(4, "nano", "a" to 'M')))
        check("folder kosong ditolak", !ChangeFeed.append(f, File(""), entry(4, "nano", "a" to 'M')))

        // baris rusak dilewati, tanpa baris baru di akhir ditangani
        f.appendText("sampah tanpa tab\nFEED1\t9\t/x\t{}\nFEED1\t1\t/x\t{bukan json}")
        ChangeFeed.append(f, File("/sdcard/work"), entry(5, "nano", "c.sh" to 'A'))
        val r3 = ChangeFeed.read(f)
        check("baris rusak dilewati, butir sesudahnya tetap terbaca", r3.size == 4 && r3[0].entry.time == 5L)

        // path folder dengan spasi/Unicode, pesan dengan tanda kutip
        val g = File(tmp, "priv/u.jsonl")
        ChangeFeed.append(g, File("/sdcard/Proyek Saya/\u00e9"), ProjectLog.Entry(9, "nano", "pesan \"kutip\" \\ ok", listOf(ProjectLog.FileChange("a b.sh", 'M', 2, 1))))
        val u = ChangeFeed.read(g).single()
        check("Unicode/spasi/kutip bertahan", u.folder == "/sdcard/Proyek Saya/\u00e9" && u.entry.message == "pesan \"kutip\" \\ ok" && u.entry.files[0].path == "a b.sh")

        // batas jumlah
        val h = File(tmp, "priv/banyak.jsonl")
        for (i in 1..(ChangeFeed.MAX_ITEMS + 40)) ChangeFeed.append(h, File("/sdcard/w"), entry(i.toLong(), "nano", "a" to 'M'))
        val hr = ChangeFeed.read(h)
        check("prune: tidak melebihi batas, yang terbaru dipertahankan", hr.size <= ChangeFeed.MAX_ITEMS + 20 && hr[0].entry.time == (ChangeFeed.MAX_ITEMS + 40).toLong() && hr.size >= ChangeFeed.MAX_ITEMS)

        // headline
        fun it(folder: String, e: ProjectLog.Entry) = ChangeFeed.Item(folder, true, e)
        check("headline: diubah satu berkas", ChangeFeed.headline(it("/sdcard/work", entry(1, "nano", "a.sh" to 'M'))) == "a.sh di folder work diubah")
        check("headline: ditambah + berkas lain", ChangeFeed.headline(it("/sdcard/work", entry(1, "nano", "a.sh" to 'A', "b.sh" to 'M', "c.sh" to 'D'))) == "a.sh di folder work ditambah dan 2 berkas lain")
        check("headline: dihapus", ChangeFeed.headline(it("/sdcard/work", entry(1, "nano", "a.sh" to 'D'))) == "a.sh di folder work dihapus")
        check("headline: hitung berkas terpotong (omitted)", ChangeFeed.headline(it("/sdcard/work", entry(1, "nano", "a.sh" to 'M', omitted = 5))) == "a.sh di folder work diubah dan 5 berkas lain")
        check("headline: tanpa berkas -> pesan", ChangeFeed.headline(it("/sdcard/work", ProjectLog.Entry(1, "nano", "catatan", emptyList()))) == "catatan")
        check("headline: folder akar memakai jalur penuh", ChangeFeed.headline(it("/", entry(1, "nano", "a.sh" to 'M'))) == "a.sh di folder / diubah")
        check("sourceLabel", ChangeFeed.sourceLabel("nano") == "via nano" && ChangeFeed.sourceLabel("script") == "dari Code" && ChangeFeed.sourceLabel("?") == "")

        ChangeFeed.clear(f)
        check("clear: berkas hilang, read kosong", !f.exists() && ChangeFeed.read(f).isEmpty())
    } finally {
        tmp.deleteRecursively()
    }
    if (fails > 0) { println("$fails GAGAL"); kotlin.system.exitProcess(1) }
    println("semua lulus")
}
