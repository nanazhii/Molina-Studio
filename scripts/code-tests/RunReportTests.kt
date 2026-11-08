import com.molina.code.guard.MiniJson
import com.molina.code.guard.MiniXml
import com.molina.code.guard.RunReport
import com.molina.code.guard.RunReport.Issue
import com.molina.code.guard.RunReport.Kind
import java.io.File

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun tmp(): File = java.nio.file.Files.createTempDirectory("rr").toFile()
private fun File.put(rel: String, text: String, old: Boolean = true): File {
    val f = File(this, rel)
    f.parentFile.mkdirs()
    f.writeText(text)
    if (old) f.setLastModified(1_000_000_000_000L) // jauh sebelum waktu pindai
    return f
}
private fun File.putBytes(rel: String, b: ByteArray): File {
    val f = File(this, rel); f.parentFile.mkdirs(); f.writeBytes(b); f.setLastModified(1_000_000_000_000L); return f
}

fun main() {
    // ---- MiniJson
    check("json: objek bersarang sah", MiniJson.valid("{\"a\":[1,2.5,-3e2,true,null,\"x\\n\\u00e9\"],\"b\":{}}"))
    check("json: BOM dan spasi", MiniJson.valid("\uFEFF  [ ]\n"))
    check("json: koma menggantung", !MiniJson.valid("{\"a\":1,}"))
    check("json: kutip tunggal", !MiniJson.valid("{'a':1}"))
    check("json: terpotong", !MiniJson.valid("{\"a\":[1,2"))
    check("json: sisa setelah nilai", !MiniJson.valid("{} x"))
    check("json: angka berawalan nol", !MiniJson.valid("[01]"))
    check("json: escape salah", !MiniJson.valid("[\"\\q\"]"))
    check("json: kosong", !MiniJson.valid(""))

    // ---- MiniXml
    check("xml: manifest sah", MiniXml.valid("<?xml version=\"1.0\"?>\n<!-- c -->\n<manifest a=\"1>2\"><app/><x>t</x></manifest>\n"))
    check("xml: tag tidak ditutup", !MiniXml.valid("<a><b></a>"))
    check("xml: dua akar", !MiniXml.valid("<a/><b/>"))
    check("xml: terpotong", !MiniXml.valid("<a><b>"))
    check("xml: kutip atribut terbuka", !MiniXml.valid("<a x=\"1></a>"))
    check("xml: teks di luar akar", !MiniXml.valid("<a/>junk"))
    check("xml: CDATA", MiniXml.valid("<a><![CDATA[<b>]]></a>"))

    // ---- findRoot
    val r0 = tmp()
    File(r0, ".git").mkdirs()
    File(r0, "a/b").mkdirs()
    check("findRoot naik ke folder .git", RunReport.findRoot(File(r0, "a/b")) == r0)
    val lone = tmp()
    check("findRoot tanpa .git: folder itu sendiri", RunReport.findRoot(lone) == lone)

    // ---- [CODE-SCANROOT] akar proyek tanpa Git
    val rm = tmp()
    File(rm, ".molina").mkdirs()
    File(rm, "x/y").mkdirs()
    check("akar: penanda .molina", RunReport.findRootInfo(File(rm, "x/y")).let { it.root == rm && it.how == ".molina" })
    val rg = tmp()
    File(rg, ".git").mkdirs()
    File(rg, "a").mkdirs()
    check("akar: .git menamai penandanya", RunReport.findRootInfo(File(rg, "a")).how == ".git")
    val rw = tmp()
    File(rw, "package.json").writeText("{}")
    File(rw, "src/app").mkdirs()
    check("akar: berkas proyek umum tanpa Git", RunReport.findRootInfo(File(rw, "src/app")).let { it.root == rw && it.how == "package.json" })
    val rs = tmp()
    File(rs, ".git").mkdirs()
    File(rs, "mod/sub").mkdirs()
    File(rs, "mod/package.json").writeText("{}")
    check("akar: penanda kuat mengalahkan berkas proyek di dalamnya", RunReport.findRootInfo(File(rs, "mod/sub")).root == rs)
    val rn = tmp()
    File(rn, "outer/inner").mkdirs()
    File(rn, "outer/package.json").writeText("{}")
    File(rn, "outer/inner/Makefile").writeText("all:\n")
    check("akar: berkas proyek terdekat menang", RunReport.findRootInfo(File(rn, "outer/inner")).root == File(rn, "outer/inner"))
    check("akar: tanpa penanda = folder itu sendiri, how null", RunReport.findRootInfo(lone).let { it.root == lone && it.how == null })
    check("akar: findRoot tetap kompatibel", RunReport.findRoot(File(rm, "x/y")) == rm)

    // ---- Pindai, ubah, bandingkan
    val root = tmp()
    root.put("a.txt", "satu\ndua\ntiga\n")
    root.put("b.kt", "fun main() {}\n")
    root.put("data.json", "{\"k\":1}\n")
    root.put("gone.txt", "hapus saya\n")
    root.put("same.txt", "tetap\n")
    root.put("big.txt", "x".repeat(300) + "\n")
    root.put(".git/HEAD", "ref\n")
    root.put("build/out.txt", "abaikan\n")
    root.putBytes("img.png", byteArrayOf(1, 2, 3))
    val now = System.currentTimeMillis()
    val snap = RunReport.capture(root, now)!!
    check("pindai: berkas .git dan build diabaikan", snap.meta.keys.none { it.startsWith(".git/") || it.startsWith("build/") })
    check("pindai: biner tidak dicadangkan", "img.png" in snap.meta && "img.png" !in snap.backups)
    check("pindai: berkas teks dicadangkan", snap.backups["a.txt"]?.toString(Charsets.UTF_8) == "satu\ndua\ntiga\n")

    // skrip: ubah, tambah, hapus, sentuh saja, rusak
    root.put("a.txt", "satu\nDUA\ntiga\nempat\n", old = false)
    root.put("new.txt", "baru\n1\n2\n", old = false)
    File(root, "gone.txt").delete()
    File(root, "same.txt").setLastModified(now + 60_000) // hanya disentuh, isi sama
    root.put("data.json", "{\"k\":1,", old = false)
    root.put("b.kt", "fun main() {}", old = false) // baris baru akhir hilang
    root.put("big.txt", "x", old = false)
    root.put("build/out.txt", "tetap diabaikan\n", old = false)
    root.putBytes("bad.txt", byteArrayOf(0x66, 0xC3.toByte(), 0x28)).setLastModified(now + 1000)
    root.putBytes("nul.txt", byteArrayOf(0x61, 0, 0x62)).setLastModified(now + 1000)

    val rep = RunReport.compare(snap)!!
    fun ch(p: String) = rep.changes.firstOrNull { it.path == p }
    check("laporan: a.txt dimodifikasi +2 -1", ch("a.txt")?.let { it.kind == Kind.MODIFIED && it.added == 2 && it.removed == 1 && it.undoable && it.issues.isEmpty() } == true)
    check("laporan: new.txt ditambah +3", ch("new.txt")?.let { it.kind == Kind.ADDED && it.added == 3 && it.removed == 0 && it.undoable } == true)
    check("laporan: gone.txt dihapus -1", ch("gone.txt")?.let { it.kind == Kind.DELETED && it.removed == 1 && it.undoable } == true)
    check("laporan: berkas yang hanya disentuh tidak muncul", ch("same.txt") == null)
    check("laporan: berkas tak berubah tidak muncul", ch("img.png") == null)
    check("laporan: folder build diabaikan", rep.changes.none { it.path.startsWith("build/") })
    check("laporan: JSON rusak", ch("data.json")?.issues?.contains(Issue.BAD_JSON) == true)
    check("laporan: baris baru akhir hilang", ch("b.kt")?.issues?.contains(Issue.NEWLINE_LOST) == true)
    check("laporan: menyusut drastis", ch("big.txt")?.issues?.contains(Issue.SHRUNK) == true)
    check("laporan: bukan UTF-8", ch("bad.txt")?.issues?.contains(Issue.NOT_UTF8) == true)
    check("laporan: byte NUL", ch("nul.txt")?.issues?.contains(Issue.HAS_NUL) == true)
    check("laporan: urut menurut path", rep.changes.map { it.path } == rep.changes.map { it.path }.sorted())
    check("laporan: hitungan bermasalah", rep.problems == 5)

    // ---- Urungkan
    // data.json diubah lagi setelah laporan: harus dilewati, tidak ditimpa
    root.put("data.json", "{\"k\":99}\n", old = false)
    val u = RunReport.undo(snap, rep)
    check("urungkan: a.txt kembali", File(root, "a.txt").readText() == "satu\ndua\ntiga\n")
    check("urungkan: gone.txt pulih", File(root, "gone.txt").readText() == "hapus saya\n")
    check("urungkan: new.txt dihapus", !File(root, "new.txt").exists())
    check("urungkan: b.kt kembali", File(root, "b.kt").readText() == "fun main() {}\n")
    check("urungkan: berkas yang berubah lagi dilewati", File(root, "data.json").readText() == "{\"k\":99}\n" && "data.json" in u.skipped)
    check("urungkan: bad.txt (baru) dihapus", !File(root, "bad.txt").exists())
    check("urungkan: tanpa gagal", u.failed.isEmpty())
    check("urungkan: tanpa sisa berkas sementara", root.walkTopDown().none { it.name.endsWith(".molina-undo") })
    check("urungkan: hitungan", u.restored == 4 && u.removed == 3)

    // ---- Berkas besar: tanpa cadangan, tak bisa diurungkan
    val r2 = tmp()
    r2.put("huge.txt", "y".repeat(RunReport.MAX_BACKUP_FILE + 10))
    val s2 = RunReport.capture(r2, System.currentTimeMillis())!!
    check("besar: tidak dicadangkan", "huge.txt" !in s2.backups)
    r2.put("huge.txt", "z".repeat(RunReport.MAX_BACKUP_FILE + 20), old = false)
    val rep2 = RunReport.compare(s2)!!
    check("besar: terlapor tapi tak bisa diurungkan", rep2.changes.single().let { it.kind == Kind.MODIFIED && !it.undoable })

    // ---- Tanpa perubahan
    val r3 = tmp()
    r3.put("x.txt", "a\n")
    val s3 = RunReport.capture(r3, System.currentTimeMillis())!!
    check("tanpa perubahan: laporan kosong", RunReport.compare(s3)!!.isEmpty)

    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails != 0) kotlin.system.exitProcess(1)
}
