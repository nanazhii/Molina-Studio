import com.molina.studio.terminal.ArchiveInfo
import com.molina.studio.terminal.BackupCancelled
import com.molina.studio.terminal.BackupControl
import com.molina.studio.terminal.BackupPhase
import com.molina.studio.terminal.NioPosix
import com.molina.studio.terminal.RootfsBackup
import com.molina.studio.terminal.RootfsBackupException
import com.molina.studio.terminal.SafeTree
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.system.exitProcess

private var passed = 0
private var failed = 0
private fun check(name: String, ok: Boolean) {
    if (ok) passed++ else { failed++; println("GAGAL: $name") }
}
private inline fun <reified T : Throwable> throwsA(name: String, block: () -> Unit) {
    try { block(); check("$name (tidak melempar)", false) }
    catch (e: Throwable) { check("$name (${e.javaClass.simpleName}: ${e.message})", e is T) }
}
private fun sym(link: File, target: String) = Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(target))
private fun isLink(f: File) = Files.isSymbolicLink(f.toPath())
private fun mode(f: File) = NioPosix.mode(f)

/** Rootfs mini: dua folder inti, mode beragam, symlink (relatif, absolut guest, ke luar), nama unik, jalur panjang. */
private fun makeRootfs(root: File, outside: File) {
    File(root, "etc").mkdirs(); File(root, "usr/bin").mkdirs(); File(root, "root").mkdirs()
    File(root, "etc/os-release").writeText("ID=debian\n")
    File(root, "usr/bin/tool").apply { writeText("#!/bin/sh\necho hi\n"); NioPosix.chmod(this, 0b111_101_101) }
    File(root, "root/rahasia").apply { writeText("x"); NioPosix.chmod(this, 0b110_000_000) }
    File(root, "root/sayang😍.txt").writeText("unicode")
    File(root, "root/kosong").mkdirs()
    val deep = File(root, "root/" + "dir_panjang_".repeat(12) + "/" + "sub_".repeat(15)).apply { mkdirs() }
    File(deep, "berkas_dengan_nama_yang_cukup_panjang_untuk_pax.txt").writeText("panjang")
    sym(File(root, "bin"), "usr/bin")                 // relatif
    sym(File(root, "etc/alt"), "/usr/bin/tool")       // absolut (guest)
    sym(File(root, "root/ws"), outside.absolutePath)  // menunjuk ke direktori NYATA di luar rootfs
    // isi volatil yang tidak boleh ikut
    File(root, "proc").mkdirs(); File(root, "proc/x").writeText("v")
    File(root, "tmp").mkdirs(); File(root, "tmp/sampah").writeText("v")
    File(root, "etc/molina/proc").mkdirs(); File(root, "etc/molina/proc/stat").writeText("v")
}

private fun writeTar(dest: File, block: (TarArchiveOutputStream) -> Unit) {
    TarArchiveOutputStream(GzipCompressorOutputStream(dest.outputStream())).use { t ->
        t.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX); block(t)
    }
}
private fun TarArchiveOutputStream.file(name: String, text: String, mode: Int = 0b110_100_100) {
    val e = TarArchiveEntry(name); e.size = text.length.toLong(); e.mode = mode
    putArchiveEntry(e); write(text.toByteArray()); closeArchiveEntry()
}
private fun TarArchiveOutputStream.dir(name: String) {
    val e = TarArchiveEntry("$name/"); e.mode = 0b111_101_101; putArchiveEntry(e); closeArchiveEntry()
}
private fun TarArchiveOutputStream.link(name: String, target: String) {
    val e = TarArchiveEntry(name, TarConstants.LF_SYMLINK); e.linkName = target; putArchiveEntry(e); closeArchiveEntry()
}

fun main() {
    val tmp = Files.createTempDirectory("rootfsbk").toFile()
    try {
        val outside = File(tmp, "storage").apply { mkdirs() }
        val keep = File(outside, "penting.txt").apply { writeText("data pengguna") }
        val src = File(tmp, "src/debian")
        makeRootfs(src, outside)

        // ---- SafeTree ----
        val dcopy = File(tmp, "dcopy"); File(dcopy, "a").mkdirs(); File(dcopy, "a/f").writeText("1")
        sym(File(dcopy, "a/ws"), outside.absolutePath); sym(File(dcopy, "mati"), "/tidak/ada")
        check("SafeTree.delete true", SafeTree.delete(dcopy))
        check("SafeTree: folder hilang", !dcopy.exists())
        check("SafeTree: target symlink TIDAK terhapus", keep.isFile && keep.readText() == "data pengguna")
        check("SafeTree: path tak ada = true", SafeTree.delete(File(tmp, "tidakada")))
        val ro = File(tmp, "ro/sub").apply { mkdirs() }; File(ro, "f").writeText("x"); ro.setWritable(false); ro.parentFile.setWritable(false)
        check("SafeTree: folder tanpa izin tulis", SafeTree.delete(File(tmp, "ro")))

        // ---- export ----
        val arc = File(tmp, "bk/debian.tar.gz")
        val phases = mutableSetOf<BackupPhase>()
        val r = RootfsBackup.export(src, arc, NioPosix) { ph, _ -> phases += ph }
        check("export: berkas ada, .part hilang", arc.isFile && !File(arc.path + ".part").exists())
        check("export: fase Scan+Write", BackupPhase.Scan in phases && BackupPhase.Write in phases)
        check("export: entri > 0, tanpa lewati", r.entries > 10 && r.skipped == 0)
        val info: ArchiveInfo = RootfsBackup.inspect(arc)
        check("inspect: tampak rootfs", info.looksLikeRootfs && info.entries == r.entries)
        check("inspect: byte terurai = isi berkas", info.bytes == r.bytes)

        // ---- restore ke lokasi baru ----
        val dst = File(tmp, "dst/debian").apply { parentFile.mkdirs() }
        val pr = mutableListOf<BackupPhase>()
        RootfsBackup.restore(arc, dst, NioPosix) { ph, _ -> if (ph !in pr) pr += ph }
        check("restore: urutan fase", pr == listOf(BackupPhase.Inspect, BackupPhase.Extract, BackupPhase.Swap))
        check("restore: isi berkas", File(dst, "etc/os-release").readText() == "ID=debian\n")
        check("restore: mode 755", mode(File(dst, "usr/bin/tool")) == 0b111_101_101)
        check("restore: mode 600", mode(File(dst, "root/rahasia")) == 0b110_000_000)
        check("restore: unicode", File(dst, "root/sayang😍.txt").readText() == "unicode")
        check("restore: folder kosong", File(dst, "root/kosong").isDirectory)
        check("restore: jalur panjang", File(dst, "root/" + "dir_panjang_".repeat(12) + "/" + "sub_".repeat(15) + "/berkas_dengan_nama_yang_cukup_panjang_untuk_pax.txt").readText() == "panjang")
        check("restore: symlink relatif", isLink(File(dst, "bin")) && Files.readSymbolicLink(File(dst, "bin").toPath()).toString() == "usr/bin")
        check("restore: symlink absolut guest", Files.readSymbolicLink(File(dst, "etc/alt").toPath()).toString() == "/usr/bin/tool")
        check("restore: symlink ke luar tetap tautan", isLink(File(dst, "root/ws")) && keep.isFile)
        check("restore: isi volatil tidak ikut", !File(dst, "proc/x").exists() && !File(dst, "tmp/sampah").exists() && !File(dst, "etc/molina/proc/stat").exists())
        check("restore: folder volatil tetap ada", File(dst, "proc").isDirectory && File(dst, "tmp").isDirectory)
        check("restore: tidak ada sisa .restore/.old", !File(dst.parentFile, "debian.restore").exists() && !File(dst.parentFile, "debian.old").exists())

        // ---- restore menggantikan rootfs yang ada (berisi symlink ke luar) ----
        File(dst, "etc/hanya-di-lama").writeText("lama")
        RootfsBackup.restore(arc, dst, NioPosix)
        check("ganti: berkas lama hilang", !File(dst, "etc/hanya-di-lama").exists())
        check("ganti: isi baru ada", File(dst, "etc/os-release").isFile)
        check("ganti: data di luar rootfs selamat", keep.isFile && keep.readText() == "data pengguna")
        check("ganti: tanpa .old", !File(dst.parentFile, "debian.old").exists())

        // ---- gagal: arsip rusak (terpotong) -> rootfs lama utuh ----
        val broken = File(tmp, "broken.tar.gz"); broken.writeBytes(arc.readBytes().copyOf((arc.length() / 2).toInt()))
        File(dst, "etc/penanda").writeText("masih lama")
        throwsA<RootfsBackupException>("arsip rusak ditolak") { RootfsBackup.restore(broken, dst, NioPosix) }
        check("rusak: rootfs lama utuh", File(dst, "etc/penanda").isFile)
        check("rusak: tanpa .restore", !File(dst.parentFile, "debian.restore").exists())

        // ---- gagal: bukan rootfs ----
        val notRoot = File(tmp, "bukan.tar.gz"); writeTar(notRoot) { it.file("foto.jpg", "abc"); it.file("catatan.txt", "x") }
        throwsA<RootfsBackupException>("bukan rootfs ditolak") { RootfsBackup.restore(notRoot, dst, NioPosix) }
        check("bukan rootfs: rootfs utuh", File(dst, "etc/penanda").isFile)

        // ---- gagal: ruang kosong kurang ----
        throwsA<RootfsBackupException>("ruang kurang ditolak") { RootfsBackup.restore(arc, dst, NioPosix, freeSpaceBytes = 1024) }
        check("ruang kurang: rootfs utuh, tanpa .restore", File(dst, "etc/penanda").isFile && !File(dst.parentFile, "debian.restore").exists())

        // ---- batal di tengah ekstrak ----
        val ctl = BackupControl()
        throwsA<BackupCancelled>("batal saat ekstrak") {
            RootfsBackup.restore(arc, dst, NioPosix, ctl) { ph, _ -> if (ph == BackupPhase.Extract) ctl.cancel() }
        }
        check("batal: rootfs utuh, tanpa .restore", File(dst, "etc/penanda").isFile && !File(dst.parentFile, "debian.restore").exists())
        val ctl2 = BackupControl()
        throwsA<BackupCancelled>("batal saat export") {
            RootfsBackup.export(src, File(tmp, "bk/batal.tar.gz"), NioPosix, ctl2) { _, _ -> ctl2.cancel() }
        }
        check("batal export: tanpa berkas/.part", !File(tmp, "bk/batal.tar.gz").exists() && !File(tmp, "bk/batal.tar.gz.part").exists())

        // ---- serangan: path traversal dan tulis lewat symlink ----
        val evil = File(tmp, "evil.tar.gz")
        val victim = File(tmp, "victim").apply { mkdirs() }
        writeTar(evil) {
            it.dir("etc"); it.file("etc/os-release", "ID=x\n"); it.dir("usr")
            it.file("../pwn1.txt", "jahat"); it.file("a/../../pwn2.txt", "jahat"); it.file("/abs/aman.txt", "ok")
            it.link("etc/tautan", victim.absolutePath)           // tautan ke luar
            it.file("etc/tautan/pwn3.txt", "jahat")              // tulis lewat tautan
            it.link("usr/lib", "../../..")
            it.file("usr/lib/pwn4.txt", "jahat")
        }
        val dst2 = File(tmp, "dst2/debian").apply { parentFile.mkdirs() }
        RootfsBackup.restore(evil, dst2, NioPosix)
        check("serangan: '../' tidak keluar", !File(tmp, "pwn1.txt").exists() && !File(tmp, "dst2/pwn1.txt").exists() && !File(tmp, "pwn2.txt").exists() && !File(tmp, "dst2/pwn2.txt").exists())
        check("serangan: tidak menulis lewat tautan", victim.list()!!.isEmpty() && !File(tmp, "pwn4.txt").exists())
        check("serangan: nama absolut dijinakkan", File(dst2, "abs/aman.txt").readText() == "ok")

        // ---- [BACKUP-NOKEYS] kunci tanda tangan dan kredensial tidak ikut cadangan ----
        val srcK = File(tmp, "srcK/debian")
        makeRootfs(srcK, outside)
        File(srcK, "root/molina-keys").mkdirs()
        File(srcK, "root/molina-keys/molina.p12").writeText("RAHASIA-KUNCI")
        File(srcK, "root/molina-keys/password.txt").writeText("RAHASIA-KUNCI")
        File(srcK, "root/molina.p12").writeText("RAHASIA-KUNCI")
        File(srcK, "root/molina.P12.b64").writeText("RAHASIA-KUNCI")
        File(srcK, "root/.ssh").mkdirs(); File(srcK, "root/.ssh/id_ed25519").writeText("RAHASIA-KUNCI")
        File(srcK, "root/.git-credentials").writeText("RAHASIA-KUNCI")
        File(srcK, "usr/share").mkdirs(); File(srcK, "usr/share/contoh.p12").writeText("bukan rahasia")
        val arcK = File(tmp, "bkK/debian.tar.gz")
        val rk = RootfsBackup.export(srcK, arcK, NioPosix)
        val namesK = mutableListOf<String>()
        val bodiesK = StringBuilder()
        TarArchiveInputStream(GzipCompressorInputStream(arcK.inputStream())).use { t ->
            while (true) {
                val e = t.nextEntry as? TarArchiveEntry ?: break
                namesK += e.name.trimEnd('/')
                if (e.isFile && e.size < 4096) bodiesK.append(t.readBytes().toString(Charsets.UTF_8))
            }
        }
        check("nokeys: tidak ada isi rahasia di arsip", !bodiesK.contains("RAHASIA-KUNCI"))
        check("nokeys: folder molina-keys tercatat, isinya tidak", "root/molina-keys" in namesK && namesK.none { it.startsWith("root/molina-keys/") })
        check("nokeys: keystore lepas di root/ dilewati", "root/molina.p12" !in namesK && "root/molina.P12.b64" !in namesK)
        check("nokeys: .ssh dan .git-credentials tetap dilewati", "root/.ssh/id_ed25519" !in namesK && "root/.git-credentials" !in namesK)
        check("nokeys: .p12 di luar root/ tidak disentuh", "usr/share/contoh.p12" in namesK)
        check("nokeys: hitungan dilewati = 3", rk.skipped == 3)

        // ---- export menimpa dest yang sudah ada ----
        RootfsBackup.export(src, arc, NioPosix)
        check("export ulang: tetap valid", RootfsBackup.inspect(arc).looksLikeRootfs)
    } finally {
        // rapikan, tanpa mengikuti symlink
        File(tmp, "ro").let { it.setWritable(true) }
        SafeTree.delete(tmp)
    }
    println("RootfsBackupTests: $passed lulus, $failed gagal")
    if (failed > 0) exitProcess(1)
}
