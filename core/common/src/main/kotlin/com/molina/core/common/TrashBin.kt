package com.molina.core.common

import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Properties

/**
 * Satu item di Recycle Bin. [dir] = folder item (`<volume>/.molina-trash/<id>`); [payload] = berkas atau
 * folder yang dihapus (di dalam `files/`, memakai nama aslinya).
 */
class TrashItem(
    val id: String,
    val name: String,
    val origin: File,
    val deletedAt: Long,
    val size: Long,
    val isDir: Boolean,
    val dir: File,
) {
    val payload: File get() = File(File(dir, "files"), name)

    /** Sisa hari sebelum dibersihkan otomatis (minimal 0). */
    fun daysLeft(now: Long, retainDays: Int = TrashBin.RETAIN_DAYS): Int {
        val used = ((now - deletedAt) / TrashBin.DAY_MS).coerceAtLeast(0L)
        return (retainDays - used).coerceAtLeast(0L).toInt()
    }
}

/**
 * Recycle Bin Molina (JVM murni, tanpa Android). Tiap volume punya `.molina-trash/` sendiri sehingga
 * pemindahan memakai rename pada volume yang sama: instan dan atomik. Satu item = satu folder
 * `<id>/` berisi `info.properties` (UTF-8) dan `files/<nama asli>`. Semua fungsi blocking: panggil dari IO.
 */
object TrashBin {
    const val DIR = ".molina-trash"
    const val RETAIN_DAYS = 30
    internal const val DAY_MS = 24L * 60 * 60 * 1000
    private const val INFO = "info.properties"
    private const val FILES = "files"
    private const val NOMEDIA = ".nomedia"

    private fun isLink(f: File): Boolean = try { Files.isSymbolicLink(f.toPath()) } catch (e: Exception) { false }
    private fun present(f: File): Boolean = f.exists() || isLink(f)

    private fun canon(f: File): String? = try { f.canonicalPath } catch (e: IOException) { null }

    /** Jalur kanonik berkas tanpa mengikuti tautan simbolik pada berkas itu sendiri. */
    private fun place(f: File): String? {
        val parent = f.absoluteFile.parentFile ?: return null
        val p = canon(parent) ?: return null
        return if (p.endsWith(File.separator)) p + f.name else p + File.separator + f.name
    }

    /** Folder Recycle Bin pada volume terdalam yang memuat [file]; null bila [file] di luar semua volume atau volume itu sendiri. */
    fun rootFor(file: File, volumes: List<File>): File? {
        val c = place(file) ?: return null
        var best: String? = null
        for (v in volumes) {
            val vp = canon(v) ?: continue
            if (c.startsWith(vp + File.separator) && (best == null || vp.length > best.length)) best = vp
        }
        return best?.let { File(it, DIR) }
    }

    /** True bila [file] berada di dalam (atau adalah) folder Recycle Bin. */
    fun isInTrash(file: File): Boolean {
        var f: File? = file.absoluteFile
        while (f != null) {
            if (f.name == DIR) return true
            f = f.parentFile
        }
        return false
    }

    private fun sizeOf(f: File): Long {
        if (isLink(f)) return 0L
        if (!f.isDirectory) return f.length()
        var s = 0L
        f.listFiles()?.forEach { s += sizeOf(it) }
        return s
    }

    private fun deleteTree(f: File, check: () -> Unit): Boolean {
        check()
        if (!isLink(f) && f.isDirectory) {
            f.listFiles()?.forEach { if (!deleteTree(it, check)) return false }
        }
        return f.delete()
    }

    private fun uniqueIn(dir: File, name: String, keepExt: Boolean): File {
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

    private fun validName(n: String): Boolean =
        n.isNotEmpty() && n != "." && n != ".." && !n.contains('/') && !n.contains('\u0000') && n.length <= 255

    private fun writeInfo(item: TrashItem) {
        val p = Properties()
        p.setProperty("v", "1")
        p.setProperty("name", item.name)
        p.setProperty("origin", item.origin.path)
        p.setProperty("at", item.deletedAt.toString())
        p.setProperty("size", item.size.toString())
        p.setProperty("dir", item.isDir.toString())
        File(item.dir, INFO).outputStream().use { o ->
            val w = OutputStreamWriter(o, StandardCharsets.UTF_8)
            p.store(w, null)
            w.flush()
        }
    }

    private fun readItem(itemDir: File): TrashItem? {
        return try {
            val p = Properties()
            File(itemDir, INFO).inputStream().use { i -> p.load(InputStreamReader(i, StandardCharsets.UTF_8)) }
            val name = p.getProperty("name") ?: return null
            if (!validName(name)) return null
            val origin = p.getProperty("origin") ?: return null
            val at = p.getProperty("at")?.toLongOrNull() ?: return null
            val item = TrashItem(
                id = itemDir.name, name = name, origin = File(origin), deletedAt = at,
                size = p.getProperty("size")?.toLongOrNull() ?: 0L,
                isDir = p.getProperty("dir") == "true", dir = itemDir,
            )
            if (present(item.payload)) item else null
        } catch (e: Exception) {
            null
        }
    }

    private fun trashOne(f: File, root: File, now: Long): TrashItem {
        val isDir = !isLink(f) && f.isDirectory
        val size = sizeOf(f)
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Recycle Bin tidak bisa dibuat (periksa izin tulis)")
        runCatching { File(root, NOMEDIA).createNewFile() }
        var n = 0
        var itemDir = File(root, "$now-$n")
        while (!itemDir.mkdir()) {
            if (++n > 10_000) throw IOException("Recycle Bin tidak bisa dibuat (periksa izin tulis)")
            itemDir = File(root, "$now-$n")
        }
        val filesDir = File(itemDir, FILES)
        if (!filesDir.mkdir()) {
            itemDir.delete()
            throw IOException("Recycle Bin tidak bisa dibuat (periksa izin tulis)")
        }
        val payload = File(filesDir, f.name)
        if (!f.renameTo(payload)) {
            filesDir.delete(); itemDir.delete()
            throw IOException("Gagal memindahkan \"" + f.name + "\" ke Recycle Bin")
        }
        val item = TrashItem(itemDir.name, f.name, f.absoluteFile, now, size, isDir, itemDir)
        try {
            writeInfo(item)
        } catch (e: Exception) {
            payload.renameTo(f)
            deleteTree(itemDir) {}
            throw IOException("Gagal mencatat item Recycle Bin")
        }
        return item
    }

    /**
     * Pindahkan [files] ke Recycle Bin volume masing-masing. Semua-atau-tidak-sama-sekali: semua item divalidasi
     * dulu; bila ada yang gagal di tengah, yang sudah dipindah dikembalikan. Jalur di [guard] ditolak.
     */
    fun trash(files: List<File>, volumes: List<File>, guard: Set<String>, now: Long = System.currentTimeMillis()): List<TrashItem> {
        for (f in files) if (f.absolutePath in guard) throw IOException("Folder ini dilindungi dan tidak bisa dihapus: " + f.name)
        for (f in files) {
            if (!present(f)) throw IOException("Sumber tidak ditemukan: " + f.name)
            if (isInTrash(f)) throw IOException("\"" + f.name + "\" sudah berada di Recycle Bin")
            if (rootFor(f, volumes) == null) {
                throw IOException("Lokasi \"" + f.name + "\" tidak mendukung Recycle Bin. Gunakan Hapus permanen.")
            }
        }
        val out = ArrayList<TrashItem>()
        try {
            for (f in files) out.add(trashOne(f, rootFor(f, volumes)!!, now))
        } catch (e: Exception) {
            runCatching { restore(out, volumes) }
            throw if (e is IOException) e else IOException(e.message ?: "Gagal memindahkan ke Recycle Bin")
        }
        return out
    }

    /** Semua item yang sah di semua volume, terbaru dulu. Folder rusak/yatim diabaikan (dibersihkan oleh [empty]). */
    fun list(volumes: List<File>): List<TrashItem> {
        val seen = HashSet<String>()
        val out = ArrayList<TrashItem>()
        for (v in volumes) {
            val vp = canon(v) ?: continue
            if (!seen.add(vp)) continue
            File(vp, DIR).listFiles()?.forEach { d -> if (d.isDirectory) readItem(d)?.let { out.add(it) } }
        }
        out.sortByDescending { it.deletedAt }
        return out
    }

    /** Kembalikan [items] ke lokasi asal (folder asal dibuat bila hilang; nama bentrok diberi " (1)"). */
    fun restore(items: List<TrashItem>, volumes: List<File>): List<File> {
        val out = ArrayList<File>()
        for (item in items) {
            if (item.dir.parentFile?.name != DIR) throw IOException("Item bukan bagian dari Recycle Bin")
            val parent = item.origin.parentFile ?: throw IOException("Lokasi asal tidak valid: " + item.name)
            val inside = volumes.any { v ->
                val vp = canon(v)
                val pp = canon(parent)
                vp != null && pp != null && (pp == vp || pp.startsWith(vp + File.separator))
            }
            if (!inside) throw IOException("Lokasi asal di luar penyimpanan yang dikenal: " + item.name)
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Folder asal tidak bisa dibuat: " + parent.path)
            val target = uniqueIn(parent, item.name, keepExt = !item.isDir)
            if (!item.payload.renameTo(target)) throw IOException("Gagal memulihkan \"" + item.name + "\"")
            deleteTree(item.dir) {}
            out.add(target)
        }
        return out
    }

    /** Hapus permanen [items] dari Recycle Bin. Mengembalikan jumlah yang terhapus. */
    fun purge(items: List<TrashItem>, check: () -> Unit = {}): Int {
        var n = 0
        for (item in items) {
            if (item.dir.parentFile?.name != DIR) continue
            if (deleteTree(item.dir, check)) n++
        }
        return n
    }

    /** Kosongkan seluruh Recycle Bin (termasuk folder rusak/yatim). Mengembalikan jumlah entri yang dihapus. */
    fun empty(volumes: List<File>, check: () -> Unit = {}): Int {
        val seen = HashSet<String>()
        var n = 0
        for (v in volumes) {
            val vp = canon(v) ?: continue
            if (!seen.add(vp)) continue
            File(vp, DIR).listFiles()?.forEach { d ->
                if (d.name != NOMEDIA && deleteTree(d, check)) n++
            }
        }
        return n
    }

    /** Bersihkan item yang lebih tua dari [days] hari. Jam mundur (deletedAt di masa depan) tidak menghapus apa pun. */
    fun autoPurge(volumes: List<File>, now: Long = System.currentTimeMillis(), days: Int = RETAIN_DAYS): Int {
        val old = list(volumes).filter { now - it.deletedAt > days * DAY_MS }
        return purge(old)
    }

    /** Total byte isi Recycle Bin. */
    fun usage(volumes: List<File>): Long = list(volumes).sumOf { it.size }
}
