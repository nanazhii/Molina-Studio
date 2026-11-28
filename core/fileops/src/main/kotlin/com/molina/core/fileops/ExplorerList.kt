package com.molina.core.fileops

import java.io.File
import java.nio.file.Files
import java.util.Locale

/**
 * Daftar isi folder untuk Penjelajah: pencarian nama (satu folder atau sampai subfolder) dan sortir.
 * Murni JVM (tanpa Android/Compose). Semua fungsi blocking: panggil dari Dispatchers.IO.
 */
object ExplorerList {
    enum class Key { NAME, DATE, SIZE, TYPE }

    class Opts(
        val key: Key = Key.NAME,
        val asc: Boolean = true,
        val foldersFirst: Boolean = true,
        val showHidden: Boolean = false,
        val deep: Boolean = false,
        /** Ekstensi huruf kecil yang ditampilkan untuk berkas (folder selalu tampil); null = semua. */
        val onlyExt: Set<String>? = null
    )

    /** [PERF-LIST] nameKey/extKey dihitung sekali per item (bukan di setiap perbandingan sortir). */
    class Item(val file: File, val isDir: Boolean, val size: Long, val modified: Long, val rel: String?) {
        val nameKey: String = file.name.lowercase(Locale.ROOT)
        val extKey: String = file.extension.lowercase(Locale.ROOT)
    }
    class Result(val items: List<Item>, val truncated: Boolean)

    const val MAX_RESULTS = 500
    private const val MAX_SCANNED = 20_000
    private const val MAX_DEPTH = 12

    private fun isLink(f: File): Boolean = try { Files.isSymbolicLink(f.toPath()) } catch (e: Exception) { false }

    private fun words(query: String): List<String> =
        query.trim().lowercase(Locale.ROOT).split(' ').filter { it.isNotEmpty() }

    private fun hit(name: String, w: List<String>): Boolean {
        if (w.isEmpty()) return true
        val n = name.lowercase(Locale.ROOT)
        return w.all { n.contains(it) }
    }

    /**
     * [SEARCH-PATH] Mode rekursif: tiap kata harus ada di nama ATAU di path relatif folder induk, dan minimal satu kata
     * harus ada di nama itu sendiri (supaya mengetik nama folder tidak membanjiri hasil dengan seluruh isinya).
     * Contoh: "feature kts" cocok dengan feature/explorer/build.gradle.kts. [relLower] sudah huruf kecil.
     */
    private fun hitPath(name: String, relLower: String, w: List<String>): Boolean {
        if (w.isEmpty()) return true
        val n = name.lowercase(Locale.ROOT)
        var nameHit = false
        for (k in w) {
            if (n.contains(k)) nameHit = true else if (!relLower.contains(k)) return false
        }
        return nameHit
    }

    /** Semua kata kunci (dipisah spasi) harus ada di nama, tanpa membedakan huruf besar/kecil. Kueri kosong cocok semuanya. */
    fun matches(name: String, query: String): Boolean = hit(name, words(query))

    /**
     * Isi [dir] dengan filter [query] (nama; mode deep juga path relatif, lihat [hitPath]). Tanpa [Opts.deep] (atau kueri kosong) hanya satu folder; dengan deep mencari
     * sampai subfolder (lebar-dahulu, maks [MAX_RESULTS] hasil, [MAX_SCANNED] entri, kedalaman [MAX_DEPTH], tautan tidak diikuti).
     * Mengembalikan null bila folder tidak bisa dibaca. [cancelled] true = berhenti dan kembalikan hasil sejauh ini.
     */
    fun list(dir: File, query: String, o: Opts, cancelled: () -> Boolean = { false }): Result? {
        val w = words(query)
        val top = dir.listFiles() ?: return null
        fun visible(f: File) = o.showHidden || !f.name.startsWith(".")
        fun extOk(f: File): Boolean {
            val only = o.onlyExt ?: return true
            return f.isDirectory || f.extension.lowercase(Locale.ROOT) in only
        }
        fun item(f: File, rel: String?): Item {
            // [PERF-LIST] satu stat (readAttributes) menggantikan isDirectory + length + lastModified (3 syscall per berkas)
            return try {
                val a = Files.readAttributes(f.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
                val d = a.isDirectory
                Item(f, d, if (d) 0L else a.size(), a.lastModifiedTime().toMillis(), rel)
            } catch (e: Exception) {
                val d = f.isDirectory
                Item(f, d, if (d) 0L else f.length(), f.lastModified(), rel)
            }
        }
        val out = ArrayList<Item>()
        if (!o.deep || w.isEmpty()) {
            for (f in top) {
                if (visible(f) && extOk(f) && hit(f.name, w)) out.add(item(f, null))
            }
            return Result(sort(out, o), false)
        }
        var truncated = false
        var scanned = 0
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(Pair(dir, 0))
        loop@ while (queue.isNotEmpty()) {
            if (cancelled()) break
            val (d, depth) = queue.removeFirst()
            val kids = (if (d == dir) top else d.listFiles()) ?: continue
            val relDir = d.path.removePrefix(dir.path).trim('/') // [SEARCH-PATH] sekali per folder, bukan per berkas
            val relLower = relDir.lowercase(Locale.ROOT)
            for (f in kids) {
                scanned++
                if (scanned > MAX_SCANNED) { truncated = true; break@loop }
                if (!visible(f)) continue
                if (f.isDirectory && !isLink(f) && depth < MAX_DEPTH) queue.add(Pair(f, depth + 1))
                if (extOk(f) && hitPath(f.name, relLower, w)) {
                    if (out.size >= MAX_RESULTS) { truncated = true; break@loop }
                    out.add(item(f, relDir.ifEmpty { null }))
                }
            }
        }
        return Result(sort(out, o), truncated)
    }

    /** Urut sesuai [Opts]: folder di atas (opsional), kunci sortir, menaik/menurun. Folder diurut per nama bila kuncinya ukuran. */
    fun sort(items: List<Item>, o: Opts): List<Item> {
        fun cmp(k: Key): Comparator<Item> = when (k) {
            Key.NAME -> compareBy<Item> { it.nameKey } // [PERF-LIST] sort
            Key.DATE -> compareBy<Item> { it.modified }
            Key.SIZE -> compareBy<Item> { it.size }
            Key.TYPE -> compareBy<Item> { it.extKey }.thenBy { it.nameKey }
        }
        fun ordered(l: List<Item>, k: Key): List<Item> {
            val s = l.sortedWith(cmp(k))
            return if (o.asc) s else s.reversed()
        }
        if (!o.foldersFirst) return ordered(items, o.key)
        val dirKey = if (o.key == Key.SIZE) Key.NAME else o.key
        return ordered(items.filter { it.isDir }, dirKey) + ordered(items.filter { !it.isDir }, o.key)
    }

    /** Heuristik teks. [FILE-CONTRACT] Implementasi tunggal di :core:files (FileTypes.looksText). */
    fun looksText(f: File): Boolean = com.molina.core.files.FileTypes.looksText(f)
}
