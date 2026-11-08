package com.molina.studio.cinema

import java.io.File
import java.nio.file.Files
import java.util.Locale

/**
 * Daftar isi folder untuk Penjelajah: pencarian nama (satu folder atau sampai subfolder) dan sortir.
 * Murni JVM (tanpa Android/Compose). Semua fungsi blocking: panggil dari Dispatchers.IO.
 */
internal object ExplorerList {
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

    class Item(val file: File, val isDir: Boolean, val size: Long, val modified: Long, val rel: String?)
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

    /** Semua kata kunci (dipisah spasi) harus ada di nama, tanpa membedakan huruf besar/kecil. Kueri kosong cocok semuanya. */
    fun matches(name: String, query: String): Boolean = hit(name, words(query))

    /**
     * Isi [dir] dengan filter nama [query]. Tanpa [Opts.deep] (atau kueri kosong) hanya satu folder; dengan deep mencari
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
            val d = f.isDirectory
            return Item(f, d, if (d) 0L else f.length(), f.lastModified(), rel)
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
            for (f in kids) {
                scanned++
                if (scanned > MAX_SCANNED) { truncated = true; break@loop }
                if (!visible(f)) continue
                if (f.isDirectory && !isLink(f) && depth < MAX_DEPTH) queue.add(Pair(f, depth + 1))
                if (extOk(f) && hit(f.name, w)) {
                    if (out.size >= MAX_RESULTS) { truncated = true; break@loop }
                    val rel = f.parentFile?.path.orEmpty().removePrefix(dir.path).trim('/')
                    out.add(item(f, rel.ifEmpty { null }))
                }
            }
        }
        return Result(sort(out, o), truncated)
    }

    /** Urut sesuai [Opts]: folder di atas (opsional), kunci sortir, menaik/menurun. Folder diurut per nama bila kuncinya ukuran. */
    fun sort(items: List<Item>, o: Opts): List<Item> {
        fun cmp(k: Key): Comparator<Item> = when (k) {
            Key.NAME -> compareBy<Item> { it.file.name.lowercase(Locale.ROOT) }
            Key.DATE -> compareBy<Item> { it.modified }
            Key.SIZE -> compareBy<Item> { it.size }
            Key.TYPE -> compareBy<Item> { it.file.extension.lowercase(Locale.ROOT) }.thenBy { it.file.name.lowercase(Locale.ROOT) }
        }
        fun ordered(l: List<Item>, k: Key): List<Item> {
            val s = l.sortedWith(cmp(k))
            return if (o.asc) s else s.reversed()
        }
        if (!o.foldersFirst) return ordered(items, o.key)
        val dirKey = if (o.key == Key.SIZE) Key.NAME else o.key
        return ordered(items.filter { it.isDir }, dirKey) + ordered(items.filter { !it.isDir }, o.key)
    }

    /** Heuristik teks: berkas biasa, maks 5 MB, 4 KB pertama tanpa byte NUL. */
    fun looksText(f: File): Boolean {
        if (!f.isFile || f.length() > 5_000_000L) return false
        return try {
            f.inputStream().use { s ->
                val b = ByteArray(4096)
                val n = s.read(b)
                (0 until maxOf(n, 0)).none { b[it] == 0.toByte() }
            }
        } catch (e: Exception) {
            false
        }
    }
}
