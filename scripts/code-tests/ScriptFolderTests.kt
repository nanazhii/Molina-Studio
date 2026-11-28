import com.molina.code.run.KnownScript
import com.molina.code.run.ScanFile
import com.molina.code.run.ScriptFolder
import com.molina.code.run.Stamp
import java.io.File

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private const val P = "/m/Scripts/"
private fun sf(name: String, body: String?) = ScanFile(P + name, name, body, 1L)
private fun ks(id: String, path: String?, key: String?, title: String, body: String) = KnownScript(id, path, key, title, body)

fun main() {
    // nama berkas
    check("isScriptName: .sh/.py/.bash ok", listOf("a.sh", "b.py", "c.bash", "D.SH").all { ScriptFolder.isScriptName(it) })
    check("isScriptName: tolak tersembunyi/tmp/new/tanpa nama", listOf(".a.sh", "a.sh.new", "a.sh.tmp", ".sh", "sh", "a.txt", "a").none { ScriptFolder.isScriptName(it) })
    check("safeFileName: lengkapi ekstensi", ScriptFolder.safeFileName("backup", "sh") == "backup.sh" && ScriptFolder.safeFileName("x.py", "sh") == "x.py")
    check("safeFileName: karakter terlarang", ScriptFolder.safeFileName("a/b:c*d", "sh") == "a_b_c_d.sh")
    check("safeFileName: kosong/titik jadi skrip", ScriptFolder.safeFileName("  ...  ", "py") == "skrip.py")
    check("safeFileName: path traversal tidak lolos", !ScriptFolder.safeFileName("../../x", "sh").contains("/"))
    check("uniqueName: bebas", ScriptFolder.uniqueName("a.sh") { false } == "a.sh")
    check("uniqueName: bentrok -> -2, -3", ScriptFolder.uniqueName("a.sh") { it == "a.sh" || it == "a-2.sh" } == "a-3.sh")

    // reconcile
    val none = ScriptFolder.reconcile(emptyList(), listOf(sf("a.sh", "x"), sf("b.py", "y")), P)
    check("berkas baru -> added", none.added.map { it.name } == listOf("a.sh", "b.py") && none.removed.isEmpty() && none.kept.isEmpty())

    val keep = ScriptFolder.reconcile(listOf(ks("1", P + "a.sh", null, "a.sh", "x")), listOf(sf("a.sh", null)), P)
    check("entri + berkas ada -> kept (id dipertahankan)", keep.kept == mapOf(P + "a.sh" to "1") && keep.added.isEmpty())

    val gone = ScriptFolder.reconcile(listOf(ks("1", P + "a.sh", null, "a.sh", "x")), emptyList(), P)
    check("berkas hilang -> removed", gone.removed == listOf("1"))

    val dup = ScriptFolder.reconcile(listOf(ks("1", P + "a.sh", null, "a.sh", "x"), ks("2", P + "a.sh", null, "a.sh", "x")), listOf(sf("a.sh", null)), P)
    check("entri ganda satu path -> yang kedua dibuang", dup.kept == mapOf(P + "a.sh" to "1") && dup.removed == listOf("2"))

    val l1 = ScriptFolder.reconcile(listOf(ks("9", null, P + "a.sh", "lama", "isi lama")), listOf(sf("a.sh", "isi baru")), P)
    check("entri lama dengan sourceKey = path berkas -> linked (berkas menang)", l1.linked == mapOf("9" to P + "a.sh") && l1.exported.isEmpty() && l1.added.isEmpty())

    val l2 = ScriptFolder.reconcile(listOf(ks("9", null, "tab:z", "a.sh", "isi")), listOf(sf("a.sh", "isi")), P)
    check("entri lama nama+isi sama -> linked (tidak menggandakan)", l2.linked == mapOf("9" to P + "a.sh") && l2.exported.isEmpty())

    val l3 = ScriptFolder.reconcile(listOf(ks("9", null, "tab:z", "a.sh", "isi A")), listOf(sf("a.sh", "isi B")), P)
    check("entri lama nama sama isi beda -> diekspor, berkas lain tetap added", l3.exported == listOf("9") && l3.added.map { it.name } == listOf("a.sh"))

    val l4 = ScriptFolder.reconcile(listOf(ks("9", null, null, "baru.sh", "x")), emptyList(), P)
    check("entri lama tanpa berkas -> diekspor", l4.exported == listOf("9"))

    val l5 = ScriptFolder.reconcile(listOf(ks("9", "/lain/x.sh", null, "x.sh", "x")), emptyList(), P)
    check("path di luar folder diperlakukan sebagai entri lama (tidak dibuang)", l5.exported == listOf("9") && l5.removed.isEmpty())

    // scan
    val dir = File(System.getProperty("java.io.tmpdir"), "molina-scan-" + System.nanoTime()).apply { mkdirs() }
    try {
        File(dir, "b.py").writeText("print(1)\r\n")
        File(dir, "a.sh").writeText("#!/bin/bash\necho hai\n")
        File(dir, ".hidden.sh").writeText("x")
        File(dir, "a.sh.new").writeText("x")
        File(dir, "catatan.txt").writeText("x")
        File(dir, "bin.sh").writeBytes(byteArrayOf(0x23, 0x21, 0x00, 0x01))
        File(dir, "besar.sh").writeText("x\n".repeat(3100))
        File(dir, "sub").mkdirs()
        File(dir, "sub/c.sh").writeText("x")
        File(dir, "kosong.sh").writeText("")

        val r1 = ScriptFolder.scan(dir, emptyMap())!!
        check("scan: hanya skrip valid, urut nama", r1.files.map { it.name } == listOf("a.sh", "b.py", "kosong.sh"))
        check("scan: CRLF dinormalkan, isi terbaca", r1.files.first { it.name == "b.py" }.body == "print(1)\n")
        check("scan: biner dan terlalu panjang dilewati dan dihitung", r1.skipped == 2)
        check("scan: berkas kosong tetap terdaftar", r1.files.first { it.name == "kosong.sh" }.body == "")

        val r2 = ScriptFolder.scan(dir, r1.stamps)!!
        check("scan ulang: berkas tak berubah tidak dibaca ulang (body null)", r2.files.all { it.body == null })

        val a = File(dir, "a.sh")
        a.writeText("#!/bin/bash\necho berubah dan lebih panjang\n")
        val r3 = ScriptFolder.scan(dir, r1.stamps)!!
        check("scan: berkas berubah dibaca ulang, lainnya tidak", r3.files.first { it.name == "a.sh" }.body?.contains("berubah") == true && r3.files.first { it.name == "b.py" }.body == null)

        File(dir, "b.py").delete()
        val r4 = ScriptFolder.scan(dir, r3.stamps)!!
        check("scan: berkas dihapus hilang dari hasil", r4.files.none { it.name == "b.py" })

        check("scan: folder tidak ada -> null (bukan kosong)", ScriptFolder.scan(File(dir, "tidak-ada"), emptyMap()) == null)
        val bogus = mapOf(File(dir, "a.sh").path to Stamp(0L, 0L))
        check("scan: cap beda -> dibaca ulang", ScriptFolder.scan(dir, bogus)!!.files.first { it.name == "a.sh" }.body != null)
    } finally {
        dir.deleteRecursively()
    }

    if (fails > 0) { println("$fails uji GAGAL"); kotlin.system.exitProcess(1) }
    println("semua uji ScriptFolder lulus")
}
