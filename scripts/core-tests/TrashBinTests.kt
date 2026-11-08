import com.molina.core.common.TrashBin
import com.molina.core.common.TrashItem
import java.io.File
import java.nio.file.Files

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}
private fun fails(block: () -> Unit): String? = try { block(); null } catch (e: java.io.IOException) { e.message ?: "" }

private fun write(f: File, text: String): File { f.parentFile.mkdirs(); f.writeText(text); return f }

fun main() {
    val base = Files.createTempDirectory("trashtest").toFile().canonicalFile
    val vol = File(base, "vol").also { it.mkdirs() }
    val sd = File(base, "sd").also { it.mkdirs() }
    val vols = listOf(vol, sd)
    val guard = setOf(vol.absolutePath, File(vol, "Molina").absolutePath)
    val day = 24L * 60 * 60 * 1000
    val t0 = 1_700_000_000_000L

    // rootFor
    check("rootFor: berkas di volume -> .molina-trash volume itu", TrashBin.rootFor(File(vol, "a/b.txt"), vols) == File(vol, ".molina-trash"))
    check("rootFor: volume lain dipilih benar", TrashBin.rootFor(File(sd, "x.txt"), vols) == File(sd, ".molina-trash"))
    check("rootFor: volume itu sendiri -> null", TrashBin.rootFor(vol, vols) == null)
    check("rootFor: di luar volume -> null", TrashBin.rootFor(File(base, "lain/x.txt"), vols) == null)
    val nested = File(vol, "nest").also { it.mkdirs() }
    check("rootFor: volume terdalam menang", TrashBin.rootFor(File(nested, "x"), vols + nested) == File(nested.canonicalFile, ".molina-trash"))
    check("isInTrash", TrashBin.isInTrash(File(vol, ".molina-trash/1-0/files/a")) && !TrashBin.isInTrash(File(vol, "a")))

    // trash + list
    val f1 = write(File(vol, "Musik/Lagu Indonesia ñ \u00e9.mp3"), "isi-1234")
    val items = TrashBin.trash(listOf(f1), vols, guard, t0)
    check("trash: sumber hilang, payload ada", !f1.exists() && items[0].payload.readText() == "isi-1234")
    check("trash: .nomedia dibuat", File(vol, ".molina-trash/.nomedia").exists())
    val l1 = TrashBin.list(vols)
    check("list: nama unicode, asal, ukuran, waktu", l1.size == 1 && l1[0].name == f1.name && l1[0].origin == f1.absoluteFile && l1[0].size == 8L && l1[0].deletedAt == t0)

    // folder
    val d = File(vol, "Proyek").also { it.mkdirs() }
    write(File(d, "a.txt"), "aaaa"); write(File(d, "sub/b.txt"), "bb")
    val it2 = TrashBin.trash(listOf(d), vols, guard, t0 + 1000)
    check("trash folder: ukuran total dan isDir", it2[0].isDir && it2[0].size == 6L && !d.exists() && File(it2[0].payload, "sub/b.txt").exists())
    check("list: terbaru dulu", TrashBin.list(vols).map { it.deletedAt } == listOf(t0 + 1000, t0))

    // restore
    val r1 = TrashBin.restore(it2, vols)
    check("restore folder: kembali utuh, item hilang dari bin", r1[0] == d.absoluteFile && File(d, "sub/b.txt").readText() == "bb" && TrashBin.list(vols).size == 1)
    // konflik nama
    write(f1, "baru")
    val r2 = TrashBin.restore(TrashBin.list(vols), vols)
    check("restore: nama bentrok diberi (1)", r2[0].name == "Lagu Indonesia ñ \u00e9 (1).mp3" && f1.readText() == "baru" && r2[0].readText() == "isi-1234")
    // folder asal hilang
    val f3 = write(File(vol, "Hapus/Dalam/c.txt"), "c")
    val i3 = TrashBin.trash(listOf(f3), vols, guard, t0)
    File(vol, "Hapus").deleteRecursively()
    val r3 = TrashBin.restore(i3, vols)
    check("restore: folder asal dibuat ulang", r3[0].readText() == "c")

    // validasi semua-atau-tidak
    val okF = write(File(vol, "ok.txt"), "x")
    val e1 = fails { TrashBin.trash(listOf(okF, File(vol, "tidak-ada.txt")), vols, guard, t0) }
    check("trash: semua-atau-tidak (satu hilang -> tak ada yang dipindah)", e1 != null && okF.exists())
    val e2 = fails { TrashBin.trash(listOf(File(vol, "Molina")), vols, guard, t0) }
    check("trash: folder dilindungi ditolak", e2 != null && e2.contains("dilindungi"))
    val e3 = fails { TrashBin.trash(listOf(write(File(base, "lain/x.txt"), "x")), vols, guard, t0) }
    check("trash: di luar volume ditolak dengan saran Hapus permanen", e3 != null && e3.contains("Hapus permanen"))
    val e4 = fails { TrashBin.trash(listOf(TrashBin.list(vols).firstOrNull()?.payload ?: File(vol, ".molina-trash/x")), vols, guard, t0) }
    check("trash: item yang sudah di bin ditolak", e4 != null)

    // autoPurge
    TrashBin.empty(vols)
    val old = write(File(vol, "tua.txt"), "t"); val fresh = write(File(vol, "baru.txt"), "b"); val future = write(File(vol, "masadepan.txt"), "f")
    TrashBin.trash(listOf(old), vols, guard, t0)
    TrashBin.trash(listOf(fresh), vols, guard, t0 + 29 * day)
    TrashBin.trash(listOf(future), vols, guard, t0 + 100 * day)
    val n = TrashBin.autoPurge(vols, now = t0 + 31 * day)
    val left = TrashBin.list(vols).map { it.name }.sorted()
    check("autoPurge: hanya >30 hari; jam mundur tidak menghapus", n == 1 && left == listOf("baru.txt", "masadepan.txt"))
    val fi = TrashBin.list(vols).first { it.name == "baru.txt" }
    check("daysLeft", fi.daysLeft(t0 + 31 * day) == 28 && fi.daysLeft(t0 + 500 * day) == 0)
    check("usage", TrashBin.usage(vols) == 2L)

    // purge + tamper
    val victim = write(File(base, "korban/penting.txt"), "jangan")
    val fake = TrashItem("x", "penting.txt", File(vol, "a"), t0, 0, false, victim.parentFile)
    check("purge: item di luar .molina-trash diabaikan", TrashBin.purge(listOf(fake)) == 0 && victim.exists())
    val toPurge = TrashBin.list(vols).filter { it.name == "baru.txt" }
    check("purge: menghapus item bin", TrashBin.purge(toPurge) == 1 && TrashBin.list(vols).map { it.name } == listOf("masadepan.txt"))
    val e5 = fails { TrashBin.restore(listOf(fake), vols) }
    check("restore: item di luar bin ditolak", e5 != null)

    // info.properties dirusak: diabaikan di list, dibersihkan empty
    val bad = File(vol, ".molina-trash/9-9").also { it.mkdirs() }
    File(bad, "info.properties").writeText("name=../../etc\norigin=/x\nat=1\n")
    File(bad, "files").mkdirs()
    check("list: nama berbahaya (..) diabaikan", TrashBin.list(vols).none { it.dir == bad })
    val orphan = File(vol, ".molina-trash/8-8").also { it.mkdirs() }
    // origin dipalsukan di luar volume
    val evil = write(File(vol, "e.txt"), "e")
    val ev = TrashBin.trash(listOf(evil), vols, guard, t0 + 5).single()
    File(ev.dir, "info.properties").writeText("name=e.txt\norigin=${File(base, "luar/e.txt").path}\nat=5\nsize=1\ndir=false\n")
    val tampered = TrashBin.list(vols).first { it.id == ev.id }
    val e6 = fails { TrashBin.restore(listOf(tampered), vols) }
    check("restore: origin palsu di luar volume ditolak", e6 != null && !File(base, "luar/e.txt").exists())
    val removed = TrashBin.empty(vols)
    check("empty: hapus semua termasuk yatim/rusak, .nomedia tetap", removed >= 3 && TrashBin.list(vols).isEmpty() && !orphan.exists() && !bad.exists() && File(vol, ".molina-trash/.nomedia").exists())

    // tautan simbolik: tidak diikuti
    try {
        val target = write(File(base, "target-besar/data.bin"), "x".repeat(500))
        val link = File(vol, "tautan")
        Files.createSymbolicLink(link.toPath(), target.parentFile.toPath())
        val li = TrashBin.trash(listOf(link), vols, guard, t0 + 9).single()
        check("symlink: dipindah sebagai tautan, target tidak tersentuh, ukuran 0", target.exists() && li.size == 0L && Files.isSymbolicLink(li.payload.toPath()))
    } catch (e: Exception) { println("lewati symlink: " + e.message) }

    base.deleteRecursively()
    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails > 0) System.exit(1)
}
