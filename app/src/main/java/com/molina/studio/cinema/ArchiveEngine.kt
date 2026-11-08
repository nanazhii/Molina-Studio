package com.molina.studio.cinema

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import net.lingala.zip4j.ZipFile as Z4jZipFile
import net.lingala.zip4j.exception.ZipException as Z4jException
import net.lingala.zip4j.model.FileHeader
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.tukaani.xz.LZMA2Options

/** [ARCHIVE] Hasil ekstrak: [dir] tempat hasil, [files] jumlah berkas (-1 = tidak dihitung), [renamed] berkas yang diganti nama karena bentrok. */
internal class ExtractResult(val dir: File, val files: Int, val renamed: Int) {
    fun describe(): String {
        val sb = StringBuilder("Diekstrak ke: ").append(dir.name)
        if (files >= 0) sb.append(" (").append(files).append(" berkas)")
        if (renamed > 0) sb.append("; ").append(renamed).append(" berkas diberi nama baru karena nama sudah ada")
        return sb.toString()
    }
}

/**
 * [ARCHIVE] Mesin arsip Penjelajah Molina (tanpa Android/Compose; blocking, panggil dari Dispatchers.IO).
 *
 * ZIP tetap lewat zip4j/java.util.zip (di [FileOps]); 7z, tar(.gz/.bz2/.xz), dan gz/bz2/xz tunggal lewat commons-compress
 * (+ org.tukaani:xz). Semua nama entri tidak dipercaya: ".." ditolak, jalur kanonik dicek, tautan/perangkat di dalam tar
 * dilewati. Bila gagal atau dibatalkan, berkas hasil ekstrak dibatalkan (folder bernama dihapus; mode "di sini" hanya
 * menghapus berkas yang dibuat operasi ini). Berkas yang sudah ada TIDAK ditimpa: hasil diberi nama "nama (1).ext".
 */
internal object ArchiveEngine {
    private const val MIN_FREE = 8L * 1024 * 1024
    private const val SPACE_CHECK_EVERY = 16L * 1024 * 1024

    /** Galat buatan mesin ini (pesan sudah siap tampil); lolos dari pemetaan galat umum. */
    private class Abort(msg: String) : IOException(msg)

    fun supports(f: File): Boolean = f.isFile && ArchiveNames.detect(f.name) != null

    // ---------------------------------------------------------------- daftar isi

    private class ListAcc {
        val items = ArrayList<FileOps.ZipEntryInfo>()
        var files = 0
        var folders = 0
        var total = 0L
        var enc = false
        var truncated = false
        fun add(name: String, size: Long, dir: Boolean) {
            if (dir) folders++ else { files++; if (size > 0) total += size }
            if (items.size < FileOps.ZIP_LIST_MAX) items.add(FileOps.ZipEntryInfo(name, size, dir)) else truncated = true
        }
        fun result() = FileOps.ZipListing(items, files, folders, total, enc, truncated)
    }

    /**
     * Daftar isi tanpa mengekstrak. 7z berkata sandi: bila nama entri ikut dienkripsi tanpa [password] melempar
     * [FileOps.NeedPassword]; bila hanya isinya, [FileOps.ZipListing.encrypted] bernilai true.
     */
    fun list(file: File, password: String? = null): FileOps.ZipListing {
        if (!file.isFile) throw IOException("Berkas arsip tidak ditemukan")
        if (file.length() == 0L) throw IOException("Berkas arsip kosong (0 byte): pembuatannya mungkin terhenti.")
        val fmt = ArchiveNames.detect(file.name) ?: throw IOException("Format arsip tidak didukung: " + file.name)
        if (fmt == ArchiveFormat.ZIP) return FileOps.listZip(file)
        val acc = ListAcc()
        try {
            when {
                fmt == ArchiveFormat.SEVEN_Z -> open7z(file, password).use { sz ->
                    for (en in sz.entries) {
                        if (en.isAntiItem) continue
                        acc.add(if (en.isDirectory) en.name.trimEnd('/') + "/" else en.name, en.size, en.isDirectory)
                    }
                    // Metode kompresi baru diketahui saat dekode, jadi sandi hanya-isi dideteksi dengan membaca 1 byte pertama
                    if (password.isNullOrEmpty()) acc.enc = probeEncrypted7z(sz)
                }
                fmt.isTar -> openTar(file, fmt, null).use { tin ->
                    while (acc.items.size < FileOps.ZIP_LIST_MAX) {
                        val en = tin.nextEntry ?: break
                        if (en.isSymbolicLink || en.isLink) continue
                        acc.add(if (en.isDirectory) en.name.trimEnd('/') + "/" else en.name, en.size, en.isDirectory)
                    }
                    if (acc.items.size >= FileOps.ZIP_LIST_MAX && tin.nextEntry != null) acc.truncated = true
                }
                else -> { // gz/bz2/xz tunggal: uji bahwa kepala berkas valid, lalu satu entri bernama stem
                    openSingle(file, fmt, null).use { it.read() }
                    acc.add(ArchiveNames.stem(file.name), 0L, false)
                }
            }
        } catch (e: OutOfMemoryError) {
            throw IOException("Memori tidak cukup untuk membuka arsip ini")
        } catch (e: Exception) {
            throw mapError(e, fmt, password, null)
        }
        return acc.result()
    }

    /** True bila isi entri pertama yang punya data butuh kata sandi (kepala tidak terenkripsi tetapi isinya ya). */
    private fun probeEncrypted7z(sz: SevenZFile): Boolean {
        try {
            while (true) {
                val en = sz.nextEntry ?: return false
                if (en.isAntiItem || en.isDirectory || !en.hasStream()) continue
                sz.getInputStream(en).use { it.read() }
                return false
            }
        } catch (e: PasswordRequiredException) {
            return true
        } catch (e: Exception) {
            return false // rusak atau tak terbaca: biarkan ekstrak yang melaporkan
        }
    }

    // ---------------------------------------------------------------- ekstrak

    /**
     * Ekstrak [file] ke [destDir]. [target] HERE = langsung ke [destDir]; NAMED_FOLDER = subfolder baru bernama arsip.
     * [only] = kunci entri terpilih (folder berakhiran "/", mencakup isinya), null = semua.
     */
    fun extract(
        file: File, destDir: File, target: ExtractTarget, ctl: FileOps.Ctl,
        password: String? = null, only: Set<String>? = null
    ): ExtractResult {
        if (!file.isFile) throw IOException("Berkas arsip tidak ditemukan")
        val fmt = ArchiveNames.detect(file.name) ?: throw IOException("Format arsip tidak didukung: " + file.name)
        if (!destDir.isDirectory || !destDir.canWrite()) throw IOException("Folder tujuan tidak bisa ditulisi")
        if (fmt == ArchiveFormat.ZIP && target == ExtractTarget.NAMED_FOLDER && only == null) {
            return ExtractResult(FileOps.unzip(file, destDir, ctl, password), -1, 0) // jalur ZIP lama, perilaku tidak berubah
        }
        val named = target == ExtractTarget.NAMED_FOLDER
        val base = if (named) FileOps.uniqueTarget(destDir, ArchiveNames.folderName(file.name), keepExt = false) else destDir
        if (named && !base.mkdirs()) throw IOException("Gagal membuat folder " + base.name)
        val sink = Sink(base, only, ctl)
        fun undo() { if (named) base.deleteRecursively() else sink.rollback() }
        try {
            when {
                fmt == ArchiveFormat.ZIP -> extractZip(file, sink, password)
                fmt == ArchiveFormat.SEVEN_Z -> extract7z(file, sink, password)
                fmt.isTar -> extractTar(file, fmt, sink)
                else -> extractSingle(file, fmt, sink)
            }
            if (only != null && sink.files == 0) throw Abort("Tidak ada entri terpilih yang cocok di arsip")
        } catch (e: OutOfMemoryError) {
            undo()
            throw IOException("Memori tidak cukup untuk mengekstrak arsip ini")
        } catch (e: Exception) {
            undo()
            throw mapError(e, fmt, password, ctl)
        }
        return ExtractResult(base, sink.files, sink.renamed)
    }

    /** Penulis hasil: memvalidasi nama, menjaga jalur tetap di dalam [base], tidak menimpa, memeriksa ruang kosong. */
    private class Sink(val base: File, val only: Set<String>?, val ctl: FileOps.Ctl) {
        private val created = ArrayList<File>()
        var files = 0
        var renamed = 0
        private var sinceCheck = 0L

        /** Nama bersih bila entri dipilih; null bila dilewati. Nama tidak aman melempar [Abort]. */
        fun accept(raw: String, isDir: Boolean): String? {
            val clean = ArchiveNames.cleanEntry(raw) ?: throw Abort("Entri arsip tidak aman: $raw")
            if (clean.isEmpty()) return null
            return if (ArchiveNames.selected(clean, isDir, only)) clean else null
        }

        fun dir(clean: String) {
            val d = ArchiveNames.safeTarget(base, clean) ?: throw Abort("Entri arsip tidak aman: $clean")
            if (!d.isDirectory && !d.mkdirs()) throw Abort("Gagal membuat folder " + d.name)
        }

        /** Tentukan berkas keluaran (nama unik bila bentrok) dan catat agar bisa dibatalkan. */
        fun reserve(clean: String): File {
            var out = ArchiveNames.safeTarget(base, clean) ?: throw Abort("Entri arsip tidak aman: $clean")
            val parent = out.parentFile ?: throw Abort("Entri arsip tidak aman: $clean")
            if (!parent.isDirectory && !parent.mkdirs()) throw Abort("Gagal membuat folder " + parent.name)
            if (out.exists()) { out = ArchiveNames.uniqueIn(parent, out.name); renamed++ }
            created.add(out)
            files++
            return out
        }

        /** Tulis isi [src] ke entri [clean]. [byteProgress] = laporkan byte keluaran ke [ctl] (tar/gz melaporkan byte terkompresi). */
        fun write(clean: String, mtime: Long, src: InputStream, byteProgress: Boolean) {
            val out = reserve(clean)
            out.outputStream().buffered().use { o ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    ctl.check()
                    val n = src.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    if (byteProgress) ctl.add(n.toLong())
                    sinceCheck += n
                    if (sinceCheck >= SPACE_CHECK_EVERY) {
                        sinceCheck = 0
                        if (base.usableSpace < MIN_FREE) throw Abort("Ruang penyimpanan tidak cukup")
                    }
                }
            }
            if (mtime > 0) out.setLastModified(mtime)
        }

        fun rollback() { for (f in created.asReversed()) f.delete() }
    }

    private fun extractZip(file: File, sink: Sink, pw: String?) {
        val ctl = sink.ctl
        if (FileOps.isEncryptedZip(file)) {
            if (pw.isNullOrEmpty()) throw FileOps.NeedPassword()
            val zf = Z4jZipFile(file, pw.toCharArray())
            val picks = ArrayList<Pair<FileHeader, String>>()
            for (h in zf.getFileHeaders()) {
                val dir = h.isDirectory()
                val clean = sink.accept(h.getFileName(), dir) ?: continue
                if (dir) sink.dir(clean) else picks.add(Pair(h, clean))
            }
            ctl.total = picks.sumOf { it.first.getUncompressedSize().coerceAtLeast(0L) }
            for ((h, clean) in picks) {
                ctl.check()
                val out = sink.reserve(clean)
                zf.extractFile(h, out.parent, out.name)
                ctl.add(h.getUncompressedSize().coerceAtLeast(0L))
            }
        } else {
            ZipFile(file).use { zf ->
                val picks = ArrayList<Pair<ZipEntry, String>>()
                for (en in zf.entries().toList()) {
                    val clean = sink.accept(en.name, en.isDirectory) ?: continue
                    if (en.isDirectory) sink.dir(clean) else picks.add(Pair(en, clean))
                }
                ctl.total = picks.sumOf { it.first.size.coerceAtLeast(0L) }
                for ((en, clean) in picks) zf.getInputStream(en).use { sink.write(clean, en.time, it, true) }
            }
        }
    }

    private fun extract7z(file: File, sink: Sink, pw: String?) {
        val ctl = sink.ctl
        open7z(file, pw).use { sz ->
            var total = 0L
            for (en in sz.entries) {
                if (en.isDirectory || en.isAntiItem || !en.hasStream()) continue
                val c = ArchiveNames.cleanEntry(en.name)
                if (c != null && c.isNotEmpty() && ArchiveNames.selected(c, false, sink.only)) total += en.size
            }
            ctl.total = total
            if (total > 0 && sink.base.usableSpace < total + MIN_FREE) throw Abort("Ruang penyimpanan tidak cukup")
            while (true) {
                val en = sz.nextEntry ?: break
                ctl.check()
                if (en.isAntiItem) continue
                val clean = sink.accept(en.name, en.isDirectory) ?: continue
                if (en.isDirectory) { sink.dir(clean); continue }
                val mt = if (en.hasLastModifiedDate) en.lastModifiedDate.time else 0L
                val src: InputStream = if (en.hasStream()) sz.getInputStream(en) else ByteArrayInputStream(ByteArray(0))
                sink.write(clean, mt, src, true)
            }
        }
    }

    private fun extractTar(file: File, fmt: ArchiveFormat, sink: Sink) {
        sink.ctl.total = file.length() // progres = byte terkompresi yang sudah terbaca
        openTar(file, fmt, sink.ctl).use { tin ->
            while (true) {
                val en = tin.nextEntry ?: break
                sink.ctl.check()
                if (en.isSymbolicLink || en.isLink) continue // tautan tidak pernah dibuat
                if (!en.isDirectory && !en.isFile) continue // perangkat, FIFO, dst.
                val clean = sink.accept(en.name, en.isDirectory) ?: continue
                if (en.isDirectory) sink.dir(clean) else sink.write(clean, en.modTime.time, tin, false)
            }
        }
    }

    private fun extractSingle(file: File, fmt: ArchiveFormat, sink: Sink) {
        sink.ctl.total = file.length()
        openSingle(file, fmt, sink.ctl).use { ins ->
            val clean = sink.accept(ArchiveNames.stem(file.name), false) ?: return
            sink.write(clean, 0L, ins, false)
        }
    }

    // ---------------------------------------------------------------- kompres [ARCHIVE2]

    /** Pengawas ruang kosong: periksa tiap [SPACE_CHECK_EVERY] byte masukan agar kompres tidak mengisi penuh penyimpanan. */
    private class SpaceGuard(val dir: File) {
        private var since = 0L
        fun add(n: Int) {
            since += n
            if (since >= SPACE_CHECK_EVERY) {
                since = 0
                if (dir.usableSpace < MIN_FREE) throw Abort("Ruang penyimpanan tidak cukup")
            }
        }
    }

    private fun isLink(f: File): Boolean = try { Files.isSymbolicLink(f.toPath()) } catch (e: Exception) { false }

    /**
     * Telusuri [files] berurutan (folder lebih dulu, anak diurutkan nama). Tautan simbolik, berkas khusus (FIFO, soket), dan
     * [skip] (arsip keluaran sendiri) dilewati. Folder yang tidak bisa dibaca = galat, bukan arsip yang diam-diam tidak lengkap.
     */
    private fun walk(files: List<File>, skip: File, ctl: FileOps.Ctl, visit: (path: String, f: File, dir: Boolean) -> Unit) {
        fun rec(f: File, path: String) {
            ctl.check()
            if (isLink(f) || f.canonicalFile == skip) return
            if (f.isDirectory) {
                visit(path, f, true)
                val kids = f.listFiles() ?: throw Abort("Folder tidak bisa dibaca: " + f.name)
                for (k in kids.sortedBy { it.name }) rec(k, path + "/" + k.name)
            } else if (f.isFile) {
                visit(path, f, false)
            }
        }
        for (f in files) rec(f, f.name)
    }

    /** Baca tepat [size] byte dari [f] ke [sink]; berkas yang menyusut saat dibaca = galat, yang membesar dipotong di [size]. */
    private fun feed(f: File, size: Long, ctl: FileOps.Ctl, guard: SpaceGuard, sink: (ByteArray, Int) -> Unit) {
        var left = size
        FileInputStream(f).use { ins ->
            val buf = ByteArray(128 * 1024)
            while (left > 0) {
                ctl.check()
                val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw Abort("Berkas berubah saat dikompres: " + f.name)
                sink(buf, n)
                left -= n
                ctl.add(n.toLong())
                guard.add(n)
            }
        }
    }

    /**
     * Kompres [files] menjadi satu arsip bernama [CompressOptions.baseName] + ekstensi format di [destDir]; hasil tidak menimpa
     * berkas lama (nama diberi " (1)"). Blocking: panggil dari Dispatchers.IO. Gagal/batal = berkas keluaran dihapus.
     * ZIP lewat [FileOps.zipAll] (zip4j untuk AES); 7z dan tar(.gz/.bz2/.xz) lewat commons-compress. Tautan dilewati.
     */
    fun compress(files: List<File>, destDir: File, opt: CompressOptions, ctl: FileOps.Ctl): File {
        val fmt = opt.format
        val pw = opt.password?.takeIf { it.isNotEmpty() }
        if (files.isEmpty()) throw IOException("Tidak ada yang dikompres")
        for (f in files) if (!f.exists() && !isLink(f)) throw IOException("Sumber tidak ditemukan: " + f.name)
        if (!destDir.isDirectory || !destDir.canWrite()) throw IOException("Folder tujuan tidak bisa ditulisi")
        files.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }?.let {
            throw IOException("Nama bentrok di dalam arsip: \"" + it.key + "\" ada di lebih dari satu folder. Kompres terpisah atau ubah namanya.")
        }
        CompressNames.baseNameError(opt.baseName, fmt)?.let { throw IOException(it) }
        // [ARCHIVE6] FAIL-CLOSED: pustaka bawaan tidak bisa mengenkripsi 7z. Tanpa baris ini 7z + sandi akan menulis arsip TANPA sandi.
        if (pw != null && fmt == CompressFormat.SEVEN_Z) throw IOException("7z dengan kata sandi harus dibuat lewat ArchiveOps.compress (7z di Debian)")
        if (pw != null && !fmt.hasPassword) throw IOException("Format " + fmt.label + " belum mendukung kata sandi")
        val base = CompressNames.cleanBaseName(opt.baseName, fmt)
        val level = fmt.clampLevel(opt.level)
        if (fmt == CompressFormat.ZIP) return FileOps.zipAll(files, destDir, ctl, pw, base, level)

        val out = CompressNames.uniqueOutput(destDir, base, fmt.ext)
        val skip = out.canonicalFile
        val guard = SpaceGuard(destDir)
        try {
            val total = FileOps.sizeOf(files)
            ctl.total = total
            if (fmt == CompressFormat.TAR && destDir.usableSpace < total + MIN_FREE) throw Abort("Ruang penyimpanan tidak cukup")
            if (destDir.usableSpace < MIN_FREE) throw Abort("Ruang penyimpanan tidak cukup")
            val n = if (fmt == CompressFormat.SEVEN_Z) write7z(files, out, level, ctl, guard, skip) else writeTar(files, out, fmt, level, ctl, guard, skip)
            if (n == 0) throw Abort("Tidak ada berkas yang bisa dikompres (tautan simbolik dan berkas khusus dilewati)")
        } catch (e: OutOfMemoryError) {
            out.delete()
            throw IOException("Memori tidak cukup untuk level ini. Turunkan level kompresi.")
        } catch (e: Exception) {
            out.delete()
            throw mapCompressError(e, ctl)
        }
        return out
    }

    /** Tulis tar (opsional dikompres gz/bz2/xz); mengembalikan jumlah entri. Nama non-ASCII dan panjang lewat PAX. */
    private fun writeTar(files: List<File>, out: File, fmt: CompressFormat, level: Int, ctl: FileOps.Ctl, guard: SpaceGuard, skip: File): Int {
        val raw = BufferedOutputStream(FileOutputStream(out), 64 * 1024)
        var tos: TarArchiveOutputStream? = null
        var count = 0
        try {
            val comp: OutputStream = when (fmt) {
                CompressFormat.TAR_GZ -> GzipCompressorOutputStream(raw, GzipParameters().apply { compressionLevel = level })
                CompressFormat.TAR_BZ2 -> BZip2CompressorOutputStream(raw, level)
                CompressFormat.TAR_XZ -> XZCompressorOutputStream(raw, level)
                else -> raw
            }
            val t = TarArchiveOutputStream(comp)
            tos = t
            t.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            t.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            t.setAddPaxHeadersForNonAsciiNames(true)
            walk(files, skip, ctl) { path, f, dir ->
                val e = TarArchiveEntry(f, if (dir) "$path/" else path)
                t.putArchiveEntry(e)
                if (!dir) feed(f, e.size, ctl, guard) { b, n -> t.write(b, 0, n) }
                t.closeArchiveEntry()
                count++
            }
            t.finish()
            t.close()
            tos = null
        } finally {
            try { tos?.close() } catch (_: Exception) {}
            try { raw.close() } catch (_: Exception) {}
        }
        return count
    }

    /** Tulis 7z (LZMA2, atau COPY bila level 0); mengembalikan jumlah entri. Tanpa kata sandi (penulis commons-compress tidak mengenkripsi). */
    private fun write7z(files: List<File>, out: File, level: Int, ctl: FileOps.Ctl, guard: SpaceGuard, skip: File): Int {
        val sz = SevenZOutputFile(out)
        var count = 0
        try {
            walk(files, skip, ctl) { path, f, dir ->
                val e = sz.createArchiveEntry(f, path)
                // Atribut Windows (0x10 = folder, 0x20 = arsip): 7-Zip tidak butuh, tetapi pembaca lain (py7zr, dll.) memakainya untuk mengenali folder
                e.setHasWindowsAttributes(true)
                e.setWindowsAttributes(if (dir) 0x10 else 0x20)
                if (dir) {
                    sz.putArchiveEntry(e)
                    sz.closeArchiveEntry()
                } else {
                    val size = f.length()
                    e.setContentMethods(methods7z(level, size))
                    sz.putArchiveEntry(e)
                    feed(f, size, ctl, guard) { b, n -> sz.write(b, 0, n) }
                    sz.closeArchiveEntry()
                }
                count++
            }
            sz.finish()
        } finally {
            try { sz.close() } catch (_: Exception) {}
        }
        return count
    }

    /**
     * Metode isi per berkas. commons-compress menulis tiap berkas sebagai blok sendiri dan membuat enkoder LZMA2 baru,
     * jadi kamus dikecilkan ke ukuran berkas (min 4 KB) agar ribuan berkas kecil tidak menelan puluhan MB per berkas.
     */
    private fun methods7z(level: Int, size: Long): List<SevenZMethodConfiguration> {
        if (level <= 0) return listOf(SevenZMethodConfiguration(SevenZMethod.COPY))
        val o = LZMA2Options()
        o.setPreset(level)
        var want = LZMA2Options.DICT_SIZE_MIN
        while (want < o.getDictSize() && want < size) want = want shl 1
        o.setDictSize(minOf(o.getDictSize(), want))
        return listOf(SevenZMethodConfiguration(SevenZMethod.LZMA2, o))
    }

    private fun mapCompressError(e: Exception, ctl: FileOps.Ctl): IOException {
        if (e is Abort) return e
        if (ctl.cancelled()) return IOException("Dibatalkan")
        if (e is IOException && e.message == "Dibatalkan") return e
        val m = e.message ?: e.javaClass.simpleName
        if (m.contains("No space left", ignoreCase = true) || m.contains("ENOSPC")) return IOException("Ruang penyimpanan tidak cukup")
        if (e is java.io.FileNotFoundException) return IOException("Tidak bisa membaca atau menulis berkas ($m)", e)
        return IOException("Gagal mengompres ($m)", e)
    }

    // ---------------------------------------------------------------- pembuka aliran

    /** Pembungkus yang melaporkan byte mentah terbaca ke [ctl] (progres untuk aliran terkompresi) dan memeriksa pembatalan. */
    private class Counting(i: InputStream, private val ctl: FileOps.Ctl?) : FilterInputStream(i) {
        override fun read(): Int {
            ctl?.check()
            val b = super.read()
            if (b >= 0) ctl?.add(1)
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            ctl?.check()
            val n = super.read(b, off, len)
            if (n > 0) ctl?.add(n.toLong())
            return n
        }
        override fun skip(n: Long): Long {
            val s = super.skip(n)
            if (s > 0) ctl?.add(s)
            return s
        }
    }

    private fun decompress(buf: InputStream, fmt: ArchiveFormat): InputStream = when (fmt) {
        ArchiveFormat.TAR_GZ, ArchiveFormat.GZ -> GzipCompressorInputStream(buf)
        ArchiveFormat.TAR_BZ2, ArchiveFormat.BZ2 -> BZip2CompressorInputStream(buf)
        ArchiveFormat.TAR_XZ, ArchiveFormat.XZ -> XZCompressorInputStream(buf)
        else -> buf
    }

    private fun openStream(file: File, fmt: ArchiveFormat, ctl: FileOps.Ctl?): InputStream {
        val raw = Counting(FileInputStream(file), ctl)
        val buf = BufferedInputStream(raw, 64 * 1024)
        try {
            return decompress(buf, fmt)
        } catch (e: Exception) {
            try { buf.close() } catch (_: Exception) {}
            throw e
        }
    }

    private fun openTar(file: File, fmt: ArchiveFormat, ctl: FileOps.Ctl?): TarArchiveInputStream {
        val s = openStream(file, fmt, ctl)
        try {
            return TarArchiveInputStream(s)
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            throw e
        }
    }

    private fun openSingle(file: File, fmt: ArchiveFormat, ctl: FileOps.Ctl?): InputStream = openStream(file, fmt, ctl)

    private fun open7z(file: File, pw: String?): SevenZFile {
        try {
            val b = SevenZFile.builder().setFile(file)
            if (!pw.isNullOrEmpty()) b.setPassword(pw.toCharArray())
            return b.get()
        } catch (e: PasswordRequiredException) {
            throw FileOps.NeedPassword()
        } catch (e: Exception) {
            // Dengan sandi di tangan, gagal membuka hampir pasti sandi salah (kepala terenkripsi)
            throw if (!pw.isNullOrEmpty()) FileOps.WrongPassword() else e
        }
    }

    // ---------------------------------------------------------------- pemetaan galat

    private fun mapError(e: Exception, fmt: ArchiveFormat, pw: String?, ctl: FileOps.Ctl?): IOException {
        if (e is IOException && (e is FileOps.NeedPassword || e is FileOps.WrongPassword || e is Abort)) return e
        if (ctl != null && ctl.cancelled()) return IOException("Dibatalkan")
        if (e is IOException && e.message == "Dibatalkan") return e
        if (e is PasswordRequiredException) return FileOps.NeedPassword()
        if (e is Z4jException) return FileOps.mapZip(e, "Gagal mengekstrak")
        if (fmt == ArchiveFormat.SEVEN_Z && !pw.isNullOrEmpty()) return FileOps.WrongPassword()
        return IOException("Arsip rusak, tidak lengkap, atau formatnya tidak didukung (" + (e.message ?: e.javaClass.simpleName) + ")", e)
    }
}
