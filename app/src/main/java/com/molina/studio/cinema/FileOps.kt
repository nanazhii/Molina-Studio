package com.molina.studio.cinema

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import net.lingala.zip4j.ZipFile as Z4jZipFile
import net.lingala.zip4j.exception.ZipException as Z4jException
import net.lingala.zip4j.model.ExcludeFileFilter
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import net.lingala.zip4j.progress.ProgressMonitor

/**
 * Operasi berkas murni untuk Penjelajah Molina (tanpa Android/Compose). Semua fungsi bersifat blocking:
 * panggil dari Dispatchers.IO. Kegagalan dilaporkan lewat IOException berpesan bahasa Indonesia.
 * Operasi panjang menerima [Ctl] untuk progres dan pembatalan.
 */
internal object FileOps {
    class Props(
        val file: File,
        val isDir: Boolean,
        val size: Long,
        val files: Int,
        val folders: Int,
        val modified: Long,
        val canRead: Boolean,
        val canWrite: Boolean,
        val symlink: Boolean
    )

    /** Kendali operasi panjang: [total] byte (diisi operasi), progres 0..1 dilaporkan per persen, [check] melempar bila dibatalkan. */
    class Ctl(private val cancel: AtomicBoolean, private val report: (Float) -> Unit) {
        @Volatile var total = 0L
        private var done = 0L
        private var last = -1
        fun check() { if (cancel.get()) throw IOException("Dibatalkan") }
        /** [ARCHIVE] True bila pengguna menekan Hentikan (untuk membedakan pembatalan dari galat arsip). */
        fun cancelled(): Boolean = cancel.get()
        fun add(n: Long) {
            done += n
            val t = total
            if (t > 0) {
                val p = (done * 100 / t).coerceIn(0L, 100L).toInt()
                if (p != last) { last = p; report(p / 100f) }
            }
        }
    }

    /** [ZIP-AES] ZIP berkata sandi tetapi sandi belum diberikan. */
    class NeedPassword : IOException("Arsip ini dilindungi kata sandi")

    /** [ZIP-AES] Kata sandi ZIP salah. */
    class WrongPassword : IOException("Kata sandi salah")

    private fun isLink(f: File): Boolean = try { Files.isSymbolicLink(f.toPath()) } catch (e: Exception) { false }
    private fun present(f: File): Boolean = f.exists() || isLink(f)
    /** Ubah exception apa pun jadi IOException berpesan jelas (jenis error ikut disebut agar bisa didiagnosis). */
    private fun asIo(e: Exception, fallback: String): IOException = when {
        e is NeedPassword || e is WrongPassword -> e as IOException
        e is java.util.zip.ZipException || e is Z4jException ->
            IOException("ZIP rusak, tidak lengkap, atau formatnya tidak didukung (" + (e.message ?: e.javaClass.simpleName) + ")", e)
        e is IOException -> e
        else -> IOException(fallback + ": " + (e.message ?: e.javaClass.simpleName), e)
    }

    /** True bila [child] sama dengan [parent] atau berada di dalamnya (jalur kanonik). */
    fun isInside(parent: File, child: File): Boolean {
        val p = parent.canonicalFile.path
        val c = child.canonicalFile.path
        return c == p || c.startsWith(p + File.separator)
    }

    /** Nama bebas bentrok di [dir]: "nama (1).ext", "nama (2).ext", dst. */
    fun uniqueTarget(dir: File, name: String, keepExt: Boolean = true): File {
        var f = File(dir, name)
        if (!present(f)) return f
        val dot = name.lastIndexOf('.')
        val base = if (keepExt && dot > 0) name.substring(0, dot) else name
        val ext = if (keepExt && dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            f = File(dir, "$base ($i)$ext")
            if (!present(f)) return f
            i++
        }
    }

    private fun validName(raw: String): String {
        val n = raw.trim()
        if (n.isEmpty()) throw IOException("Nama tidak boleh kosong")
        if (n == "." || n == ".." || n.contains('/') || n.contains('\u0000')) throw IOException("Nama tidak valid")
        if (n.length > 255) throw IOException("Nama terlalu panjang (maks. 255 karakter)")
        return n
    }

    internal fun pump(i: InputStream, o: OutputStream, ctl: Ctl) {
        val buf = ByteArray(128 * 1024)
        while (true) {
            ctl.check()
            val n = i.read(buf)
            if (n < 0) break
            o.write(buf, 0, n)
            ctl.add(n.toLong())
        }
    }

    private fun checkDest(src: File, destDir: File) {
        if (!present(src)) throw IOException("Sumber tidak ditemukan: " + src.name)
        if (!destDir.isDirectory) throw IOException("Folder tujuan tidak ditemukan")
        if (!destDir.canWrite()) throw IOException("Folder tujuan tidak bisa ditulisi")
        if (src.isDirectory && !isLink(src) && isInside(src, destDir)) {
            throw IOException("Folder tidak bisa dimasukkan ke dalam dirinya sendiri")
        }
    }

    private fun copyRec(src: File, dst: File, ctl: Ctl) {
        ctl.check()
        if (isLink(src)) {
            try {
                Files.createSymbolicLink(dst.toPath(), Files.readSymbolicLink(src.toPath()))
            } catch (e: Exception) {
                throw IOException("Tautan simbolik tidak dapat disalin: " + src.name)
            }
            return
        }
        if (src.isDirectory) {
            if (!dst.mkdirs() && !dst.isDirectory) throw IOException("Gagal membuat folder " + dst.name)
            val kids = src.listFiles() ?: throw IOException("Folder tidak dapat dibaca: " + src.name)
            for (k in kids) copyRec(k, File(dst, k.name), ctl)
        } else {
            src.inputStream().use { i -> dst.outputStream().use { o -> pump(i, o, ctl) } }
        }
        dst.setLastModified(src.lastModified())
    }

    private fun deleteRec(f: File, ctl: Ctl? = null): Boolean {
        ctl?.check()
        if (!isLink(f) && f.isDirectory) {
            val kids = f.listFiles()
            if (kids != null) for (k in kids) if (!deleteRec(k, ctl)) return false
        }
        return f.delete()
    }

    /** Total byte semua isi [files] (tautan simbolik tidak diikuti). */
    fun sizeOf(files: List<File>): Long {
        var s = 0L
        fun walk(f: File) {
            if (isLink(f)) return
            if (f.isDirectory) f.listFiles()?.forEach { walk(it) } else s += f.length()
        }
        files.forEach { walk(it) }
        return s
    }

    /** Salin semua [files] ke [destDir]. Nama bentrok diberi akhiran " (1)". Mengembalikan hasil salinan. */
    fun copyAll(files: List<File>, destDir: File, ctl: Ctl): List<File> {
        ctl.total = sizeOf(files)
        val out = ArrayList<File>()
        for (f in files) {
            ctl.check()
            checkDest(f, destDir)
            val t = uniqueTarget(destDir, f.name, keepExt = !f.isDirectory)
            try {
                copyRec(f, t, ctl)
            } catch (e: Exception) {
                deleteRec(t)
                throw asIo(e, "Gagal menyalin")
            }
            out.add(t)
        }
        return out
    }

    /** Pindahkan semua [files] ke [destDir]: rename cepat; bila beda volume salin lalu hapus sumber. */
    fun moveAll(files: List<File>, destDir: File, ctl: Ctl): List<File> {
        ctl.total = sizeOf(files)
        val out = ArrayList<File>()
        for (f in files) {
            ctl.check()
            checkDest(f, destDir)
            if (f.parentFile?.canonicalFile == destDir.canonicalFile) {
                throw IOException("\"" + f.name + "\" sudah berada di folder ini")
            }
            val t = uniqueTarget(destDir, f.name, keepExt = !f.isDirectory)
            val sz = sizeOf(listOf(f))
            if (f.renameTo(t)) { ctl.add(sz); out.add(t); continue }
            try {
                copyRec(f, t, ctl)
            } catch (e: Exception) {
                deleteRec(t)
                throw asIo(e, "Gagal memindahkan")
            }
            if (!deleteRec(f)) throw IOException("Sudah disalin ke tujuan, tetapi \"" + f.name + "\" tidak bisa dihapus")
            out.add(t)
        }
        return out
    }

    /** Hapus semua [files]. Jalur di [guard] (jalur absolut) dilindungi dan ditolak. Mengembalikan jumlah item terhapus. */
    fun deleteAll(files: List<File>, guard: Set<String>, ctl: Ctl): Int {
        for (f in files) if (f.absolutePath in guard) throw IOException("Folder ini dilindungi dan tidak bisa dihapus: " + f.name)
        var n = 0
        for (f in files) {
            ctl.check()
            if (!present(f)) continue
            if (!deleteRec(f, ctl)) throw IOException("Gagal menghapus: " + f.name)
            n++
        }
        return n
    }

    /** Buat folder [name] di [parent]. */
    fun mkdir(parent: File, name: String): File {
        val n = validName(name)
        if (!parent.isDirectory || !parent.canWrite()) throw IOException("Folder ini tidak bisa ditulisi")
        val t = File(parent, n)
        if (present(t)) throw IOException("Sudah ada \"" + n + "\" di folder ini")
        if (!t.mkdir()) throw IOException("Gagal membuat folder (periksa izin tulis)")
        return t
    }

    /** Ubah nama [src] (hanya nama, tanpa jalur). Mengembalikan File baru. */
    fun rename(src: File, newName: String): File {
        val n = validName(newName)
        if (!present(src)) throw IOException("Sumber tidak ditemukan: " + src.name)
        if (n == src.name) return src
        val parent = src.parentFile ?: throw IOException("Folder induk tidak ditemukan")
        val target = File(parent, n)
        // Penyimpanan bersama tidak peka huruf besar/kecil: ganti kapitalisasi nama sendiri tetap diizinkan.
        if (!n.equals(src.name, ignoreCase = true) && present(target)) {
            throw IOException("Sudah ada \"" + n + "\" di folder ini")
        }
        if (!src.renameTo(target)) throw IOException("Gagal mengubah nama (periksa izin tulis)")
        return target
    }

    /**
     * Kompres [files] menjadi satu ZIP di [destDir]. Satu item: nama item; banyak: nama folder tujuan. Tautan dilewati.
     * [ARCHIVE2] [baseName] = nama dasar bawaan pengguna (tanpa ".zip"); [level] 0..9 (0 = simpan saja), -1 = bawaan.
     */
    fun zipAll(
        files: List<File>, destDir: File, ctl: Ctl, password: String? = null,
        baseName: String? = null, level: Int = Deflater.DEFAULT_COMPRESSION
    ): File {
        if (files.isEmpty()) throw IOException("Tidak ada yang dikompres")
        for (f in files) if (!present(f)) throw IOException("Sumber tidak ditemukan: " + f.name)
        if (!destDir.isDirectory || !destDir.canWrite()) throw IOException("Folder tujuan tidak bisa ditulisi")
        files.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }?.let {
            throw IOException("Nama bentrok di dalam ZIP: \"" + it.key + "\" ada di lebih dari satu folder. Kompres terpisah atau ubah namanya.")
        } // [PICK-PERSIST]
        val one = files.singleOrNull()
        val stem = baseName?.takeIf { it.isNotBlank() } ?: if (one != null) {
            if (one.isDirectory) one.name else one.nameWithoutExtension.ifEmpty { one.name }
        } else destDir.name.ifEmpty { "Arsip" }
        val out = uniqueTarget(destDir, "$stem.zip", keepExt = true)
        if (!password.isNullOrEmpty()) return zipEncrypted(files, out, password, ctl, level)
        ctl.total = sizeOf(files)
        try {
            ZipOutputStream(out.outputStream().buffered()).use { z ->
                z.setLevel(level.coerceIn(Deflater.DEFAULT_COMPRESSION, Deflater.BEST_COMPRESSION)) // [ARCHIVE2]
                val skip = out.canonicalFile
                for (f in files) addZip(z, f, f.name, skip, ctl)
            }
        } catch (e: Exception) {
            out.delete()
            throw asIo(e, "Gagal mengompres")
        }
        return out
    }

    /** [ZIP-AES] ZIP terenkripsi AES-256 (zip4j). Tautan dilewati; progres dan pembatalan lewat [ctl]. */
    private fun zipEncrypted(files: List<File>, out: File, password: String, ctl: Ctl, level: Int = Deflater.DEFAULT_COMPRESSION): File {
        val skip = out.canonicalFile
        val params = ZipParameters()
        if (level >= 0) { // [ARCHIVE2] level 0 = simpan saja; selain itu petakan 1..9 ke CompressionLevel zip4j
            val l = level.coerceAtMost(9)
            params.setCompressionLevel(CompressionLevel.values().first { it.getLevel() == l })
            if (l == 0) params.setCompressionMethod(CompressionMethod.STORE)
        }
        params.setEncryptFiles(true)
        params.setEncryptionMethod(EncryptionMethod.AES)
        params.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256)
        params.setExcludeFileFilter(ExcludeFileFilter { f -> isLink(f) || f.canonicalFile == skip })
        ctl.total = files.size * 100L
        try {
            val zf = Z4jZipFile(out, password.toCharArray())
            zf.setRunInThread(true)
            for (f in files) {
                ctl.check()
                if (isLink(f)) continue
                if (f.isDirectory) zf.addFolder(f, params) else zf.addFile(f, params)
                await(zf.getProgressMonitor(), ctl, 100)
            }
        } catch (e: Exception) {
            out.delete()
            throw mapZip(e, "Gagal mengompres")
        }
        return out
    }

    /** Menunggu tugas zip4j (berjalan di thread sendiri), melaporkan persen ke [ctl], dan meneruskan pembatalan. */
    private fun await(pm: ProgressMonitor, ctl: Ctl, weight: Int) {
        var last = 0
        var cancelled = false
        while (pm.getState() == ProgressMonitor.State.BUSY) {
            try { ctl.check() } catch (e: IOException) {
                if (!cancelled) { cancelled = true; pm.setCancelAllTasks(true) }
            }
            val p = pm.getPercentDone()
            if (p > last) { ctl.add((p - last) * weight / 100L); last = p }
            try { Thread.sleep(60) } catch (e: InterruptedException) {
                cancelled = true
                pm.setCancelAllTasks(true)
            }
        }
        if (cancelled || pm.getResult() == ProgressMonitor.Result.CANCELLED) throw IOException("Dibatalkan")
        if (pm.getResult() == ProgressMonitor.Result.ERROR) throw (pm.getException() ?: IOException("Operasi ZIP gagal"))
        if (last < 100) ctl.add((100 - last) * weight / 100L)
    }

    internal fun mapZip(e: Exception, fallback: String): IOException =
        if (e is Z4jException && e.getType() == Z4jException.Type.WRONG_PASSWORD) WrongPassword() else asIo(e, fallback)

    /** [ZIP-AES] True bila [zip] punya entri terenkripsi (butuh kata sandi untuk diekstrak). */
    fun isEncryptedZip(zip: File): Boolean =
        try { zip.isFile && Z4jZipFile(zip).isEncrypted() } catch (e: Exception) { false }

    class ZipEntryInfo(val name: String, val size: Long, val isDir: Boolean)
    class ZipListing(
        val entries: List<ZipEntryInfo>,
        val files: Int,
        val folders: Int,
        val totalSize: Long,
        val encrypted: Boolean,
        val truncated: Boolean
    )

    const val ZIP_LIST_MAX = 5000

    /**
     * Daftar isi [zip] tanpa mengekstrak (ZIP berkata sandi pun bisa karena nama entri tidak dienkripsi).
     * Memakai zip4j, lalu java.util.zip sebagai cadangan. Gagal = IOException berpesan jelas.
     */
    fun listZip(zip: File): ZipListing {
        if (!zip.isFile) throw IOException("Berkas ZIP tidak ditemukan")
        if (zip.length() == 0L) throw IOException("Berkas ZIP kosong (0 byte): pembuatannya mungkin terhenti. Kompres ulang.")
        val items = ArrayList<ZipEntryInfo>()
        var files = 0
        var folders = 0
        var total = 0L
        var enc = false
        var truncated = false
        fun add(name: String, size: Long, dir: Boolean) {
            if (dir) folders++ else { files++; if (size > 0) total += size }
            if (items.size < ZIP_LIST_MAX) items.add(ZipEntryInfo(name, size, dir)) else truncated = true
        }
        try {
            for (h in Z4jZipFile(zip).getFileHeaders()) {
                if (h.isEncrypted()) enc = true
                add(h.getFileName(), h.getUncompressedSize(), h.isDirectory())
            }
        } catch (e: Exception) {
            items.clear(); files = 0; folders = 0; total = 0L; enc = false; truncated = false
            try {
                ZipFile(zip).use { zf -> for (en in zf.entries().toList()) add(en.name, en.size, en.isDirectory) }
            } catch (e2: Exception) {
                throw IOException("ZIP rusak, tidak lengkap, atau bukan berkas ZIP (" + (e.message ?: e.javaClass.simpleName) + ")", e2)
            }
        }
        return ZipListing(items, files, folders, total, enc, truncated)
    }

    private fun addZip(z: ZipOutputStream, f: File, path: String, skip: File, ctl: Ctl) {
        ctl.check()
        if (isLink(f) || f.canonicalFile == skip) return
        if (f.isDirectory) {
            z.putNextEntry(ZipEntry("$path/").apply { time = f.lastModified() })
            z.closeEntry()
            val kids = f.listFiles() ?: return
            for (k in kids.sortedBy { it.name }) addZip(z, k, path + "/" + k.name, skip, ctl)
        } else {
            z.putNextEntry(ZipEntry(path).apply { time = f.lastModified() })
            f.inputStream().use { pump(it, z, ctl) }
            z.closeEntry()
        }
    }

    /** Ekstrak [zip] ke folder baru bernama sama di [destDir]. Entri yang keluar dari folder tujuan (zip-slip) ditolak. */
    fun unzip(zip: File, destDir: File, ctl: Ctl, password: String? = null): File {
        if (!zip.isFile) throw IOException("Berkas ZIP tidak ditemukan")
        if (!destDir.isDirectory || !destDir.canWrite()) throw IOException("Folder tujuan tidak bisa ditulisi")
        if (isEncryptedZip(zip)) return unzipEncrypted(zip, destDir, ctl, password)
        val target = uniqueTarget(destDir, zip.nameWithoutExtension.ifEmpty { "Ekstrak" }, keepExt = false)
        val base = target.canonicalFile
        try {
            ZipFile(zip).use { zf ->
                val entries = zf.entries().toList()
                var total = 0L
                for (en in entries) if (!en.isDirectory && en.size > 0) total += en.size
                ctl.total = total
                if (!target.mkdirs()) throw IOException("Gagal membuat folder " + target.name)
                for (en in entries) {
                    ctl.check()
                    val out = File(target, en.name).canonicalFile
                    if (out.path != base.path && !out.path.startsWith(base.path + File.separator)) {
                        throw IOException("Entri ZIP tidak aman: " + en.name)
                    }
                    if (en.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    zf.getInputStream(en).use { i -> out.outputStream().use { o -> pump(i, o, ctl) } }
                    if (en.time > 0) out.setLastModified(en.time)
                }
            }
        } catch (e: Exception) {
            deleteRec(target)
            throw asIo(e, "Gagal mengekstrak")
        }
        return target
    }

    private fun unzipEncrypted(zip: File, destDir: File, ctl: Ctl, password: String?): File {
        if (password.isNullOrEmpty()) throw NeedPassword()
        val target = uniqueTarget(destDir, zip.nameWithoutExtension.ifEmpty { "Ekstrak" }, keepExt = false)
        val base = target.canonicalFile
        try {
            val zf = Z4jZipFile(zip, password.toCharArray())
            for (h in zf.getFileHeaders()) {
                val out = File(target, h.getFileName()).canonicalFile
                if (out.path != base.path && !out.path.startsWith(base.path + File.separator)) {
                    throw IOException("Entri ZIP tidak aman: " + h.getFileName())
                }
            }
            if (!target.mkdirs()) throw IOException("Gagal membuat folder " + target.name)
            ctl.total = 100
            zf.setRunInThread(true)
            zf.extractAll(target.path)
            await(zf.getProgressMonitor(), ctl, 100)
        } catch (e: Exception) {
            deleteRec(target)
            throw mapZip(e, "Gagal mengekstrak")
        }
        return target
    }

    /**
     * [ZIP-OPEN] Ekstrak SATU entri [entryName] dari [zip] ke cache sementara (bukan seluruh ZIP), lalu kembalikan berkasnya.
     * Entri yang keluar dari folder cache (zip-slip) ditolak. Hasil dipakai ulang bila sudah ada; cache dipangkas ke 300 MB
     * (folder ZIP terlama dihapus lebih dulu). ZIP terenkripsi: tanpa [password] melempar NeedPassword, salah = WrongPassword.
     */
    fun extractEntry(zip: File, entryName: String, cacheRoot: File, password: String?, cancelled: () -> Boolean): File {
        if (!zip.isFile) throw IOException("Berkas ZIP tidak ditemukan")
        val root = File(cacheRoot, "zipview")
        val bucket = File(root, Integer.toHexString((zip.canonicalPath + "|" + zip.lastModified() + "|" + zip.length()).hashCode()))
        val base = bucket.canonicalFile
        val out = File(bucket, entryName).canonicalFile
        if (out.path != base.path && !out.path.startsWith(base.path + File.separator)) {
            throw IOException("Entri ZIP tidak aman: " + entryName)
        }
        if (out.isFile && out.length() > 0L) {
            bucket.setLastModified(System.currentTimeMillis())
            return out
        }
        trimZipCache(root, 300L * 1024L * 1024L, bucket)
        out.parentFile?.mkdirs()
        try {
            if (isEncryptedZip(zip)) {
                if (password.isNullOrEmpty()) throw NeedPassword()
                Z4jZipFile(zip, password.toCharArray()).extractFile(entryName, bucket.path)
            } else {
                ZipFile(zip).use { zf ->
                    val en = zf.getEntry(entryName) ?: throw IOException("Entri tidak ditemukan di ZIP")
                    if (en.isDirectory) throw IOException("Itu folder, bukan berkas")
                    zf.getInputStream(en).use { i ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (cancelled()) throw IOException("Dibatalkan")
                                val n = i.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                            }
                        }
                    }
                    if (en.time > 0) out.setLastModified(en.time)
                }
            }
        } catch (e: Exception) {
            out.delete()
            throw if (e is Z4jException) mapZip(e, "Gagal membuka entri") else asIo(e, "Gagal membuka entri")
        }
        bucket.setLastModified(System.currentTimeMillis())
        return out
    }

    private fun trimZipCache(root: File, maxBytes: Long, keep: File) {
        val dirs = root.listFiles()?.filter { it.isDirectory && it.path != keep.path } ?: return
        var total = dirs.sumOf { cacheSize(it) }
        for (d in dirs.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val s = cacheSize(d)
            deleteRec(d)
            total -= s
        }
    }

    private fun cacheSize(f: File): Long = if (f.isFile) f.length() else (f.listFiles()?.sumOf { cacheSize(it) } ?: 0L)

    /** [ZIP-PREVIEW] Baca satu entri ke memori (maks. [maxBytes]) untuk pratinjau; null bila terlalu besar, hilang, atau butuh kata sandi. */
    fun readEntryBytes(zip: File, entryName: String, password: String?, maxBytes: Int): ByteArray? {
        return try {
            if (isEncryptedZip(zip)) {
                if (password.isNullOrEmpty()) return null
                val zf = Z4jZipFile(zip, password.toCharArray())
                val h = zf.getFileHeader(entryName) ?: return null
                if (h.getUncompressedSize() > maxBytes) return null
                zf.getInputStream(h).use { readCapped(it, maxBytes) }
            } else {
                ZipFile(zip).use { zf ->
                    val en = zf.getEntry(entryName) ?: return null
                    if (en.size > maxBytes) return null
                    zf.getInputStream(en).use { readCapped(it, maxBytes) }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readCapped(i: InputStream, max: Int): ByteArray? {
        val bo = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val n = i.read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            bo.write(buf, 0, n)
        }
        return bo.toByteArray()
    }

    /**
     * [ZIP-SAVE] Ganti isi entri [entryName] di [zip] dengan [src] (hasil suntingan salinan sementara).
     * Cadangan ZIP disimpan di [backupDir]/<nama>.bak; bila gagal, ZIP dipulihkan dari cadangan.
     * ZIP terenkripsi: entri baru dienkripsi AES-256 dengan kata sandi yang sama; tanpa kata sandi melempar NeedPassword.
     */
    fun replaceEntry(zip: File, entryName: String, src: File, password: String?, backupDir: File) {
        if (!zip.isFile || !zip.canWrite()) throw IOException("ZIP tidak bisa ditulisi")
        if (!src.isFile) throw IOException("Salinan sementara tidak ditemukan")
        val enc = isEncryptedZip(zip)
        if (enc && password.isNullOrEmpty()) throw NeedPassword()
        backupDir.mkdirs()
        val bak = File(backupDir, zip.name + ".bak")
        zip.copyTo(bak, overwrite = true)
        try {
            val zf = if (enc) Z4jZipFile(zip, password!!.toCharArray()) else Z4jZipFile(zip)
            val old = zf.getFileHeader(entryName)
            if (enc && old != null) zf.getInputStream(old).use { it.read() } // uji kata sandi sebelum mengubah apa pun
            if (old != null) zf.removeFile(old)
            val params = ZipParameters()
            params.setFileNameInZip(entryName)
            if (enc) {
                params.setEncryptFiles(true)
                params.setEncryptionMethod(EncryptionMethod.AES)
                params.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256)
            }
            zf.addFile(src, params)
        } catch (e: Exception) {
            try { bak.copyTo(zip, overwrite = true) } catch (e2: Exception) { }
            throw mapZip(e, "Gagal menyimpan ke ZIP")
        }
    }

    /** [ZIP-EDIT] Cadangkan ZIP ke [backupDir]/<nama>.bak, jalankan [block]; bila gagal ZIP dipulihkan dari cadangan. */
    private fun <T> zipGuarded(zip: File, backupDir: File, fail: String, block: () -> T): T {
        backupDir.mkdirs()
        val bak = File(backupDir, zip.name + ".bak")
        zip.copyTo(bak, overwrite = true)
        try {
            return block()
        } catch (e: Exception) {
            try { bak.copyTo(zip, overwrite = true) } catch (e2: Exception) { }
            throw mapZip(e, fail)
        }
    }

    /**
     * [ZIP-EDIT] Hapus entri dari [zip]. Nama berakhiran "/" dianggap folder (seluruh isinya ikut dihapus).
     * Mengembalikan jumlah entri yang dihapus. Tidak butuh kata sandi.
     */
    fun deleteEntries(zip: File, names: List<String>, backupDir: File): Int {
        if (!zip.isFile || !zip.canWrite()) throw IOException("ZIP tidak bisa ditulisi")
        if (names.isEmpty()) return 0
        return zipGuarded(zip, backupDir, "Gagal menghapus dari ZIP") {
            val zf = Z4jZipFile(zip)
            val victims = ArrayList<String>()
            for (h in zf.getFileHeaders()) {
                val fn = h.getFileName()
                if (names.any { n -> fn == n || (n.endsWith("/") && fn.startsWith(n)) }) victims.add(fn)
            }
            if (victims.isEmpty()) throw IOException("Entri tidak ditemukan di ZIP")
            zf.removeFiles(victims)
            victims.size
        }
    }

    /**
     * [ZIP-EDIT] Tambahkan [files] (berkas atau folder) ke [zip] di dalam folder [destPrefix] ("" = akar, atau "a/b/").
     * Berkas bernama sama diganti. ZIP terenkripsi: entri baru dienkripsi AES-256 dengan kata sandi yang sama.
     * Mengembalikan jumlah item yang ditambahkan.
     */
    fun addEntries(zip: File, files: List<File>, destPrefix: String, password: String?, backupDir: File): Int {
        if (!zip.isFile || !zip.canWrite()) throw IOException("ZIP tidak bisa ditulisi")
        val skip = zip.canonicalFile
        val src = files.filter { it.exists() && !isLink(it) && it.canonicalFile != skip }
        if (src.isEmpty()) throw IOException("Tidak ada berkas untuk ditambahkan")
        val enc = isEncryptedZip(zip)
        if (enc && password.isNullOrEmpty()) throw NeedPassword()
        return zipGuarded(zip, backupDir, "Gagal menambah ke ZIP") {
            val zf = if (enc) Z4jZipFile(zip, password!!.toCharArray()) else Z4jZipFile(zip)
            if (enc) {
                val probe = zf.getFileHeaders().firstOrNull { !it.isDirectory() }
                if (probe != null) zf.getInputStream(probe).use { it.read() } // uji kata sandi sebelum mengubah apa pun
            }
            var n = 0
            for (f in src) {
                val params = ZipParameters()
                if (enc) {
                    params.setEncryptFiles(true)
                    params.setEncryptionMethod(EncryptionMethod.AES)
                    params.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256)
                }
                params.setExcludeFileFilter(ExcludeFileFilter { x -> isLink(x) || x.canonicalFile == skip })
                if (f.isDirectory) {
                    val root = destPrefix + f.name
                    val stale = zf.getFileHeaders().map { it.getFileName() }.filter { it.startsWith(root + "/") }
                    if (stale.isNotEmpty()) zf.removeFiles(stale)
                    params.setRootFolderNameInZip(root)
                    zf.addFolder(f, params)
                } else {
                    val full = destPrefix + f.name
                    val old = zf.getFileHeader(full)
                    if (old != null) zf.removeFile(old)
                    params.setFileNameInZip(full)
                    zf.addFile(f, params)
                }
                n++
            }
            n
        }
    }

    /**
     * [RUNNER] Interpreter untuk menjalankan [f] di shell: dari shebang (#!), lalu dari ekstensi.
     * Mengembalikan null bila tidak dikenal. Nama dibatasi huruf/angka/._+- agar aman disisipkan ke perintah shell.
     */
    fun runnerFor(f: File): String? {
        if (!f.isFile) return null
        val head = try {
            f.inputStream().use { i ->
                val b = ByteArray(160)
                String(b, 0, maxOf(i.read(b), 0), Charsets.ISO_8859_1)
            }
        } catch (e: Exception) { "" }
        if (head.startsWith("#!")) {
            val toks = head.substring(2).lineSequence().first().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            var name = toks.firstOrNull()?.substringAfterLast('/') ?: ""
            if (name == "env") name = toks.drop(1).firstOrNull { !it.startsWith("-") }?.substringAfterLast('/') ?: ""
            if (name.isNotEmpty() && name.matches(Regex("[A-Za-z0-9._+-]+"))) return name
        }
        return when (f.extension.lowercase()) {
            "sh", "bash" -> "bash"
            "py" -> "python3"
            "js", "mjs", "cjs" -> "node"
            "pl" -> "perl"
            "rb" -> "ruby"
            "php" -> "php"
            "lua" -> "lua"
            else -> null
        }
    }

    /** SHA-256 heksadesimal isi [f]; dibaca bertahap dan bisa dibatalkan lewat [ctl]. */
    fun sha256(f: File, ctl: Ctl): String {
        if (!f.isFile) throw IOException("Bukan berkas biasa")
        ctl.total = f.length()
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                ctl.check()
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                ctl.add(n.toLong())
            }
        }
        return md.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }

    /** Properti [f]; untuk folder ukuran dan isi dihitung rekursif (tautan dihitung sebagai berkas, tidak diikuti). */
    fun props(f: File): Props {
        var size = 0L
        var files = 0
        var folders = 0
        fun walk(d: File) {
            val kids = d.listFiles() ?: return
            for (k in kids) {
                if (isLink(k)) { files++; continue }
                if (k.isDirectory) { folders++; walk(k) } else { files++; size += k.length() }
            }
        }
        val link = isLink(f)
        val dir = f.isDirectory && !link
        if (dir) walk(f) else size = f.length()
        return Props(f, dir, size, files, folders, f.lastModified(), f.canRead(), f.canWrite(), link)
    }

    fun readable(b: Long): String {
        if (b < 1024) return "$b B"
        val u = arrayOf("KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = -1
        do { v /= 1024.0; i++ } while (v >= 1024.0 && i < u.lastIndex)
        return String.format(Locale("id", "ID"), "%.1f %s", v, u[i])
    }
}
