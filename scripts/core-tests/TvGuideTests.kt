import com.molina.core.common.*
import java.io.File
import java.nio.file.Files
import java.util.TimeZone

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun put(dir: File, name: String, size: Int = 1, mod: Long? = null): File =
    File(dir, name).also { it.writeBytes(ByteArray(size)); if (mod != null) it.setLastModified(mod) }

fun main() {
    val utc = TimeZone.getTimeZone("UTC")

    // ---- size / date / clock
    check("size: byte", TvGuide.size(512) == "512 B" && TvGuide.size(0) == "0 B" && TvGuide.size(-3) == "0 B")
    check("size: KB koma Indonesia", TvGuide.size(1536) == "1,5 KB")
    check("size: MB", TvGuide.size(2L * 1024 * 1024) == "2,0 MB")
    check("size: GB", TvGuide.size((3.4 * 1024 * 1024 * 1024).toLong()) == "3,4 GB")
    check("size: batas 1024 KB -> MB", TvGuide.size(1024L * 1024) == "1,0 MB")
    check("date: format Indonesia", TvGuide.date(1_706_659_200_000L, utc) == "31/01/2024 00.00")
    check("clock: HH.mm", TvGuide.clock(1_706_659_200_000L + 7 * 3600_000L + 5 * 60_000L, utc) == "07.05")

    // ---- step
    check("step: dibatasi", TvGuide.step(0, -1, 5) == 0 && TvGuide.step(4, 1, 5) == 4 && TvGuide.step(2, 1, 5) == 3)
    check("step: kosong -> -1", TvGuide.step(0, 1, 0) == -1)

    val d = Files.createTempDirectory("tv").toFile()
    try {
        // ---- channels
        val a = File(d, "Unduhan").also { it.mkdirs() }
        val b = File(d, "Video").also { it.mkdirs() }
        val ch = TvGuide.channels(listOf("Molina" to File(d, "tidak-ada"), "Unduhan" to a, "Video" to b))
        check("channels: folder hilang dilewati tanpa lubang nomor", ch.map { it.number } == listOf(1, 2) && ch.map { it.label } == listOf("Unduhan", "Video"))
        check("channels: kosong", TvGuide.channels(emptyList()).isEmpty())

        // ---- kindOf
        check("kind: video/audio/lainnya/folder",
            TvGuide.kindOf(put(d, "a.MP4")) == TvGuide.Kind.Video && TvGuide.kindOf(put(d, "a.flac")) == TvGuide.Kind.Audio &&
            TvGuide.kindOf(put(d, "a.txt")) == TvGuide.Kind.Other && TvGuide.kindOf(a) == TvGuide.Kind.Folder)

        // ---- rows
        val r = File(d, "isi").also { it.mkdirs() }
        File(r, "zeta").mkdirs(); File(r, "Alfa").mkdirs(); File(r, ".rahasia").mkdirs()
        put(r, "lama.mp4", mod = 1_000_000_000_000L)
        put(r, "baru.mkv", mod = 1_700_000_000_000L)
        put(r, "lagu.mp3", mod = 1_500_000_000_000L)
        put(r, "catatan.txt"); put(r, ".tersembunyi.mp4"); put(r, "poster.jpg")
        val rows = TvGuide.rows(r)
        check("rows: urutan baris", rows.map { it.title } == listOf("Folder", "Video", "Audio", "Lainnya"))
        check("rows: folder urut nama tanpa tersembunyi", rows[0].items.map { it.name } == listOf("Alfa", "zeta"))
        check("rows: video terbaru dulu", rows[1].items.map { it.name } == listOf("baru.mkv", "lama.mp4"))
        check("rows: audio", rows[2].items.single().name == "lagu.mp3")
        check("rows: lainnya memuat txt dan jpg", rows[3].items.map { it.name }.toSet() == setOf("catatan.txt", "poster.jpg"))
        check("rows: judul tanpa ekstensi", rows[1].items[0].title == "baru" && rows[0].items[0].title == "Alfa")

        val capped = TvGuide.rows(r, 1)
        check("rows: dipotong perRow, total tetap", capped[1].items.size == 1 && capped[1].total == 2)
        check("rows: perRow nol dianggap 1", TvGuide.rows(r, 0)[1].items.size == 1)
        val empty = File(d, "kosong").also { it.mkdirs() }
        check("rows: folder kosong -> tanpa baris", TvGuide.rows(empty).isEmpty())
        check("rows: folder hilang -> kosong", TvGuide.rows(File(d, "gaib")).isEmpty())
        check("rows: berkas bukan folder -> kosong", TvGuide.rows(put(d, "bukan-folder.bin")).isEmpty())

        // ---- shelves: rak per kanal (subfolder berisi video -> baris berjudul nama folder)
        val sh = File(d, "rak").also { it.mkdirs() }
        val chA = File(sh, "Kanal Alfa").also { it.mkdirs() }
        val chB = File(sh, "Kanal Beta").also { it.mkdirs() }
        val chC = File(sh, "Tanpa Video").also { it.mkdirs() }
        val chH = File(sh, ".tersembunyi").also { it.mkdirs() }
        put(chA, "a1.mp4", mod = 1_000_000_000_000L); put(chA, "a2.mkv", mod = 1_600_000_000_000L); put(chA, "catatan.txt")
        put(chB, "b1.mp4", mod = 1_700_000_000_000L)
        put(chC, "lagu.mp3"); put(chH, "x.mp4")
        put(sh, "langsung.mp4", mod = 1_200_000_000_000L)
        val shelves = TvGuide.shelves(sh)
        check("shelves: hanya subfolder berisi video, video terbaru di depan", shelves.map { it.title } == listOf("Kanal Beta", "Kanal Alfa"))
        check("shelves: isi baris terbaru dulu, total benar", shelves[1].items.map { it.name } == listOf("a2.mkv", "a1.mp4") && shelves[1].total == 2)
        check("shelves: baris menyimpan folder dan bertanda shelf", shelves.all { it.shelf } && shelves[0].folder == chB)
        check("shelves: berkas langsung di folder induk tidak ikut", shelves.none { r0 -> r0.items.any { it.name == "langsung.mp4" } })
        check("shelves: dipotong perRow, total tetap", TvGuide.shelves(sh, perRow = 1)[1].let { it.items.size == 1 && it.total == 2 })
        check("shelves: batas jumlah baris", TvGuide.shelves(sh, maxShelves = 1).map { it.title } == listOf("Kanal Beta"))
        check("shelves: folder hilang atau berkas -> kosong", TvGuide.shelves(File(d, "gaib")).isEmpty() && TvGuide.shelves(put(d, "bukan-folder2.bin")).isEmpty())
        check("rows: baris biasa bukan shelf", rows.none { it.shelf } && rows.all { it.folder == null })
        check("badge: sampai 99 apa adanya, lebih dari itu 99+, tidak negatif", TvGuide.badge(1) == "1" && TvGuide.badge(99) == "99" && TvGuide.badge(100) == "99+" && TvGuide.badge(-3) == "0")
        check("cover: video terbaru di baris kanal", shelves[0].coverFile == File(chB, "b1.mp4") && shelves[1].coverFile == File(chA, "a2.mkv"))
        check("cover: baris biasa memakai video terbarunya, baris tanpa video -> null", rows[1].coverFile?.name == "baru.mkv" && rows[2].coverFile == null)
        val merged = TvGuide.rowsWithShelves(sh)
        check("rowsWithShelves: baris kanal tepat setelah Video", merged.map { it.title } == listOf("Folder", "Video", "Kanal Beta", "Kanal Alfa"))
        check("rowsWithShelves: tanpa subfolder berisi video sama dengan rows", TvGuide.rowsWithShelves(r).map { it.title } == TvGuide.rows(r).map { it.title })
        val clash = File(d, "bentrok").also { it.mkdirs() }
        put(File(clash, "Video").also { it.mkdirs() }, "v.mp4"); put(clash, "top.mp4")
        val clashRows = TvGuide.rowsWithShelves(clash)
        check("rowsWithShelves: folder bernama Video tidak membuat kunci ganda",
            clashRows.count { it.title == "Video" } == 2 && clashRows.map { it.key }.let { it.size == it.toSet().size })
        val onlyShelf = File(d, "hanya-rak").also { it.mkdirs() }
        put(File(onlyShelf, "K1").also { it.mkdirs() }, "k.mp4")
        check("rowsWithShelves: tanpa video langsung, baris kanal setelah Folder", TvGuide.rowsWithShelves(onlyShelf).map { it.title } == listOf("Folder", "K1"))

        // ---- describe
        val it0 = TvGuide.Item(put(d, "Film.mp4", size = 2048, mod = 1_706_659_200_000L), TvGuide.Kind.Video, 2048, 1_706_659_200_000L)
        check("describe: video + extra", TvGuide.describe(it0, "1:02:05", utc) == "Video \u00B7 2,0 KB \u00B7 1:02:05 \u00B7 31/01/2024 00.00")
        check("describe: tanpa extra", TvGuide.describe(it0, null, utc) == "Video \u00B7 2,0 KB \u00B7 31/01/2024 00.00")
        check("describe: extra kosong diabaikan", TvGuide.describe(it0, "  ", utc) == TvGuide.describe(it0, null, utc))
        val fd = TvGuide.Item(a, TvGuide.Kind.Folder, 0, 1_706_659_200_000L)
        check("describe: folder tanpa ukuran", TvGuide.describe(fd, null, utc) == "Folder \u00B7 31/01/2024 00.00")

        // ---- guard / destinations
        val root = File(d, "root").also { it.mkdirs() }
        val app = File(root, "Molina").also { it.mkdirs() }
        val unduh = File(app, "Downloads").also { it.mkdirs() }
        val vid = File(app, "Video").also { it.mkdirs() }
        val g = TvGuide.guard(listOf(root), app, listOf(unduh, vid))
        check("guard: akar volume, trash, appRoot, saluran dilindungi",
            root.absolutePath in g && File(root, TrashBin.DIR).absolutePath in g && app.absolutePath in g && unduh.absolutePath in g)
        check("guard: isi saluran boleh diubah", TvGuide.canModify(File(unduh, "film.mp4"), g) && !TvGuide.canModify(unduh, g))
        val chs = TvGuide.channels(listOf("Unduhan" to unduh, "Video" to vid))
        val src = File(unduh, "film.mp4").also { it.writeText("x") }
        check("destinations: pindah mengeluarkan folder asal", TvGuide.destinations(chs, src, true).map { it.label } == listOf("Video"))
        check("destinations: salin menawarkan semua", TvGuide.destinations(chs, src, false).map { it.label } == listOf("Unduhan", "Video"))
        val sub = File(unduh, "pl").also { it.mkdirs() }
        val chs2 = TvGuide.channels(listOf("Unduhan" to unduh, "Dalam" to sub, "Video" to vid))
        check("destinations: folder tidak ditawarkan ke dirinya/isinya", TvGuide.destinations(chs2, sub, false).map { it.label } == listOf("Unduhan", "Video"))

        // ---- freeLabel
        check("freeLabel: folder ada -> berakhiran bebas", TvGuide.freeLabel(d)?.endsWith(" bebas") == true)
        check("freeLabel: folder hilang -> null", TvGuide.freeLabel(File(d, "gaib")) == null)
    } finally {
        d.deleteRecursively()
    }
    if (fails > 0) { println("$fails GAGAL"); System.exit(1) }
    println("semua lulus")
}
