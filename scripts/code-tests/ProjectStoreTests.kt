import com.molina.code.guard.ProjectCommit
import com.molina.code.guard.ProjectLog
import com.molina.code.guard.ProjectMarker
import com.molina.code.guard.ProjectStore
import java.io.File

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun tmp(): File = java.nio.file.Files.createTempDirectory("ps").toFile()
private fun proj(): File = tmp().also { ProjectMarker.create(it, 1_700_000_000_000L) }
private fun put(root: File, rel: String, text: String, mtime: Long = OLD): File {
    val f = File(root, rel)
    f.parentFile.mkdirs()
    f.writeText(text)
    f.setLastModified(mtime)
    return f
}
private fun chg(e: ProjectLog.Entry?, p: String) = e?.files?.firstOrNull { it.path == p }

private const val OLD = 1_600_000_000_000L
private const val T1 = 1_700_000_100_000L
private const val T2 = 1_700_000_200_000L
private const val T3 = 1_700_000_300_000L

// [CODE-STORE] Uji blob store lokal, manifest per commit, hitungan +/- baris, gc, dan pemulihan per berkas
fun main() {
    // --- blob dasar
    val d0 = proj()
    val data = "halo dunia\n".toByteArray()
    val sha = ProjectStore.sha1Hex(data)
    check("put: tersimpan (>0)", ProjectStore.put(d0, sha, data, 1_000_000L) > 0)
    check("put ulang: 0 (sudah ada)", ProjectStore.put(d0, sha, data, 1_000_000L) == 0L)
    check("get: isi sama", ProjectStore.get(d0, sha)?.contentEquals(data) == true)
    check("has: ya", ProjectStore.has(d0, sha) && !ProjectStore.has(d0, "0".repeat(40)))
    check("put: sha salah bentuk ditolak", ProjectStore.put(d0, "../x", data, 1_000_000L) == -1L)
    check("put: melebihi jatah -> -2 dan tanpa berkas", ProjectStore.put(d0, ProjectStore.sha1Hex("lain".toByteArray()), "lain".toByteArray(), 1L) == -2L)
    File(d0, ".molina/objects/$sha.gz").writeBytes(byteArrayOf(1, 2, 3))
    check("get: blob rusak -> null", ProjectStore.get(d0, sha) == null)

    // --- commit pertama menyimpan isi + manifest
    val d = proj()
    put(d, "a.txt", "satu\ndua\ntiga\n")
    put(d, "src/b.kt", "fun b() {}\n")
    val bin = File(d, "img.bin").also { it.writeBytes(byteArrayOf(0, 1, 2, 3, 0)); it.setLastModified(OLD) }
    val r1 = ProjectCommit.commit(d, "Awal", T1)
    check("commit 1: COMMITTED, semua A", r1.status == ProjectCommit.Status.COMMITTED && r1.entry!!.files.size == 3 && r1.entry!!.files.all { it.kind == 'A' })
    check("commit 1: manifest ada", ProjectStore.hasSnapshot(d, T1) && ProjectStore.snapshotTimes(d) == setOf(T1))
    check("commit 1: A berteks mencatat jumlah baris (+3 dan +1)", chg(r1.entry, "a.txt")!!.added == 3 && chg(r1.entry, "src/b.kt")!!.added == 1)
    check("commit 1: biner tanpa hitungan baris", chg(r1.entry, "img.bin")!!.added == 0)
    check("commit 1: tidak ada yang tak tersimpan", r1.unsaved == 0)
    check("isi pada commit 1 terbaca", String(ProjectStore.contentAt(d, T1, "a.txt")!!) == "satu\ndua\ntiga\n")
    check("biner juga bisa dipulihkan isinya", ProjectStore.contentAt(d, T1, "img.bin")!!.contentEquals(bin.readBytes()))

    // --- commit 2: M dan D dengan +/-
    put(d, "a.txt", "satu\nDUA\ntiga\nempat\n")
    File(d, "src/b.kt").delete()
    put(d, "baru.txt", "x\ny\n")
    val r2 = ProjectCommit.commit(d, "Dua", T2)
    check("commit 2: a.txt M +2 -1", chg(r2.entry, "a.txt")!!.let { it.kind == 'M' && it.added == 2 && it.removed == 1 })
    check("commit 2: b.kt D -1", chg(r2.entry, "src/b.kt")!!.let { it.kind == 'D' && it.removed == 1 && it.added == 0 })
    check("commit 2: baru.txt A +2", chg(r2.entry, "baru.txt")!!.let { it.kind == 'A' && it.added == 2 })
    check("dua manifest", ProjectStore.snapshotTimes(d) == setOf(T1, T2))

    // --- pemulihan per berkas
    check("restore a.txt ke commit 1: OK", ProjectStore.restore(d, T1, "a.txt") == ProjectStore.RestoreStatus.OK && File(d, "a.txt").readText() == "satu\ndua\ntiga\n")
    check("restore berkas yang sudah dihapus (b.kt): OK dan folder dibuat", ProjectStore.restore(d, T1, "src/b.kt") == ProjectStore.RestoreStatus.OK && File(d, "src/b.kt").readText() == "fun b() {}\n")
    check("restore: tidak ada di commit itu", ProjectStore.restore(d, T1, "baru.txt") == ProjectStore.RestoreStatus.NOT_IN_SNAPSHOT)
    check("restore: commit tanpa manifest", ProjectStore.restore(d, 12345L, "a.txt") == ProjectStore.RestoreStatus.NO_SNAPSHOT)
    check("restore: tidak meninggalkan berkas sementara", d.walkTopDown().none { it.name.endsWith(".tmp") })
    val log0 = ProjectLog.read(d).size
    check("restoreFile: OK dan dicatat di log", ProjectCommit.restoreFile(d, T1, "a.txt", T3) == ProjectStore.RestoreStatus.OK &&
        ProjectLog.read(d).size == log0 + 1 && ProjectLog.read(d)[0].source == ProjectStore.SRC_RESTORE)
    check("restoreFile gagal: tidak mencatat log", ProjectCommit.restoreFile(d, T1, "baru.txt", T3) == ProjectStore.RestoreStatus.NOT_IN_SNAPSHOT && ProjectLog.read(d).size == log0 + 1)

    // --- keamanan path
    check("safePath: menolak .., mutlak, .molina, .git", listOf("../x", "a/../../x", "/etc/passwd", ".molina/log.jsonl", "a/.git/config", "", "a//b", "a\\b").none { ProjectStore.safePath(it) })
    check("safePath: menerima biasa", ProjectStore.safePath("src/deep/c.md") && ProjectStore.safePath("a.txt"))
    check("restore: BAD_PATH tidak menyentuh log", ProjectStore.restore(d, T1, ".molina/log.jsonl") == ProjectStore.RestoreStatus.BAD_PATH)
    // manifest yang dirusak menunjuk keluar proyek tidak boleh menulis di luar
    val evil = proj()
    val outside = tmp()
    val data2 = "jahat\n".toByteArray(); val sha2 = ProjectStore.sha1Hex(data2)
    ProjectStore.put(evil, sha2, data2, 1_000_000L)
    File(evil, ".molina/commits").mkdirs()
    File(evil, ".molina/commits/5.tsv").writeText("#molina-commit 1\n$sha2\t6\t../${outside.name}/pwn.txt\n$sha2\t6\t.molina/log.jsonl\n")
    check("restore: manifest berbahaya (..) ditolak", ProjectStore.restore(evil, 5L, "../${outside.name}/pwn.txt") == ProjectStore.RestoreStatus.BAD_PATH && !File(outside, "pwn.txt").exists())
    check("restore: manifest menunjuk .molina ditolak", ProjectStore.restore(evil, 5L, ".molina/log.jsonl") == ProjectStore.RestoreStatus.BAD_PATH)
    // tautan simbolik: tidak ditulis lewat symlink
    val sl = proj()
    put(sl, "t.txt", "asli\n")
    ProjectCommit.commit(sl, "s", T1)
    val victim = File(outside, "victim.txt").also { it.writeText("aman") }
    File(sl, "t.txt").delete()
    java.nio.file.Files.createSymbolicLink(File(sl, "t.txt").toPath(), victim.toPath())
    check("restore: target symlink ditolak, korban utuh", ProjectStore.restore(sl, T1, "t.txt") == ProjectStore.RestoreStatus.BAD_PATH && victim.readText() == "aman")

    // --- dedup antar-commit
    val dd = proj()
    put(dd, "a.txt", "sama\n"); put(dd, "b.txt", "sama\n")
    ProjectCommit.commit(dd, "x", T1)
    check("isi sama = satu blob", File(dd, ".molina/objects").listFiles()!!.count { it.name.endsWith(".gz") } == 1)

    // --- batas ukuran per berkas
    val big = proj()
    put(big, "besar.txt", "a".repeat(ProjectStore.MAX_BLOB_FILE + 10))
    put(big, "kecil.txt", "k\n")
    val rb = ProjectCommit.commit(big, "b", T1)
    check("berkas besar: tercatat tetapi tidak tersimpan (unsaved=1)", rb.status == ProjectCommit.Status.COMMITTED && rb.unsaved == 1 && rb.entry!!.files.size == 2)
    check("berkas besar: restore NO_BLOB", ProjectStore.restore(big, T1, "besar.txt") == ProjectStore.RestoreStatus.NO_BLOB)
    check("berkas kecil tetap bisa dipulihkan", ProjectStore.restore(big, T1, "kecil.txt") == ProjectStore.RestoreStatus.OK)

    // --- berkas yang sama kembali ke isi lama (blob sudah ada) tetap dihitung +/-
    val rv = proj()
    put(rv, "a.txt", "v1\n", OLD); ProjectCommit.commit(rv, "1", T1)
    put(rv, "a.txt", "v2\n", OLD + 1000); ProjectCommit.commit(rv, "2", T2)
    put(rv, "a.txt", "v1\n", OLD + 2000); val rv3 = ProjectCommit.commit(rv, "3", T3)
    check("kembali ke isi lama: M +1 -1", chg(rv3.entry, "a.txt")!!.let { it.kind == 'M' && it.added == 1 && it.removed == 1 })

    // --- gc: manifest tua dan blob yatim dibuang
    val g = proj()
    var t = T1
    for (i in 1..(ProjectStore.MAX_SNAPSHOTS + 3)) {
        put(g, "f.txt", "versi $i\n", OLD + i * 1000L)
        ProjectCommit.commit(g, "c$i", t)
        t += 10_000
    }
    val times = ProjectStore.snapshotTimes(g)
    check("gc: manifest dibatasi MAX_SNAPSHOTS", times.size == ProjectStore.MAX_SNAPSHOTS && T1 !in times && (t - 10_000) in times)
    check("gc: blob yatim dibuang (satu blob per manifest tersisa)", File(g, ".molina/objects").listFiles()!!.count { it.name.endsWith(".gz") } == ProjectStore.MAX_SNAPSHOTS)
    check("gc: isi commit terbaru masih bisa dipulihkan", ProjectStore.restore(g, t - 10_000, "f.txt") == ProjectStore.RestoreStatus.OK)

    // --- upgrade dari commit lama tanpa manifest: baseline disimpan pada commit berikutnya
    val up = proj()
    put(up, "a.txt", "lama\n")
    val idx = mapOf("a.txt" to ProjectCommit.scan(up, null)!!.getValue("a.txt"))
    ProjectCommit.writeIndex(up, idx) // meniru indeks hasil commit versi lama (tanpa blob/manifest)
    put(up, "b.txt", "baru\n")
    val ru = ProjectCommit.commit(up, "setelah upgrade", T2)
    check("upgrade: hanya b.txt tercatat A", ru.entry!!.files.size == 1 && ru.entry!!.files[0].path == "b.txt")
    check("upgrade: a.txt (tidak berubah) tetap tersimpan sebagai baseline", ProjectStore.restore(up, T2, "a.txt") == ProjectStore.RestoreStatus.OK)

    // --- tanpa perubahan: tidak ada manifest baru
    val nn = proj()
    put(nn, "a.txt", "x\n"); ProjectCommit.commit(nn, "1", T1)
    val rn = ProjectCommit.commit(nn, "2", T2)
    check("tanpa perubahan: NOTHING dan tanpa manifest baru", rn.status == ProjectCommit.Status.NOTHING && ProjectStore.snapshotTimes(nn) == setOf(T1))

    // --- dua commit dalam milidetik yang sama
    val tt = proj()
    put(tt, "a.txt", "1\n"); ProjectCommit.commit(tt, "1", T1)
    put(tt, "a.txt", "2\n", OLD + 1000); val rt = ProjectCommit.commit(tt, "2", T1)
    check("waktu bentrok: manifest tidak menimpa (waktu digeser)", rt.entry!!.time == T1 + 1 && ProjectStore.snapshotTimes(tt) == setOf(T1, T1 + 1))

    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails > 0) System.exit(1)
}
