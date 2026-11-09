import com.molina.code.guard.ProjectCommit
import com.molina.code.guard.ProjectLog
import com.molina.code.guard.ProjectMarker
import java.io.File

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun tmp(): File = java.nio.file.Files.createTempDirectory("pc").toFile()
private fun proj(): File = tmp().also { ProjectMarker.create(it, 1_700_000_000_000L) }
private fun put(root: File, rel: String, text: String, mtime: Long? = null): File {
    val f = File(root, rel)
    f.parentFile.mkdirs()
    f.writeText(text)
    if (mtime != null) f.setLastModified(mtime)
    return f
}
private fun paths(e: ProjectLog.Entry?, kind: Char): List<String> = e?.files?.filter { it.kind == kind }?.map { it.path }?.sorted() ?: emptyList()

private const val OLD = 1_600_000_000_000L // jauh di masa lalu: bukan "racy"
private const val NOW = 1_700_000_100_000L

// [CODE-COMMIT] Uji commit manual (indeks sidik jari + log proyek)
fun main() {
    // --- penolakan
    val plain = tmp()
    check("tanpa penanda: DISABLED", ProjectCommit.commit(plain, "x", NOW).status == ProjectCommit.Status.DISABLED)
    val git = tmp(); File(git, ".git").mkdirs()
    check("repo Git: DISABLED dan tidak membuat .molina", ProjectCommit.commit(git, "x", NOW).status == ProjectCommit.Status.DISABLED && !File(git, ".molina").exists())
    check("pending: null bila bukan proyek", ProjectCommit.pending(plain) == null)

    // --- proyek kosong
    val e0 = proj()
    check("proyek kosong: NOTHING dan tanpa indeks", ProjectCommit.commit(e0, "x", NOW).status == ProjectCommit.Status.NOTHING && !File(e0, ".molina/index.tsv").exists())

    // --- commit pertama
    val d = proj()
    put(d, "a.txt", "satu\n", OLD)
    put(d, "src/b.kt", "fun b() {}\n", OLD)
    put(d, "src/deep/c.md", "# c\n", OLD)
    check("pending: null sebelum commit pertama", ProjectCommit.pending(d) == null)
    val r1 = ProjectCommit.commit(d, "Awal", NOW)
    check("commit pertama: COMMITTED", r1.status == ProjectCommit.Status.COMMITTED)
    check("commit pertama: semua berkas A", paths(r1.entry, 'A') == listOf("a.txt", "src/b.kt", "src/deep/c.md") && r1.entry!!.files.size == 3)
    check("indeks ditulis", File(d, ".molina/index.tsv").isFile)
    val lg = ProjectLog.read(d)
    check("log: satu entri berjenis commit dengan pesan dan waktu", lg.size == 1 && lg[0].source == ProjectCommit.SRC && lg[0].message == "Awal" && lg[0].time == NOW)
    check("indeks tidak memuat isi .molina", ProjectCommit.readIndex(d)!!.keys.none { it.startsWith(".molina") })
    check("pending: 0 setelah commit", ProjectCommit.pending(d) == 0)

    // --- tanpa perubahan
    val r2 = ProjectCommit.commit(d, "Lagi", NOW + 1000)
    check("commit ulang tanpa perubahan: NOTHING dan log tidak bertambah", r2.status == ProjectCommit.Status.NOTHING && ProjectLog.read(d).size == 1)

    // --- hanya waktu ubah berganti
    File(d, "a.txt").setLastModified(OLD + 5000)
    check("hanya mtime berganti (isi sama): NOTHING", ProjectCommit.commit(d, "Sentuh", NOW + 2000).status == ProjectCommit.Status.NOTHING)

    // --- ubah, tambah, hapus
    put(d, "src/b.kt", "fun b() { println(1) }\n", OLD + 9000)
    put(d, "baru.txt", "x\n", OLD)
    File(d, "a.txt").delete()
    check("pending: 3 perubahan", ProjectCommit.pending(d) == 3)
    val r3 = ProjectCommit.commit(d, "Ubah campur", NOW + 3000)
    check("M/A/D terdeteksi benar", paths(r3.entry, 'M') == listOf("src/b.kt") && paths(r3.entry, 'A') == listOf("baru.txt") && paths(r3.entry, 'D') == listOf("a.txt"))
    check("log: dua entri, terbaru dulu", ProjectLog.read(d).map { it.message } == listOf("Ubah campur", "Awal"))

    // --- ukuran sama, isi beda, mtime beda
    put(d, "baru.txt", "y\n", OLD + 20000)
    check("isi beda ukuran sama: M", paths(ProjectCommit.commit(d, "Samaukuran", NOW + 4000).entry, 'M') == listOf("baru.txt"))

    // --- racy: diubah tepat setelah commit dengan mtime sama
    val rc = proj()
    val f = put(rc, "r.txt", "aaa\n")
    val t = f.lastModified()
    check("racy: commit awal", ProjectCommit.commit(rc, "A", t + 100).status == ProjectCommit.Status.COMMITTED)
    f.writeText("bbb\n"); f.setLastModified(t)
    check("racy: ukuran dan mtime sama tapi isi beda tetap terdeteksi", paths(ProjectCommit.commit(rc, "B", t + 200).entry, 'M') == listOf("r.txt"))

    // --- nama aneh
    val w = proj()
    // nama non-ASCII hanya diuji bila JVM memakai encoding nama berkas UTF-8 (sandbox POSIX bisa memakai ASCII)
    val utf8Names = (System.getProperty("sun.jnu.encoding") ?: "").contains("UTF", ignoreCase = true)
    val weird = if (utf8Names) "a\tb\\c\nd é.txt" else "a\tb\\c\nd.txt"
    put(w, weird, "1\n", OLD)
    check("nama dengan tab/backslash/newline/unicode: commit", ProjectCommit.commit(w, "W", NOW).status == ProjectCommit.Status.COMMITTED)
    check("nama aneh: indeks bolak-balik utuh", ProjectCommit.readIndex(w)!!.keys == setOf(weird))
    put(w, weird, "22\n", OLD + 7000)
    check("nama aneh: perubahan dikenali di path yang benar", paths(ProjectCommit.commit(w, "W2", NOW + 1000).entry, 'M') == listOf(weird))

    // --- folder yang dilewati
    val s = proj()
    put(s, "keep.txt", "k\n", OLD)
    put(s, "build/out.bin", "b\n", OLD)
    put(s, "node_modules/x/y.js", "n\n", OLD)
    check("build dan node_modules dilewati", paths(ProjectCommit.commit(s, "S", NOW).entry, 'A') == listOf("keep.txt"))

    // --- tautan simbolik
    val l = proj()
    put(l, "real.txt", "r\n", OLD)
    val linked = try { java.nio.file.Files.createSymbolicLink(File(l, "link.txt").toPath(), File(l, "real.txt").toPath()); true } catch (e: Exception) { false }
    if (linked) check("tautan simbolik dilewati", paths(ProjectCommit.commit(l, "L", NOW).entry, 'A') == listOf("real.txt"))

    // --- indeks rusak
    val c = proj()
    put(c, "k.txt", "k\n", OLD)
    ProjectCommit.commit(c, "K", NOW)
    File(c, ".molina/index.tsv").appendText("sampah tanpa tab\n" + "zz\t1\t2\tx\n" + "\n")
    check("baris indeks rusak dilewati, indeks tetap terbaca", ProjectCommit.readIndex(c)!!.keys == setOf("k.txt"))
    File(c, ".molina/index.tsv").writeText("rusak total\n")
    check("indeks rusak total: commit tetap jalan (berkas dianggap baru)", ProjectCommit.commit(c, "K2", NOW + 1000).status == ProjectCommit.Status.COMMITTED)

    // --- terlalu besar
    val b = proj()
    for (i in 1..5) put(b, "f$i.txt", "$i\n", OLD)
    check("melebihi batas berkas: TOO_BIG dan tidak mencatat apa pun", ProjectCommit.commit(b, "B", NOW, maxFiles = 3).status == ProjectCommit.Status.TOO_BIG && ProjectLog.read(b).isEmpty() && !File(b, ".molina/index.tsv").exists())

    // --- banyak berkas: dibatasi per entri
    val m = proj()
    for (i in 1..(ProjectLog.MAX_FILES_PER_ENTRY + 30)) put(m, "d/f$i.txt", "$i\n", OLD)
    val rm = ProjectCommit.commit(m, "Banyak", NOW)
    check("entri dibatasi MAX_FILES_PER_ENTRY, sisanya di omitted", rm.entry!!.files.size == ProjectLog.MAX_FILES_PER_ENTRY && rm.entry!!.omitted == 30)
    check("indeks tetap memuat semua berkas", ProjectCommit.readIndex(m)!!.size == ProjectLog.MAX_FILES_PER_ENTRY + 30)

    // --- pesan
    val p = proj()
    put(p, "x.txt", "x\n", OLD)
    check("pesan kosong: pesan bawaan", ProjectCommit.commit(p, "   ", NOW).entry!!.message == ProjectCommit.DEFAULT_MESSAGE)
    put(p, "y.txt", "y\n", OLD)
    val longMsg = ProjectCommit.commit(p, "z".repeat(500) + "\nbaris dua", NOW + 1000).entry!!.message
    check("pesan panjang dipotong dan satu baris", longMsg.length == ProjectLog.MAX_MESSAGE && !longMsg.contains('\n'))

    // --- diff murni
    val sTmp = ProjectCommit.Stat(1, 1, "a".repeat(40))
    check("diff: prev null -> semua A", ProjectCommit.diff(null, mapOf("a" to sTmp, "b" to sTmp)).map { it.kind } == listOf('A', 'A'))

    // --- gagal tulis indeks
    val bad = proj()
    put(bad, "z.txt", "z\n", OLD)
    put(bad, ".molina/index.tsv/isi.txt", "x") // indeks berupa folder TIDAK kosong: tidak bisa ditimpa
    check("indeks tak bisa ditulis: FAILED", ProjectCommit.commit(bad, "Z", NOW).status == ProjectCommit.Status.FAILED)

    // --- skrip dan commit berdampingan
    val q = proj()
    put(q, "s.py", "1\n", OLD)
    ProjectLog.append(q, ProjectLog.note(NOW - 500, ProjectLog.SRC_SCRIPT, "Skrip: s.py"))
    ProjectCommit.commit(q, "Setelah skrip", NOW)
    check("catatan skrip dan commit hidup berdampingan di log", ProjectLog.read(q).map { it.source } == listOf(ProjectCommit.SRC, ProjectLog.SRC_SCRIPT))

    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails != 0) kotlin.system.exitProcess(1)
}
