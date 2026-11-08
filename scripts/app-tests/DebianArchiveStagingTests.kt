package com.molina.studio.cinema

import com.molina.core.common.SevenZipCommand
import java.io.File
import java.io.IOException
import java.nio.file.Files

private var fails = 0
private fun check(name: String, ok: Boolean) { if (ok) println("ok   $name") else { fails++; println("GAGAL $name") } }
private fun throwsIo(block: () -> Unit): Boolean = try { block(); false } catch (e: IOException) { true }

private fun tmp(): File = Files.createTempDirectory("molina-stage-test").toFile()
private fun File.put(rel: String, text: String = "x"): File { val f = File(this, rel); f.parentFile.mkdirs(); f.writeText(text); return f }

fun main() {
    // ---- DebianFormats
    fun d(n: String) = DebianFormats.detect(n)
    check("rar", d("a.rar")?.label == "RAR" && d("a.rar")!!.firstVolume && d("a.rar")!!.stem == "a")
    check("RAR huruf besar", d("A.RAR")?.label == "RAR")
    check("iso/cab/wim/deb/rpm/dmg", listOf("a.iso", "b.cab", "c.wim", "d.deb", "e.rpm", "f.dmg", "g.cpio", "h.arj", "i.lzh").all { d(it) != null })
    check("part1.rar pertama", d("x.part1.rar")?.firstVolume == true && d("x.part01.rar")?.firstVolume == true && d("x.part1.rar")!!.stem == "x")
    check("part2.rar bukan pertama", d("x.part2.rar")?.firstVolume == false)
    check("7z.001 pertama, .002 bukan", d("x.7z.001")?.firstVolume == true && d("x.7z.002")?.firstVolume == false && d("x.7z.001")!!.label == "7Z")
    check("zip.001", d("x.zip.001")?.label == "ZIP")
    check("zip/7z/tar bukan urusan Debian", d("a.zip") == null && d("a.7z") == null && d("a.tar.gz") == null)
    check("stem kosong ditolak", d(".rar") == null)
    check("folderName rar", DebianFormats.folderName("Film.part1.rar") == "Film" && DebianFormats.folderName("a.iso") == "a")

    // ---- create / sweepStale
    val dest = tmp()
    val st = DebianArchiveStaging.create(dest, 42L)
    check("create: dir tersembunyi di dest", st.isDirectory && st.parentFile == dest && st.name.startsWith(DebianArchiveStaging.PREFIX))
    check("create: nonce sama ditolak", throwsIo { DebianArchiveStaging.create(dest, 42L) })
    check("create: parent tak ada ditolak", throwsIo { DebianArchiveStaging.create(File(dest, "nope")) })
    check("sweep: baru tidak dibuang", DebianArchiveStaging.sweepStale(dest) == 0 && st.exists())
    st.put("a.txt")
    check("sweep: usang dibuang", DebianArchiveStaging.sweepStale(dest, System.currentTimeMillis() + DebianArchiveStaging.STALE_MS + 1000) == 1 && !st.exists())
    val other = dest.put(".molina-stagex/keep.txt"); // bukan awalan persis? awalan '.molina-stage' + 'x' tetap berawalan -> hanya dir
    check("sweep: berkas biasa tak disentuh", File(dest, "plain.txt").also { it.writeText("p") }.exists() && DebianArchiveStaging.sweepStale(dest, Long.MAX_VALUE / 2) >= 0 && File(dest, "plain.txt").exists())

    // ---- audit
    val a = tmp()
    a.put("f1.txt", "12345"); a.put("sub/f2.txt", "123"); File(a, "empty").mkdir()
    val au = DebianArchiveStaging.audit(a)
    check("audit: hitung", au.files == 2 && au.dirs == 2 && au.bytes == 8L && au.removed == 0)
    val victim = tmp(); val vf = victim.put("secret.txt", "jangan hapus")
    try {
        Files.createSymbolicLink(File(a, "lnk").toPath(), victim.toPath())
        Files.createSymbolicLink(File(a, "lnkfile").toPath(), vf.toPath())
        val au2 = DebianArchiveStaging.audit(a)
        check("audit: tautan dihapus, target utuh", au2.removed == 2 && !File(a, "lnk").exists() && vf.readText() == "jangan hapus")
        // deleteTree tidak mengikuti tautan
        Files.createSymbolicLink(File(a, "lnk2").toPath(), victim.toPath())
        DebianArchiveStaging.deleteTree(a)
        check("deleteTree: tidak mengikuti tautan", !a.exists() && vf.exists() && vf.readText() == "jangan hapus")
    } catch (e: UnsupportedOperationException) { println("lewati: symlink tak didukung") }
    check("audit: stage berupa tautan ditolak", run {
        val real = tmp(); val link = File(tmp(), "ln"); Files.createSymbolicLink(link.toPath(), real.toPath())
        throwsIo { DebianArchiveStaging.audit(link) }
    })

    // ---- commit
    fun fresh(): Pair<File, File> { val dd = tmp(); return dd to DebianArchiveStaging.create(dd) }

    run {
        val (dd, s) = fresh()
        s.put("a.txt", "A"); s.put("dir/b.txt", "B"); s.put("dir/deep/c.txt", "C")
        val r = DebianArchiveStaging.commit(s, dd, null) { false }
        check("commit HERE: berkas+folder", File(dd, "a.txt").readText() == "A" && File(dd, "dir/deep/c.txt").readText() == "C" && r.files == 3 && r.renamed == 0 && r.base == dd.canonicalFile)
    }
    run {
        val (dd, s) = fresh()
        dd.put("a.txt", "LAMA"); dd.put("dir/b.txt", "LAMA-B")
        s.put("a.txt", "BARU"); s.put("dir/b.txt", "BARU-B"); s.put("dir/n.txt", "N")
        val r = DebianArchiveStaging.commit(s, dd, null) { false }
        check("commit: tidak menimpa", File(dd, "a.txt").readText() == "LAMA" && File(dd, "a (1).txt").readText() == "BARU")
        check("commit: folder digabung, berkas bentrok diganti nama", File(dd, "dir/b.txt").readText() == "LAMA-B" && File(dd, "dir/b (1).txt").readText() == "BARU-B" && File(dd, "dir/n.txt").readText() == "N")
        check("commit: hitung renamed/files", r.renamed == 2 && r.files == 3)
    }
    run {
        val (dd, s) = fresh()
        dd.put("Film/lama.txt")
        s.put("x.txt", "X")
        val r = DebianArchiveStaging.commit(s, dd, "Film") { false }
        check("commit NAMED: nama unik", r.base.name == "Film (1)" && File(dd, "Film (1)/x.txt").readText() == "X" && File(dd, "Film/lama.txt").exists())
    }
    run {
        val (dd, s) = fresh()
        s.put("a.txt"); s.put("b.txt"); s.put("c.txt")
        var n = 0
        val ex = try { DebianArchiveStaging.commit(s, dd, "Out") { ++n > 2 }; null } catch (e: IOException) { e }
        check("commit batal: rollback total", ex?.message == "Dibatalkan" && !File(dd, "Out").exists() && File(dd, "a.txt").exists().not())
    }
    run {
        val (dd, s) = fresh()
        dd.put("keep.txt", "K"); s.put("a.txt"); s.put("b.txt")
        var n = 0
        try { DebianArchiveStaging.commit(s, dd, null) { ++n > 1 } } catch (e: IOException) { }
        check("commit batal HERE: hanya yang dibuat dibatalkan", File(dd, "keep.txt").readText() == "K" && !File(dd, "a.txt").exists() && !File(dd, "b.txt").exists())
    }
    check("commit: stage bukan anak dest ditolak", run { val dd = tmp(); val s = tmp(); throwsIo { DebianArchiveStaging.commit(s, dd, null) { false } } })

    // ---- roomError
    val rd = tmp()
    check("roomError: wajar OK", DebianArchiveStaging.roomError(rd, 1024) == null)
    check("roomError: terlalu besar", DebianArchiveStaging.roomError(rd, Long.MAX_VALUE / 4) != null)

    // ---- [ARCHIVE5] ArchiveRoute: mesin bawaan selalu didahulukan, Debian hanya untuk sisanya
    val nativeNames = listOf("a.zip", "a.7z", "a.tar", "a.tar.gz", "a.tgz", "a.tar.bz2", "a.tar.xz", "a.txz", "a.gz", "a.bz2", "a.xz", "A.ZIP", "B.7Z")
    check("route: format bawaan TIDAK lewat Debian", nativeNames.all { ArchiveRoute.supports(it) && !ArchiveRoute.viaDebian(it) })
    val debNames = listOf("a.rar", "a.iso", "a.cab", "a.wim", "a.deb", "a.rpm", "a.dmg", "x.7z.001", "x.zip.001", "x.part1.rar", "x.part2.rar")
    check("route: format Debian lewat Debian", debNames.all { ArchiveRoute.supports(it) && ArchiveRoute.viaDebian(it) })
    check("route: bukan arsip", listOf("a.txt", "a.mp4", "a.kt", "a", "a.001", ".rar", "a.7z.01").none { ArchiveRoute.supports(it) })
    check("route: label", ArchiveRoute.label("a.tar.gz") == "TAR.GZ" && ArchiveRoute.label("a.rar") == "RAR" && ArchiveRoute.label("x.7z.001") == "7Z" && ArchiveRoute.label("a.txt") == null)
    check("route: folderName", ArchiveRoute.folderName("Film.part1.rar") == "Film" && ArchiveRoute.folderName("a.tar.gz") == "a" && ArchiveRoute.folderName("notes.txt.gz") == "notes" && ArchiveRoute.folderName("d.iso") == "d")
    check("route: canPassword", ArchiveRoute.canPassword("a.zip") && ArchiveRoute.canPassword("a.7z") && !ArchiveRoute.canPassword("a.tar.gz") && ArchiveRoute.canPassword("a.rar") && !ArchiveRoute.canPassword("a.txt"))
    check("route: tumpang tindih tidak ada (tak satu pun nama bawaan dikenal Debian)", nativeNames.none { DebianFormats.supports(it) })


    // ---- [ARCHIVE6] rute kompres + model format
    fun o(fmt: CompressFormat, pw: String?) = CompressOptions(fmt, 5, "x", pw)
    check("route6: 7z + sandi -> Debian", ArchiveRoute.compressViaDebian(o(CompressFormat.SEVEN_Z, "abcd")))
    check("route6: 7z tanpa sandi / sandi kosong -> bawaan", !ArchiveRoute.compressViaDebian(o(CompressFormat.SEVEN_Z, null)) && !ArchiveRoute.compressViaDebian(o(CompressFormat.SEVEN_Z, "")))
    check("route6: ZIP AES dan tar tetap bawaan", CompressFormat.values().filter { it != CompressFormat.SEVEN_Z }.none { ArchiveRoute.compressViaDebian(o(it, "abcd")) })
    check("model6: hanya 7z punya enkripsi nama; 7z dan ZIP punya sandi", CompressFormat.values().filter { it.hasNameEncryption } == listOf(CompressFormat.SEVEN_Z) &&
        CompressFormat.SEVEN_Z.hasPassword && CompressFormat.ZIP.hasPassword && !CompressFormat.TAR_GZ.hasPassword)
    check("model6: CompressOptions.encryptNames bawaan false", !o(CompressFormat.SEVEN_Z, "abcd").encryptNames && CompressOptions(CompressFormat.SEVEN_Z, 5, "x", "abcd", true).encryptNames)

    // ---- [ARCHIVE6] commitFile
    run {
        val dd = tmp(); val stg = DebianArchiveStaging.create(dd, 7L)
        stg.put("archive.7z", "ISI")
        val made = DebianArchiveStaging.commitFile(stg, "archive.7z", dd, "Foto", ".7z") { false }
        check("commitFile: nama akhir dan isi", made.name == "Foto.7z" && made.readText() == "ISI" && !File(stg, "archive.7z").exists())
    }
    run {
        val dd = tmp(); dd.put("Foto.7z", "LAMA"); dd.put("Foto (1).7z", "LAMA1")
        val stg = DebianArchiveStaging.create(dd, 8L); stg.put("archive.7z", "BARU")
        val made = DebianArchiveStaging.commitFile(stg, "archive.7z", dd, "Foto", ".7z") { false }
        check("commitFile: bentrok -> (2), berkas lama TIDAK tertimpa", made.name == "Foto (2).7z" && made.readText() == "BARU" &&
            File(dd, "Foto.7z").readText() == "LAMA" && File(dd, "Foto (1).7z").readText() == "LAMA1")
    }
    run {
        val dd = tmp(); val stg = DebianArchiveStaging.create(dd, 9L); stg.put("archive.7z", "X")
        check("commitFile: batal -> galat dan tujuan tak berubah", throwsIo { DebianArchiveStaging.commitFile(stg, "archive.7z", dd, "A", ".7z") { true } } && !File(dd, "A.7z").exists() && File(stg, "archive.7z").exists())
    }
    run {
        val dd = tmp(); val stg = DebianArchiveStaging.create(dd, 10L)
        check("commitFile: hasil tidak ada ditolak", throwsIo { DebianArchiveStaging.commitFile(stg, "archive.7z", dd, "A", ".7z") { false } })
        stg.put("archive.7z", "")
        check("commitFile: hasil kosong ditolak", throwsIo { DebianArchiveStaging.commitFile(stg, "archive.7z", dd, "A", ".7z") { false } })
        File(stg, "archive.7z").delete()
        Files.createSymbolicLink(File(stg, "archive.7z").toPath(), File("/etc/hostname").toPath())
        check("commitFile: tautan sebagai hasil ditolak (tidak diikuti)", throwsIo { DebianArchiveStaging.commitFile(stg, "archive.7z", dd, "A", ".7z") { false } } && !File(dd, "A.7z").exists())
        val lain = tmp(); val s2 = tmp(); s2.put("archive.7z", "x")
        check("commitFile: stage bukan anak dest ditolak", throwsIo { DebianArchiveStaging.commitFile(s2, "archive.7z", lain, "A", ".7z") { false } })
    }

    // ---- [ARCHIVE6] symlinkExcludes
    run {
        val w = tmp()
        w.put("foto/1.jpg"); w.put("foto/sub/2.jpg"); w.put("catatan.txt"); w.put("kosong/.keep")
        Files.createSymbolicLink(File(w, "foto/ln-dir").toPath(), File("/etc").toPath())
        Files.createSymbolicLink(File(w, "foto/sub/ln-file").toPath(), File("/etc/hostname").toPath())
        Files.createSymbolicLink(File(w, "foto/ln-rusak").toPath(), File("/tidak/ada/sama-sekali").toPath())
        val ex = DebianArchiveStaging.symlinkExcludes(listOf(File(w, "foto"), File(w, "catatan.txt"), File(w, "kosong")))
        check("excludes: semua tautan (folder, file, rusak) ditemukan, jalur relatif ke akar arsip", ex.toSet() == setOf("foto/ln-dir", "foto/sub/ln-file", "foto/ln-rusak") && ex.size == 3)
        check("excludes: tidak mengikuti tautan folder (isi /etc tidak ditelusuri)", ex.none { it.startsWith("foto/ln-dir/") })
        check("excludes: tanpa tautan -> kosong", DebianArchiveStaging.symlinkExcludes(listOf(File(w, "kosong"), File(w, "catatan.txt"))).isEmpty())
        check("excludes: melebihi batas -> galat", throwsIo { DebianArchiveStaging.symlinkExcludes(listOf(File(w, "foto")), 2) })
        check("excludes: dibatalkan -> galat", throwsIo { DebianArchiveStaging.symlinkExcludes(listOf(File(w, "foto"))) { true } })
    }

    // ---- [ARCHIVE6] e2e dengan 7z ASLI: kode staging produksi + createArgs + commitFile (jalur host = jalur guest di sini)
    val bin = System.getenv("SEVENZIP_BIN")?.takeIf { it.isNotBlank() }
    if (bin == null) println("lewati e2e6: SEVENZIP_BIN tidak diset") else {
        fun sz(args: List<String>, pw: String?): Pair<Int, String> {
            val pb = ProcessBuilder(listOf(bin) + args).redirectErrorStream(true)
            pb.environment()["LANG"] = "C.UTF-8"
            val pr = pb.start()
            SevenZipCommand.stdinFor(pw)?.let { pr.outputStream.write(it) }
            try { pr.outputStream.close() } catch (e: Exception) { }
            val out = pr.inputStream.readBytes().toString(Charsets.UTF_8)
            return pr.waitFor() to out
        }
        for (enc in listOf(true, false)) {
            val w = tmp()
            w.put("A/foto/1.jpg", "uno"); w.put("A/foto/sub/2.jpg", "dos"); w.put("A/catatan -x.txt", "cat"); w.put("B/lain.txt", "lain")
            Files.createSymbolicLink(File(w, "A/foto/ln-dir").toPath(), File("/etc").toPath())
            Files.createSymbolicLink(File(w, "A/foto/sub/ln-file").toPath(), File("/etc/hostname").toPath())
            val out = File(w, "out").also { it.mkdirs() }
            out.put("hasil.7z", "ADA") // berkas lama TIDAK boleh tertimpa
            val srcs = listOf(File(w, "A/foto"), File(w, "A/catatan -x.txt"), File(w, "B/lain.txt"))
            val ex = DebianArchiveStaging.symlinkExcludes(srcs)
            val stg = DebianArchiveStaging.create(out)
            val (rc, _) = sz(SevenZipCommand.createArgs(File(stg, SevenZipCommand.OUT_FILE).path, srcs.map { it.path }, 1, true, enc, ex), "rahasia")
            val made = DebianArchiveStaging.commitFile(stg, SevenZipCommand.OUT_FILE, out, "hasil", ".7z") { false }
            DebianArchiveStaging.deleteTree(stg)
            val tag = "e2e6[nama=${if (enc) "ya" else "tidak"}]"
            check("$tag: rc=0, hasil 'hasil (1).7z', berkas lama utuh, staging bersih", rc == 0 && made.name == "hasil (1).7z" && File(out, "hasil.7z").readText() == "ADA" &&
                out.listFiles()!!.map { it.name }.toSet() == setOf("hasil.7z", "hasil (1).7z"))
            val want = setOf("foto", "foto/sub", "foto/1.jpg", "foto/sub/2.jpg", "catatan -x.txt", "lain.txt")
            val (rl, ol) = sz(SevenZipCommand.listArgs(made.path), if (enc) "rahasia" else null)
            check("$tag: daftar (dengan sandi bila perlu) = item pilihan, tautan dilewati", rl == 0 && SevenZipCommand.parseList(ol).map { it.path }.toSet() == want)
            val (rn, on) = sz(SevenZipCommand.listArgs(made.path), null)
            if (enc) check("$tag: tanpa sandi nama TIDAK terlihat", SevenZipCommand.parseList(on).isEmpty() && SevenZipCommand.classify(rn, on, false) == SevenZipCommand.Failure.NEED_PASSWORD)
            else check("$tag: tanpa sandi nama terlihat tetapi isi terkunci", rn == 0 && SevenZipCommand.parseList(on).filter { !it.isDir }.all { it.encrypted })
            val xo = File(w, "ekstrak").also { it.mkdirs() }
            val (rx, _) = sz(SevenZipCommand.extractArgs(made.path, xo.path), "rahasia")
            check("$tag: ekstrak bersih rc=0 (tanpa 'Dangerous link'), isi utuh", rx == 0 && File(xo, "foto/sub/2.jpg").readText() == "dos" && File(xo, "lain.txt").readText() == "lain" && File(xo, "catatan -x.txt").readText() == "cat")
        }
    }

    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails > 0) System.exit(1)
}
