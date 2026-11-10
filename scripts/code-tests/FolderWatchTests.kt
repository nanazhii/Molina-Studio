import com.molina.code.guard.ChangeFeed
import com.molina.code.guard.FolderWatch
import com.molina.code.guard.ProjectLog
import com.molina.code.guard.RunReport
import java.io.File
import java.nio.file.Files

// [CODE-FEED4] Uji sidik jari folder (JVM murni).
private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun put(root: File, rel: String, text: String, mtime: Long) {
    val f = File(root, rel)
    f.parentFile.mkdirs()
    f.writeText(text)
    f.setLastModified(mtime)
}

private fun m(size: Long, mtime: Long) = RunReport.Meta(size, mtime)

fun main() {
    val tmp = Files.createTempDirectory("folderwatch").toFile()
    try {
        val root = File(tmp, "proj")
        put(root, "a.sh", "echo a", 10_000_000)
        put(root, "src/b.kt", "val b = 1", 10_000_000)
        put(root, "ruang kosong/e.txt", "x", 10_000_000)
        put(root, ".git/config", "x", 10_000_000)
        put(root, "build/out.txt", "x", 10_000_000)
        put(root, "src/__pycache__/x.pyc", "x", 10_000_000)
        put(root, "tab\there.txt", "x", 10_000_000)

        val c1 = FolderWatch.capture(root)!!
        check("capture: berkas biasa masuk (jalur relatif, spasi)", c1.keys == setOf("a.sh", "src/b.kt", "ruang kosong/e.txt"))
        check("capture: ukuran dan mtime dicatat", c1["a.sh"]!!.size == 6L && c1["a.sh"]!!.mtime == 10_000_000L)
        check("capture: folder tak ada -> kosong", FolderWatch.capture(File(tmp, "tidakada"))!!.isEmpty())

        check("diff: tanpa perubahan -> kosong", FolderWatch.diff(c1, c1).isEmpty())

        put(root, "a.sh", "echo aa", 10_000_000)       // ukuran beda
        put(root, "src/b.kt", "val b = 2", 10_900_000) // mtime beda jauh, ukuran sama
        File(root, "ruang kosong/e.txt").delete()
        put(root, "baru.txt", "n", 11_000_000)
        val c2 = FolderWatch.capture(root)!!
        val d = FolderWatch.diff(c1, c2)
        check("diff: A/M/D benar dan urut menurut jalur", d.map { it.path to it.kind } ==
            listOf("a.sh" to 'M', "baru.txt" to 'A', "ruang kosong/e.txt" to 'D', "src/b.kt" to 'M'))

        // selisih mtime kecil dengan ukuran sama dianggap tidak berubah
        check("diff: selisih mtime <= 2 detik diabaikan", FolderWatch.diff(mapOf("x" to m(5, 1000)), mapOf("x" to m(5, 2500))).isEmpty())
        check("diff: selisih mtime > 2 detik = diubah", FolderWatch.diff(mapOf("x" to m(5, 1000)), mapOf("x" to m(5, 3500))).map { it.kind } == listOf('M'))

        // entry
        val e = FolderWatch.entry(d, 777)
        check("entry: sumber external, pesan, waktu, daftar utuh", e.source == ChangeFeed.SRC_EXTERNAL && e.message == FolderWatch.MESSAGE && e.time == 777L && e.files.size == 4 && e.omitted == 0)
        val big = (1..(ProjectLog.MAX_FILES_PER_ENTRY + 30)).map { ProjectLog.FileChange("f$it", 'A', 0, 0) }
        val eb = FolderWatch.entry(big, 1)
        check("entry: dipotong ke batas, sisanya dihitung omitted", eb.files.size == ProjectLog.MAX_FILES_PER_ENTRY && eb.omitted == 30 && eb.fileCount == big.size)
        check("headline entry folder", ChangeFeed.headline(ChangeFeed.Item("/sdcard/proj", true, e)) == "a.sh di folder proj diubah dan 3 berkas lain")

        // simpan/muat
        val priv = File(tmp, "priv/watch")
        val key = FolderWatch.keyFile(priv, root)
        check("keyFile: stabil, beda untuk folder lain", key == FolderWatch.keyFile(priv, root) && key != FolderWatch.keyFile(priv, File(tmp, "lain")) && key.name.endsWith(".tsv"))
        check("load: belum ada -> null", FolderWatch.load(key, root) == null)
        check("save: berhasil", FolderWatch.save(key, FolderWatch.Baseline(5000, root.path, c2)) && key.isFile && !File(key.path + ".tmp").exists())
        val b = FolderWatch.load(key, root)!!
        check("load: waktu, folder, isi bertahan (Unicode/spasi)", b.time == 5000L && b.root == root.path && b.meta.keys == c2.keys &&
            b.meta.all { (k, v) -> c2[k]!!.size == v.size && c2[k]!!.mtime == v.mtime })
        check("load: folder lain -> null", FolderWatch.load(key, File(tmp, "lain")) == null)
        val uni = File(priv, "uni.tsv")
        FolderWatch.save(uni, FolderWatch.Baseline(1, "/sdcard/Proyek \u00e9", mapOf("dir \u00e9/a \u00fc.txt" to m(3, 4))))
        val ub = FolderWatch.load(uni, File("/sdcard/Proyek \u00e9"))
        check("save/load: Unicode dan spasi di jalur bertahan", ub != null && ub.meta.keys == setOf("dir \u00e9/a \u00fc.txt") && ub.meta.values.single().size == 3L)

        key.appendText("sampah tanpa tab\nx\ty\tz\n1\t2\t\n")
        val b2 = FolderWatch.load(key, root)!!
        check("load: baris rusak dilewati", b2.meta.keys == c2.keys)
        key.writeText("bukan baseline\n")
        check("load: header rusak -> null", FolderWatch.load(key, root) == null)
        check("save: folder berisi tab ditolak", !FolderWatch.save(key, FolderWatch.Baseline(1, "/a\tb", c2)))

        // coveredSince
        val feed = File(tmp, "priv/changes.jsonl")
        val target = File(root, "src/b.kt")
        check("coveredSince: feed kosong -> false", !ChangeFeed.coveredSince(feed, target, 0))
        ChangeFeed.append(feed, root, ProjectLog.Entry(9000, ChangeFeed.SRC_NANO, "n", listOf(ProjectLog.FileChange("src/b.kt", 'M', 1, 1))))
        check("coveredSince: butir sesudah baseline memuat berkas -> true", ChangeFeed.coveredSince(feed, target, 8000))
        check("coveredSince: butir sebelum baseline -> false", !ChangeFeed.coveredSince(feed, target, 9500))
        check("coveredSince: berkas lain -> false", !ChangeFeed.coveredSince(feed, File(root, "a.sh"), 8000))
    } finally {
        tmp.deleteRecursively()
    }
    if (fails > 0) { println("$fails GAGAL"); kotlin.system.exitProcess(1) }
    println("semua lulus")
}
