package com.molina.studio.cinema

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale

/**
 * [ARCHIVE4] Format yang dibuka lewat 7z di Debian PRoot (bukan commons-compress). Murni (tanpa Android) supaya
 * bisa diuji JVM. ZIP/7z/tar tetap dikerjakan [ArchiveEngine]; kelas ini hanya untuk yang tidak dikuasainya.
 */
internal object DebianFormats {
    class Info(val label: String, val firstVolume: Boolean, val stem: String)

    const val NOT_FIRST_HINT = "Ini bukan bagian pertama arsip. Pilih bagian pertama (.001 atau .part1.rar)."

    private val SINGLE = Regex("""^(.+)\.(rar|iso|cab|wim|arj|lzh|lha|cpio|deb|rpm|dmg)$""", RegexOption.IGNORE_CASE)
    private val VOLUME = Regex("""^(.+)\.(7z|zip|rar)\.(\d{3})$""", RegexOption.IGNORE_CASE)
    private val PART_RAR = Regex("""^(.+)\.part(\d+)\.rar$""", RegexOption.IGNORE_CASE)

    fun detect(name: String): Info? {
        PART_RAR.matchEntire(name)?.let { m ->
            val n = m.groupValues[2].toIntOrNull() ?: return null
            return Info("RAR", n == 1, m.groupValues[1])
        }
        VOLUME.matchEntire(name)?.let { m ->
            val n = m.groupValues[3].toIntOrNull() ?: return null
            return Info(m.groupValues[2].uppercase(Locale.ROOT), n == 1, m.groupValues[1])
        }
        SINGLE.matchEntire(name)?.let { m ->
            return Info(m.groupValues[2].uppercase(Locale.ROOT), true, m.groupValues[1])
        }
        return null
    }

    fun supports(name: String): Boolean = detect(name) != null

    /** Nama subfolder tujuan ("a.part1.rar" -> "a"); "Ekstrak" bila kosong. */
    fun folderName(name: String): String = detect(name)?.stem?.ifEmpty { null } ?: ArchiveNames.folderName(name)
}

/**
 * [ARCHIVE4] Staging hasil ekstrak 7z. Hasil ditulis dulu ke folder tersembunyi `.molina-stage-*` DI FOLDER TUJUAN
 * (satu volume, jadi pemindahan akhir hanya rename dan tidak butuh ruang ganda), diaudit, baru dipindah. Bila gagal atau
 * dibatalkan, tujuan tidak berubah. Berkas yang sudah ada TIDAK ditimpa ("nama (1).ext"), sama seperti [ArchiveEngine].
 *
 * Penghapusan TIDAK mengikuti tautan simbolik (File.deleteRecursively milik Kotlin mengikuti tautan ke folder).
 */
internal object DebianArchiveStaging {
    const val PREFIX = ".molina-stage-"
    const val STALE_MS = 6L * 60 * 60 * 1000
    const val MIN_FREE = 8L * 1024 * 1024

    private fun isLink(f: File) = Files.isSymbolicLink(f.toPath())
    private fun taken(f: File) = Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)
    private fun isRealDir(f: File) = Files.isDirectory(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    /** Hapus [f] beserta isinya tanpa mengikuti tautan. */
    fun deleteTree(f: File) {
        if (!taken(f)) return
        if (!isLink(f) && f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
        f.delete()
    }

    fun create(parent: File, nonce: Long = System.nanoTime()): File {
        if (!parent.isDirectory) throw IOException("Folder tujuan tidak ditemukan")
        val d = File(parent, PREFIX + java.lang.Long.toUnsignedString(nonce, 36))
        if (taken(d)) throw IOException("Folder sementara sudah ada")
        if (!d.mkdir()) throw IOException("Gagal membuat folder sementara di " + parent.name)
        return d
    }

    /** Buang sisa staging lama (proses mati di tengah ekstrak). Mengembalikan jumlah yang dibuang. */
    fun sweepStale(parent: File, nowMs: Long = System.currentTimeMillis(), maxAgeMs: Long = STALE_MS): Int {
        var n = 0
        for (c in parent.listFiles() ?: return 0) {
            if (!c.name.startsWith(PREFIX) || isLink(c) || !c.isDirectory) continue
            if (nowMs - c.lastModified() > maxAgeMs) { deleteTree(c); n++ }
        }
        return n
    }

    /** Pesan galat bila ruang di [dest] kurang untuk [expectedBytes] (0 = tidak diketahui), atau null. */
    fun roomError(dest: File, expectedBytes: Long): String? {
        val free = dest.usableSpace
        val need = if (expectedBytes > 0) expectedBytes + MIN_FREE else MIN_FREE
        return if (free < need) "Ruang penyimpanan tidak cukup" else null
    }

    class Audit(val files: Int, val dirs: Int, val bytes: Long, val removed: Int)

    /**
     * Periksa isi [stage]: tautan simbolik dan berkas khusus dihapus (tidak diikuti), dan setiap jalur kanonik harus
     * tetap di dalam [stage]. Dipanggil SESUDAH 7z selesai sebagai lapis kedua di atas perlindungan 7z sendiri.
     */
    fun audit(stage: File): Audit {
        if (isLink(stage) || !stage.isDirectory) throw IOException("Folder sementara tidak valid")
        val root = stage.canonicalFile
        val prefix = root.path + File.separator
        var files = 0
        var dirs = 0
        var bytes = 0L
        var removed = 0
        fun walk(d: File) {
            val kids = d.listFiles() ?: throw IOException("Gagal membaca hasil ekstrak")
            for (k in kids) {
                val p = k.toPath()
                if (Files.isSymbolicLink(p)) { k.delete(); removed++; continue }
                val isDir = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
                val isFile = Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                if (!isDir && !isFile) { k.delete(); removed++; continue }
                if (!k.canonicalFile.path.startsWith(prefix)) throw IOException("Hasil ekstrak keluar dari folder sementara")
                if (isDir) { dirs++; walk(k) } else { files++; bytes += k.length() }
            }
        }
        walk(root)
        return Audit(files, dirs, bytes, removed)
    }

    class Committed(val base: File, val files: Int, val renamed: Int)

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        if (!taken(f)) return f
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            f = File(dir, "$stem ($i)$ext")
            if (!taken(f)) return f
            i++
        }
    }

    private fun countFiles(f: File): Int {
        if (isLink(f)) return 0
        if (!f.isDirectory) return 1
        var n = 0
        for (c in f.listFiles() ?: return 0) n += countFiles(c)
        return n
    }

    /**
     * Pindahkan isi [stage] (harus anak langsung [dest]) ke [dest], atau ke subfolder baru [namedFolder]. Folder
     * yang sama digabung; berkas bentrok diberi nama baru. Gagal atau [cancelled] = semua yang sudah dipindah dibatalkan.
     */
    fun commit(stage: File, dest: File, namedFolder: String?, cancelled: () -> Boolean): Committed {
        val d = dest.canonicalFile
        if (stage.canonicalFile.parentFile != d) throw IOException("Folder sementara harus berada di folder tujuan")
        val created = ArrayList<File>()
        var files = 0
        var renamed = 0
        val base = if (namedFolder != null) unique(d, namedFolder) else d
        fun move(src: File, dst: File) {
            val kids = (src.listFiles() ?: throw IOException("Gagal membaca hasil ekstrak")).sortedBy { it.name }
            for (k in kids) {
                if (cancelled()) throw IOException("Dibatalkan")
                val dir = isRealDir(k)
                var t = File(dst, k.name)
                if (dir && isRealDir(t)) { move(k, t); k.delete(); continue }
                if (taken(t)) { t = unique(dst, k.name); renamed++ }
                if (!k.renameTo(t)) throw IOException("Gagal memindahkan hasil ekstrak: " + k.name)
                created.add(t)
                files += if (dir) countFiles(t) else 1
            }
        }
        try {
            if (namedFolder != null) {
                if (!base.mkdir()) throw IOException("Gagal membuat folder " + base.name)
                created.add(base)
            }
            move(stage, base)
        } catch (e: Exception) {
            for (f in created.asReversed()) deleteTree(f)
            throw e
        }
        return Committed(base, files, renamed)
    }

    /**
     * [ARCHIVE6] Pindahkan SATU berkas hasil kompres [produced] (anak langsung [stage]) ke [dest] sebagai `base + ext`,
     * atau `base (1)+ext`, ... bila nama sudah ada. Berkas yang ada TIDAK pernah ditimpa: pemindahan memakai
     * [Files.move] tanpa REPLACE_EXISTING dan mengulang dengan nama berikutnya bila ada yang menyela. Mengembalikan berkas akhir.
     */
    fun commitFile(stage: File, produced: String, dest: File, base: String, ext: String, cancelled: () -> Boolean): File {
        val d = dest.canonicalFile
        if (stage.canonicalFile.parentFile != d) throw IOException("Folder sementara harus berada di folder tujuan")
        val src = File(stage, produced)
        val sp = src.toPath()
        if (Files.isSymbolicLink(sp) || !Files.isRegularFile(sp, LinkOption.NOFOLLOW_LINKS)) throw IOException("Hasil kompres tidak ditemukan")
        if (src.length() == 0L) throw IOException("Hasil kompres kosong")
        var tries = 0
        while (true) {
            if (cancelled()) throw IOException("Dibatalkan")
            val target = CompressNames.uniqueOutput(d, base, ext)
            try {
                Files.move(sp, target.toPath())
                return target
            } catch (e: FileAlreadyExistsException) {
                if (++tries > 100) throw IOException("Gagal memberi nama hasil kompres")
            } catch (e: IOException) {
                throw IOException("Gagal memindahkan hasil kompres: " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    const val MAX_LINK_EXCLUDES = 2000
    const val TOO_MANY_LINKS = "Sumber memuat terlalu banyak tautan simbolik (lebih dari 2000). Hapus tautannya atau pakai format lain."

    /**
     * [ARCHIVE6] Jalur dalam arsip (`nama/sub/tautan`) dari SEMUA tautan simbolik di dalam [sources] (folder; tautan tingkat atas
     * sudah disaring pemanggil). Penelusuran TIDAK mengikuti tautan. Dilewatkan ke 7z sebagai `-x!` supaya tautan dilewati seperti
     * di [ArchiveEngine]: 7z sendiri mengikuti tautan folder, dan arsip berisi tautan absolut gagal diekstrak ("Dangerous link").
     */
    fun symlinkExcludes(sources: List<File>, max: Int = MAX_LINK_EXCLUDES, cancelled: () -> Boolean = { false }): List<String> {
        val out = ArrayList<String>()
        val todo = ArrayDeque<Pair<File, String>>()
        for (s in sources) if (Files.isDirectory(s.toPath(), LinkOption.NOFOLLOW_LINKS)) todo.addLast(s to s.name)
        while (todo.isNotEmpty()) {
            val (dir, rel) = todo.removeLast()
            val kids = dir.listFiles() ?: continue // tak terbaca: 7z akan memperingatkan (exit 1) dan kompres gagal
            for (k in kids) {
                if (cancelled()) throw IOException("Dibatalkan")
                val r = rel + "/" + k.name
                val p = k.toPath()
                if (Files.isSymbolicLink(p)) {
                    out.add(r)
                    if (out.size > max) throw IOException(TOO_MANY_LINKS)
                } else if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    todo.addLast(k to r)
                }
            }
        }
        return out
    }
}
