package com.molina.core.common

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Model "Mode TV" untuk penjelajah berkas Molina Cinema (SIDE-5): folder sebagai saluran bernomor,
 * isi folder sebagai baris-baris ala rak streaming, dan teks panel info ala panduan acara (EPG).
 * Murni JVM (tanpa Compose/Android) supaya bisa diuji; tidak melempar pada folder hilang/tak terbaca.
 */
object TvGuide {
    const val MAX_ENTRIES = 500
    const val ROW_MAX = 12
    const val MAX_SHELVES = 12
    const val MAX_SHELF_SCAN = 40

    private val LOCALE = Locale("id", "ID")
    private val AUDIO = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "ac3", "dts", "mka")

    enum class Kind { Folder, Video, Audio, Other }

    class Channel(val number: Int, val label: String, val dir: File)

    class Item(val file: File, val kind: Kind, val size: Long, val modified: Long) {
        val name: String get() = file.name
        val title: String get() = if (kind == Kind.Folder) file.name else file.nameWithoutExtension
    }

    /**
     * Satu baris rak. [folder] terisi untuk baris rak kanal (isi satu subfolder, judul = nama folder); null untuk
     * baris biasa (Folder/Video/Audio/Lainnya). [key] unik walau folder kanal bernama sama dengan baris biasa.
     */
    class Row(val title: String, val items: List<Item>, val total: Int, val folder: File? = null) {
        val shelf: Boolean get() = folder != null
        val key: String get() = (if (folder != null) "k:" else "r:") + title

        /** Video terbaru di baris ini (baris sudah urut terbaru dulu); dipakai sebagai sampul kanal. Null bila tak ada video. */
        val coverFile: File? get() = items.firstOrNull { it.kind == Kind.Video }?.file
    }

    /** Beri nomor 1..n pada tempat yang foldernya ada; yang tidak ada dilewati tanpa melubangi nomor. */
    fun channels(places: List<Pair<String, File>>): List<Channel> =
        places.filter { it.second.isDirectory }.mapIndexed { i, p -> Channel(i + 1, p.first, p.second) }

    fun kindOf(f: File): Kind {
        if (f.isDirectory) return Kind.Folder
        val ext = f.extension.lowercase()
        return when {
            ext in ShelfScan.VIDEO_EXTS -> Kind.Video
            ext in AUDIO -> Kind.Audio
            else -> Kind.Other
        }
    }

    /**
     * Baris untuk isi [dir]: Folder (urut nama), lalu Video, Audio, Lainnya (terbaru dulu). Berkas
     * tersembunyi dilewati, penelusuran dibatasi [MAX_ENTRIES], tiap baris dipotong [perRow] item
     * (`total` menyimpan jumlah sebenarnya untuk label "lihat semua"). Baris kosong dibuang.
     */
    fun rows(dir: File, perRow: Int = ROW_MAX): List<Row> {
        val kids = try { dir.listFiles() } catch (e: SecurityException) { null } ?: return emptyList()
        val items = kids.asSequence().filter { !it.name.startsWith(".") }.take(MAX_ENTRIES)
            .map { Item(it, kindOf(it), if (it.isFile) it.length() else 0L, it.lastModified()) }.toList()
        val cap = perRow.coerceAtLeast(1)
        fun row(title: String, kind: Kind, folderOrder: Boolean): Row? {
            val all = items.filter { it.kind == kind }
            if (all.isEmpty()) return null
            val sorted = if (folderOrder) all.sortedBy { it.name.lowercase() } else all.sortedByDescending { it.modified }
            return Row(title, sorted.take(cap), all.size)
        }
        return listOfNotNull(
            row("Folder", Kind.Folder, true),
            row("Video", Kind.Video, false),
            row("Audio", Kind.Audio, false),
            row("Lainnya", Kind.Other, false),
        )
    }

    /**
     * Rak per kanal: tiap subfolder [dir] yang berisi video (langsung di dalamnya) menjadi satu baris berjudul
     * nama folder, video terbaru dulu, baris dengan video terbaru di depan. Cocok dengan `molina_dl --per-channel`
     * dan tidak butuh metadata. Subfolder tersembunyi/tanpa video dilewati; penelusuran dibatasi
     * [MAX_SHELF_SCAN] folder dan hasil [maxShelves] baris. Tidak melempar.
     */
    fun shelves(dir: File, perRow: Int = ROW_MAX, maxShelves: Int = MAX_SHELVES): List<Row> {
        val kids = try { dir.listFiles() } catch (e: SecurityException) { null } ?: return emptyList()
        val cap = perRow.coerceAtLeast(1)
        val out = ArrayList<Pair<Long, Row>>()
        var scanned = 0
        val subs = kids.asSequence().filter { !it.name.startsWith(".") && it.isDirectory }.take(MAX_ENTRIES)
            .sortedByDescending { it.lastModified() }
        for (sub in subs) {
            if (++scanned > MAX_SHELF_SCAN) break
            val files = try { sub.listFiles() } catch (e: SecurityException) { null } ?: continue
            val vids = files.asSequence()
                .filter { !it.name.startsWith(".") && it.isFile && it.extension.lowercase() in ShelfScan.VIDEO_EXTS }
                .take(MAX_ENTRIES).map { Item(it, Kind.Video, it.length(), it.lastModified()) }.toList()
            if (vids.isEmpty()) continue
            val sorted = vids.sortedByDescending { it.modified }
            out.add(sorted[0].modified to Row(sub.name, sorted.take(cap), vids.size, sub))
        }
        return out.sortedByDescending { it.first }.take(maxShelves.coerceAtLeast(0)).map { it.second }
    }

    /** [rows] ditambah baris rak kanal tepat setelah baris Video (atau setelah Folder bila tak ada video langsung). */
    fun rowsWithShelves(dir: File, perRow: Int = ROW_MAX): List<Row> {
        val base = rows(dir, perRow)
        val extra = shelves(dir, perRow)
        if (extra.isEmpty()) return base
        val at = base.indexOfFirst { it.title == "Video" }.let { i -> if (i >= 0) i + 1 else base.indexOfFirst { it.title == "Folder" } + 1 }
        return base.take(at) + extra + base.drop(at)
    }

    /** Teks lencana jumlah video pada judul baris rak kanal: "99+" bila lebih dari 99, tidak pernah negatif. */
    fun badge(total: Int): String = if (total > 99) "99+" else total.coerceAtLeast(0).toString()

    /** "512 B", "1,5 KB", "2,0 MB", "3,4 GB" (koma desimal Indonesia, satu angka di belakang koma). */
    fun size(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        if (b < 1024L) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b / 1024.0
        var i = 0
        while (v >= 1024.0 && i < units.lastIndex) { v /= 1024.0; i++ }
        return String.format(LOCALE, "%.1f %s", v, units[i])
    }

    fun date(millis: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("dd/MM/yyyy HH.mm", LOCALE).also { it.timeZone = zone }.format(Date(millis))

    /** Jam bilah status: "07.05". */
    fun clock(millis: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("HH.mm", LOCALE).also { it.timeZone = zone }.format(Date(millis))

    /** Teks "ruang tersisa" bilah status; [root] tidak terbaca -> null. */
    fun freeLabel(root: File): String? =
        try { root.usableSpace.takeIf { it > 0L }?.let { size(it) + " bebas" } } catch (e: SecurityException) { null }

    /** Baris ringkas panel info untuk item terfokus; durasi/kanal diberikan [extra] (dari ShelfCard/info.json). */
    fun describe(item: Item, extra: String? = null, zone: TimeZone = TimeZone.getDefault()): String {
        val kind = when (item.kind) { Kind.Folder -> "Folder"; Kind.Video -> "Video"; Kind.Audio -> "Audio"; Kind.Other -> "Berkas" }
        val parts = ArrayList<String>()
        parts.add(kind)
        if (item.kind != Kind.Folder) parts.add(size(item.size))
        extra?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        parts.add(date(item.modified, zone))
        return parts.joinToString(" \u00B7 ")
    }

    /**
     * Jalur absolut yang tidak boleh dihapus/diubah namanya/dipindah dari Mode TV: akar volume, folder
     * Recycle Bin tiap volume, akar data aplikasi, dan folder saluran itu sendiri (isinya tetap boleh diubah).
     */
    fun guard(volumes: List<File>, appRoot: File, channelDirs: List<File>): Set<String> {
        val out = HashSet<String>()
        for (v in volumes) {
            out.add(v.absolutePath)
            out.add(File(v, TrashBin.DIR).absolutePath)
        }
        out.add(appRoot.absolutePath)
        for (c in channelDirs) out.add(c.absolutePath)
        return out
    }

    fun canModify(file: File, guard: Set<String>): Boolean = file.absolutePath !in guard

    /**
     * Folder tujuan salin/pindah yang ditawarkan: folder saluran. Untuk pindah, folder tempat berkas sudah
     * berada dikeluarkan; untuk berkas yang folder, folder itu sendiri dan isinya juga dikeluarkan.
     */
    fun destinations(channels: List<Channel>, source: File, move: Boolean): List<Channel> {
        val parent = source.absoluteFile.parentFile?.absolutePath
        val own = source.absolutePath
        return channels.filter { ch ->
            val p = ch.dir.absolutePath
            !(move && p == parent) && p != own && !(source.isDirectory && p.startsWith(own + File.separator))
        }
    }

    /** Pindah saluran dengan batas (tanpa melingkar): arah -1/+1; hasil selalu indeks sah, -1 bila kosong. */
    fun step(current: Int, delta: Int, count: Int): Int =
        if (count <= 0) -1 else (current + delta).coerceIn(0, count - 1)
}
