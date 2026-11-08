package com.molina.studio.terminal

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Backup, restore, dan hapus rootfs Debian. Murni JVM (tanpa Android) agar bisa diuji; operasi yang butuh
 * stat/chmod/symlink gaya Unix lewat [RootfsPosix] (di Android: Os.*).
 *
 * Prinsip: symlink TIDAK PERNAH diikuti (symlink guest bisa menunjuk ke storage nyata di host);
 * hasil ditulis ke berkas/folder sementara lalu ditukar atomik; gagal/batal tidak menyentuh rootfs yang ada.
 */
interface RootfsPosix {
    /** Bit izin (0..0xFFF) dari lstat, tanpa mengikuti symlink. */
    fun mode(f: File): Int
    fun chmod(f: File, mode: Int)
    fun symlink(link: File, target: String)
}

class RootfsBackupException(message: String) : IOException(message)
class BackupCancelled : RuntimeException()

class BackupControl {
    @Volatile var cancelled = false
    fun cancel() { cancelled = true }
    internal fun check() { if (cancelled) throw BackupCancelled() }
}

enum class BackupPhase { Scan, Write, Inspect, Extract, Swap }

data class ExportResult(val entries: Int, val bytes: Long, val skipped: Int)
data class ArchiveInfo(val entries: Int, val bytes: Long, val looksLikeRootfs: Boolean)

/** Hapus pohon folder TANPA mengikuti symlink (File.deleteRecursively mengikutinya dan bisa menghapus data di luar). */
object SafeTree {
    /** true bila [root] sudah tidak ada setelahnya. Symlink dihapus sebagai tautan, targetnya tidak disentuh. */
    fun delete(root: File): Boolean {
        val p = root.toPath()
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return true
        try {
            Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val d = dir.toFile() // bukan symlink: walkFileTree tidak mengikuti tautan
                    d.setReadable(true); d.setWritable(true); d.setExecutable(true)
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file); return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    try { Files.deleteIfExists(dir) } catch (e: IOException) { /* dibiarkan; dicek di akhir */ }
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (e: IOException) { /* dicek di akhir */ }
        return !Files.exists(p, LinkOption.NOFOLLOW_LINKS)
    }

    /** true bila path kanonik (mengikuti symlink) [f] berada di dalam [rootCanon]. */
    fun isInside(f: File, rootCanon: String): Boolean {
        val c = f.canonicalPath
        return c == rootCanon || c.startsWith(rootCanon + File.separator)
    }
}

/** Implementasi JVM biasa (Linux). Android memakai Os.lstat/chmod/symlink di lapisan integrasi. */
object NioPosix : RootfsPosix {
    override fun mode(f: File): Int =
        (Files.getAttribute(f.toPath(), "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int) and 0xFFF
    override fun chmod(f: File, mode: Int) {
        Files.setAttribute(f.toPath(), "unix:mode", mode)
    }
    override fun symlink(link: File, target: String) {
        Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(target))
    }
}

private class ByteCounter(input: InputStream) : FilterInputStream(input) {
    @Volatile var count = 0L
    override fun read(): Int { val r = super.read(); if (r >= 0) count++; return r }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val r = super.read(b, off, len); if (r > 0) count += r; return r
    }
}

object RootfsBackup {
    /** Isi folder ini tidak ikut dicadangkan (foldernya tetap dicatat): dibuat ulang/volatil. */
    private val SKIP_CONTENTS = setOf(
        "proc", "sys", "dev", "run", "tmp", "etc/molina/proc",
        // [BACKUP-NOCRED] folder kredensial tidak ikut cadangan (cadangan ada di penyimpanan bersama)
        "root/.ssh", "root/.gnupg", "root/.config/gh",
        // [BACKUP-NOKEYS] kunci tanda tangan APK (docs/SIGNING.md) tidak boleh ikut cadangan di /sdcard
        "root/molina-keys",
    )

    /** Berkas kredensial/token: tidak dicadangkan. Login ulang setelah pemulihan. */
    private val SENSITIVE_FILES = setOf(
        "root/.git-credentials", "root/.netrc", "root/.npmrc", "root/.pypirc",
    )

    /** [BACKUP-NOKEYS] Keystore lepas di folder root (mis. molina.p12 yang lupa dihapus): hanya di bawah root/. */
    private val KEY_SUFFIXES = listOf(".p12", ".pfx", ".jks", ".keystore", ".p12.b64")

    private fun isSensitive(rel: String): Boolean =
        rel in SENSITIVE_FILES || (rel.startsWith("root/") && KEY_SUFFIXES.any { rel.lowercase().endsWith(it) })

    private const val SPACE_MARGIN = 64L * 1024 * 1024

    /**
     * Cadangkan [rootfs] ke [dest] (.tar.gz) lewat berkas .part lalu ganti nama. Sesi terminal harus berhenti dulu:
     * berkas yang berubah saat dibaca menggagalkan pencadangan (tidak ada arsip rusak yang tersisa).
     */
    fun export(
        rootfs: File, dest: File, posix: RootfsPosix,
        ctl: BackupControl = BackupControl(),
        onProgress: (BackupPhase, Int) -> Unit = { _, _ -> },
    ): ExportResult {
        if (!rootfs.isDirectory) throw RootfsBackupException("Rootfs tidak ditemukan")
        val root = rootfs.toPath()
        onProgress(BackupPhase.Scan, 0)
        val total = scanBytes(root, ctl).coerceAtLeast(1L)

        val part = File(dest.path + ".part")
        part.parentFile?.mkdirs()
        part.delete()
        var entries = 0
        var skipped = 0
        var done = 0L
        var last = -1
        try {
            TarArchiveOutputStream(GzipCompressorOutputStream(part.outputStream().buffered(256 * 1024))).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                tar.setAddPaxHeadersForNonAsciiNames(true)
                val buf = ByteArray(64 * 1024)
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        ctl.check()
                        val rel = relName(root, dir)
                        if (rel.isNotEmpty()) {
                            val e = TarArchiveEntry("$rel/")
                            fill(e, dir.toFile(), posix, 0x1ED)
                            tar.putArchiveEntry(e); tar.closeArchiveEntry(); entries++
                        }
                        return if (rel in SKIP_CONTENTS) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        ctl.check()
                        val rel = relName(root, file)
                        val f = file.toFile()
                        when {
                            isSensitive(rel) -> skipped++
                            attrs.isSymbolicLink -> {
                                val e = TarArchiveEntry(rel, TarConstants.LF_SYMLINK)
                                e.linkName = Files.readSymbolicLink(file).toString()
                                fill(e, f, posix, 0x1FF)
                                tar.putArchiveEntry(e); tar.closeArchiveEntry(); entries++
                            }
                            attrs.isRegularFile -> {
                                val size = attrs.size()
                                val e = TarArchiveEntry(rel)
                                e.size = size
                                fill(e, f, posix, 0x1A4)
                                tar.putArchiveEntry(e)
                                f.inputStream().use { input ->
                                    var left = size
                                    while (left > 0) {
                                        ctl.check()
                                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                        if (n < 0) throw RootfsBackupException("Berkas berubah saat dicadangkan: $rel")
                                        tar.write(buf, 0, n)
                                        left -= n
                                        done += n
                                        val p = ((done * 100) / total).toInt().coerceIn(0, 100)
                                        if (p != last) { last = p; onProgress(BackupPhase.Write, p) }
                                    }
                                    if (input.read() >= 0) throw RootfsBackupException("Berkas berubah saat dicadangkan: $rel")
                                }
                                tar.closeArchiveEntry(); entries++
                            }
                            else -> skipped++ // fifo, socket, perangkat
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        throw RootfsBackupException("Tidak bisa membaca ${relName(root, file)}: ${exc.message}")
                    }
                })
            }
            moveInto(part, dest)
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
        onProgress(BackupPhase.Write, 100)
        return ExportResult(entries, done, skipped)
    }

    /** Baca arsip sampai habis: jumlah entri, total byte terurai, dan apakah tampak seperti rootfs Linux. */
    fun inspect(archive: File, ctl: BackupControl = BackupControl()): ArchiveInfo {
        var entries = 0
        var bytes = 0L
        var osRelease = false
        var etc = false
        var usr = false
        openTar(archive).use { tar ->
            while (true) {
                val e = tar.nextEntry as? TarArchiveEntry ?: break
                ctl.check()
                entries++
                if (e.isFile) bytes += e.size
                when (cleanName(e.name).trimEnd('/')) {
                    "etc/os-release" -> osRelease = true
                    "etc" -> etc = true
                    "usr" -> usr = true
                }
            }
        }
        return ArchiveInfo(entries, bytes, osRelease || (etc && usr))
    }

    /**
     * Pulihkan [archive] menggantikan [rootfs]: periksa arsip dan ruang kosong, ekstrak ke "<nama>.restore",
     * lalu tukar (yang lama jadi "<nama>.old", dihapus setelah berhasil; dikembalikan bila penukaran gagal).
     * Pemanggil wajib menghentikan semua sesi terminal lebih dulu.
     */
    fun restore(
        archive: File, rootfs: File, posix: RootfsPosix,
        ctl: BackupControl = BackupControl(),
        freeSpaceBytes: Long = rootfs.absoluteFile.parentFile?.usableSpace ?: Long.MAX_VALUE,
        onProgress: (BackupPhase, Int) -> Unit = { _, _ -> },
    ) {
        if (!archive.isFile) throw RootfsBackupException("Berkas cadangan tidak ditemukan")
        val base = rootfs.absoluteFile.parentFile ?: throw RootfsBackupException("Lokasi rootfs tidak valid")
        val staging = File(base, rootfs.name + ".restore")
        val old = File(base, rootfs.name + ".old")

        onProgress(BackupPhase.Inspect, 0)
        val info = try { inspect(archive, ctl) } catch (e: BackupCancelled) { throw e }
            catch (e: IOException) { throw RootfsBackupException("Arsip rusak atau bukan tar.gz: ${e.message}") }
        if (!info.looksLikeRootfs) throw RootfsBackupException("Arsip ini tidak tampak seperti rootfs Linux")
        val need = info.bytes + SPACE_MARGIN
        if (freeSpaceBytes < need) {
            throw RootfsBackupException("Ruang kosong kurang: perlu ${need / (1024 * 1024)} MB, tersedia ${freeSpaceBytes / (1024 * 1024)} MB")
        }

        SafeTree.delete(staging)
        staging.mkdirs()
        try {
            extractInto(archive, staging, posix, ctl) { onProgress(BackupPhase.Extract, it) }
            onProgress(BackupPhase.Swap, 0)
            ctl.check()
            if (!SafeTree.delete(old)) throw RootfsBackupException("Tidak bisa membersihkan sisa rootfs lama (.old)")
            val hadOld = rootfs.exists()
            if (hadOld && !rootfs.renameTo(old)) throw RootfsBackupException("Gagal menyisihkan rootfs lama")
            if (!staging.renameTo(rootfs)) {
                if (hadOld) old.renameTo(rootfs) // kembalikan
                throw RootfsBackupException("Gagal memasang rootfs hasil pemulihan; rootfs lama dikembalikan")
            }
        } catch (t: Throwable) {
            SafeTree.delete(staging)
            throw t
        }
        SafeTree.delete(old) // gagal di sini tidak fatal: dibersihkan pada pemulihan berikutnya
        onProgress(BackupPhase.Swap, 100)
    }

    /** Ekstrak ke [root] (folder kosong). Nama tak tepercaya: ".." dan keluar-rootfs lewat symlink dilewati. */
    internal fun extractInto(
        archive: File, root: File, posix: RootfsPosix, ctl: BackupControl, onPercent: (Int) -> Unit,
    ) {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val rootCanon = root.canonicalPath
        val counter = ByteCounter(archive.inputStream().buffered(256 * 1024))
        val total = archive.length().coerceAtLeast(1L)
        var last = -1
        TarArchiveInputStream(GzipCompressorInputStream(counter)).use { tar ->
            while (true) {
                val e = tar.nextEntry as? TarArchiveEntry ?: break
                ctl.check()
                val name = cleanName(e.name)
                if (name.isEmpty() || name.contains('\u0000')) continue
                val target = rootPath.resolve(name).normalize()
                if (target == rootPath || !target.startsWith(rootPath)) continue
                val f = target.toFile()
                val parent = f.parentFile
                if (parent != null) {
                    parent.mkdirs()
                    if (!SafeTree.isInside(parent, rootCanon)) continue
                }
                val perm = e.mode and 0xFFF
                when {
                    e.isDirectory -> {
                        if (Files.isSymbolicLink(f.toPath())) f.delete()
                        f.mkdirs()
                        posix.chmod(f, perm or 0x1C0) // u+rwx
                    }
                    e.isSymbolicLink -> {
                        SafeTree.delete(f)
                        posix.symlink(f, e.linkName)
                    }
                    e.isLink -> { // hardlink -> salinan (Android melarang hardlink)
                        val src = rootPath.resolve(cleanName(e.linkName)).normalize()
                        val sf = src.toFile()
                        if (src.startsWith(rootPath) && sf.isFile && SafeTree.isInside(sf, rootCanon)) {
                            SafeTree.delete(f)
                            sf.copyTo(f, overwrite = true)
                            posix.chmod(f, perm or 0x180)
                        }
                    }
                    e.isFile -> {
                        if (Files.isSymbolicLink(f.toPath()) || f.isDirectory) SafeTree.delete(f)
                        f.outputStream().use { out -> tar.copyTo(out, 64 * 1024) }
                        posix.chmod(f, perm or 0x180) // u+rw
                        f.setLastModified(e.lastModifiedTime.toMillis())
                    }
                    else -> {} // fifo/perangkat dilewati
                }
                val p = ((counter.count * 100) / total).toInt().coerceIn(0, 100)
                if (p != last) { last = p; onPercent(p) }
            }
        }
    }

    private fun openTar(archive: File) =
        TarArchiveInputStream(GzipCompressorInputStream(archive.inputStream().buffered(256 * 1024)))

    private fun cleanName(raw: String): String = raw.removePrefix("./").trimStart('/')

    private fun relName(root: Path, p: Path): String =
        root.relativize(p).toString().replace(File.separatorChar, '/')

    private fun fill(e: TarArchiveEntry, f: File, posix: RootfsPosix, fallbackMode: Int) {
        e.mode = try { posix.mode(f) } catch (ex: Exception) { fallbackMode }
        e.setUserId(0); e.setGroupId(0); e.userName = ""; e.groupName = ""
        try { e.setModTime(Files.getLastModifiedTime(f.toPath(), LinkOption.NOFOLLOW_LINKS)) } catch (ex: Exception) {}
    }

    private fun scanBytes(root: Path, ctl: BackupControl): Long {
        var sum = 0L
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                ctl.check()
                return if (relName(root, dir) in SKIP_CONTENTS) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && !isSensitive(relName(root, file))) sum += attrs.size()
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return sum
    }

    private fun moveInto(part: File, dest: File) {
        try {
            Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
