import com.molina.code.guard.ProjectCommit
import com.molina.code.guard.ProjectLog
import com.molina.code.guard.ProjectMarker
import com.molina.code.guard.ProjectRestore
import com.molina.code.guard.ProjectStore
import java.io.File

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private const val OLD = 1_600_000_000_000L
private const val T1 = 1_700_000_100_000L
private const val T2 = 1_700_000_200_000L
private const val T3 = 1_700_000_300_000L

private fun proj(): File = java.nio.file.Files.createTempDirectory("pr").toFile().also { ProjectMarker.create(it, 1_700_000_000_000L) }
private fun put(root: File, rel: String, text: String, mtime: Long = OLD): File {
    val f = File(root, rel)
    f.parentFile.mkdirs()
    f.writeText(text)
    f.setLastModified(mtime)
    return f
}
private fun kinds(p: ProjectRestore.Plan) = p.items.associate { it.path to it.kind }

// [CODE-RESTORE] Uji pemulihan seluruh proyek: rencana, commit pengaman, hapus/timpa/buat, berkas dilewati, keamanan
fun main() {
    // --- skenario utama
    val d = proj()
    put(d, "a.txt", "1\n"); put(d, "b.txt", "b\n"); put(d, "src/c.txt", "c\n")
    ProjectCommit.commit(d, "satu", T1)
    put(d, "a.txt", "2\n", OLD + 1000); File(d, "b.txt").delete(); put(d, "d.txt", "d\n")
    ProjectCommit.commit(d, "dua", T2)
    put(d, "a.txt", "3\n", OLD + 2000); put(d, "e.txt", "e\n") // belum di-commit
    val plan = ProjectRestore.plan(d, T1)!!
    val k = kinds(plan)
    check("plan: a ditimpa, b dibuat, d dan e dihapus, c tak berubah", k == mapOf("a.txt" to 'M', "b.txt" to 'A', "d.txt" to 'D', "e.txt" to 'D'))
    check("plan: hitungan", plan.overwritten == 1 && plan.created == 1 && plan.deleted == 2 && plan.skipped.isEmpty())
    check("plan: tidak mengubah apa pun", File(d, "a.txt").readText() == "3\n" && File(d, "e.txt").exists() && !File(d, "b.txt").exists())
    val logBefore = ProjectLog.read(d).size
    val r = ProjectRestore.run(d, T1, T3)
    check("run: OK dengan hitungan benar", r.status == ProjectRestore.Status.OK && r.overwritten == 1 && r.created == 1 && r.deleted == 2 && r.failed == 0 && r.skipped == 0)
    check("run: isi sama dengan commit target", File(d, "a.txt").readText() == "1\n" && File(d, "b.txt").readText() == "b\n" && File(d, "src/c.txt").readText() == "c\n")
    check("run: berkas tambahan dihapus", !File(d, "d.txt").exists() && !File(d, "e.txt").exists())
    val logs = ProjectLog.read(d)
    check("run: dua entri baru (commit pengaman + pulih)", logs.size == logBefore + 2)
    val safety = logs.firstOrNull { it.source == ProjectCommit.SRC && it.message.startsWith(ProjectRestore.SAFETY_PREFIX) }
    check("run: commit pengaman tercatat dan punya manifest", safety != null && ProjectStore.hasSnapshot(d, safety.time))
    val rest = logs.firstOrNull { it.source == ProjectStore.SRC_RESTORE && it.message.startsWith(ProjectRestore.LOG_PREFIX) }
    check("run: entri pulih mencatat 4 perubahan", rest != null && rest.fileCount == 4)
    check("keadaan sebelum pemulihan bisa dikembalikan dari commit pengaman",
        ProjectStore.restore(d, safety!!.time, "a.txt") == ProjectStore.RestoreStatus.OK && File(d, "a.txt").readText() == "3\n" &&
            ProjectStore.restore(d, safety.time, "e.txt") == ProjectStore.RestoreStatus.OK && File(d, "e.txt").readText() == "e\n" &&
            ProjectStore.restore(d, safety.time, "d.txt") == ProjectStore.RestoreStatus.OK)
    check("log dan indeks .molina utuh", ProjectLog.read(d).isNotEmpty() && File(d, ".molina/index.tsv").isFile)

    // --- tanpa perubahan
    val same = proj()
    put(same, "a.txt", "x\n"); ProjectCommit.commit(same, "1", T1)
    val ps = ProjectRestore.plan(same, T1)!!
    check("plan: proyek sudah sama -> kosong", ps.isEmpty && ps.skipped.isEmpty())
    val rs = ProjectRestore.run(same, T1, T2)
    check("run: sama -> OK tanpa perubahan dan tanpa commit pengaman baru", rs.status == ProjectRestore.Status.OK && rs.created + rs.overwritten + rs.deleted == 0 &&
        ProjectStore.snapshotTimes(same) == setOf(T1))

    // --- target tidak ada
    val nl = ProjectLog.read(d).size
    check("plan: commit tanpa manifest -> null", ProjectRestore.plan(d, 12345L) == null)
    check("run: commit tanpa manifest -> NO_SNAPSHOT, log tidak berubah", ProjectRestore.run(d, 12345L, T3).status == ProjectRestore.Status.NO_SNAPSHOT && ProjectLog.read(d).size == nl)
    val plain = java.nio.file.Files.createTempDirectory("plain").toFile()
    check("run: bukan proyek .molina -> DISABLED", ProjectRestore.run(plain, T1, T2).status == ProjectRestore.Status.DISABLED && ProjectRestore.plan(plain, T1) == null)

    // --- berkas besar dilewati, tidak pernah ditimpa/dihapus tanpa cadangan
    val big = proj()
    val x = "x".repeat(ProjectStore.MAX_BLOB_FILE + 100)
    put(big, "besar.txt", x); put(big, "kecil.txt", "k1\n")
    ProjectCommit.commit(big, "b", T1)
    put(big, "besar.txt", "y".repeat(ProjectStore.MAX_BLOB_FILE + 100), OLD + 5000) // isi lain, tidak tersimpan di T1
    put(big, "kecil.txt", "k2\n", OLD + 5000)
    put(big, "huge.bin", "h".repeat(ProjectStore.MAX_BLOB_FILE + 50)) // baru dan besar: tidak bisa dicadangkan
    val pb = ProjectRestore.plan(big, T1)!!
    check("plan: besar.txt dilewati (isi target tidak tersimpan)", pb.skipped.any { it.path == "besar.txt" && it.reason == ProjectRestore.Skip.NO_BLOB })
    check("plan: huge.bin dilewati (tak bisa dicadangkan)", pb.skipped.any { it.path == "huge.bin" && it.reason == ProjectRestore.Skip.NO_BACKUP })
    check("plan: kecil.txt tetap ditimpa", kinds(pb)["kecil.txt"] == 'M')
    val rb = ProjectRestore.run(big, T1, T2)
    check("run: berkas besar utuh, kecil dipulihkan", rb.status == ProjectRestore.Status.OK && rb.skipped == 2 &&
        File(big, "huge.bin").exists() && File(big, "besar.txt").readText().startsWith("y") && File(big, "kecil.txt").readText() == "k1\n")

    // --- konflik berkas/folder
    val cf = proj()
    put(cf, "x/y.txt", "y\n"); ProjectCommit.commit(cf, "1", T1)
    File(cf, "x").deleteRecursively(); put(cf, "x", "berkas\n", OLD + 3000)
    val rc = ProjectRestore.run(cf, T1, T2)
    check("konflik berkas vs folder: x dihapus lalu x/y.txt dibuat", rc.status == ProjectRestore.Status.OK && rc.failed == 0 && File(cf, "x/y.txt").readText() == "y\n")

    // --- manifest berbahaya
    val ev = proj()
    val outside = java.nio.file.Files.createTempDirectory("out").toFile()
    val data = "jahat\n".toByteArray(); val sha = ProjectStore.sha1Hex(data)
    ProjectStore.put(ev, sha, data, 1_000_000L)
    File(ev, ".molina/commits").mkdirs()
    File(ev, ".molina/commits/7.tsv").writeText("#molina-commit 1\n$sha\t6\t../${outside.name}/pwn.txt\n$sha\t6\t.molina/log.jsonl\n$sha\t6\tok.txt\n")
    val pe = ProjectRestore.plan(ev, 7L)!!
    check("manifest berbahaya: path tak aman dilewati, ok.txt dibuat", pe.skipped.count { it.reason == ProjectRestore.Skip.BAD_PATH } == 2 && kinds(pe) == mapOf("ok.txt" to 'A'))
    val re = ProjectRestore.run(ev, 7L, T2)
    check("manifest berbahaya: tidak menulis di luar proyek", re.status == ProjectRestore.Status.OK && !File(outside, "pwn.txt").exists() && File(ev, "ok.txt").readText() == "jahat\n")

    // --- symlink pada folder induk tidak diikuti
    val sl = proj()
    put(sl, "dir/f.txt", "asli\n"); ProjectCommit.commit(sl, "1", T1)
    val victim = java.nio.file.Files.createTempDirectory("victim").toFile()
    File(sl, "dir").deleteRecursively()
    java.nio.file.Files.createSymbolicLink(File(sl, "dir").toPath(), victim.toPath())
    ProjectRestore.run(sl, T1, T2)
    check("symlink folder induk: tidak menulis ke target tautan", victim.listFiles()!!.isEmpty())

    // --- manifest sumber dilindungi dari gc walau sudah batas 30
    val g = proj()
    var t = T1
    val times = ArrayList<Long>()
    for (i in 1..ProjectStore.MAX_SNAPSHOTS) {
        put(g, "f.txt", "versi $i\n", OLD + i * 1000L)
        ProjectCommit.commit(g, "c$i", t); times.add(t); t += 10_000
    }
    put(g, "f.txt", "kotor\n", OLD + 99_000L)
    val rg = ProjectRestore.run(g, times.first(), t)
    check("commit tertua dipulihkan walau penuh 30 manifest", rg.status == ProjectRestore.Status.OK && File(g, "f.txt").readText() == "versi 1\n")
    check("manifest target tidak dibuang oleh commit pengaman", times.first() in ProjectStore.snapshotTimes(g))

    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails > 0) System.exit(1)
}
