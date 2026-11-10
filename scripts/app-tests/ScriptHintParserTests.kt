import com.molina.studio.code.ScriptHintParser
import com.molina.studio.code.ScriptHintParser.Kind

private var fails = 0
private var runs = 0
private fun <T> eq(name: String, got: T, want: T) { runs++; if (got != want) { fails++; println("GAGAL: $name -> $got (seharusnya $want)") } }

fun main(args: Array<String>) {
    val t = "\t"
    val out = "a.py${t}fix${t}belum ada shebang (baris 1, contoh: #!/usr/bin/env python3)\n" +
        "c.py${t}warn${t}pembungkus 'python3 << EOF' dibuka\r\n" +
        "\n" +
        "sampah tanpa tab\n" +
        "x.sh${t}aneh${t}jenis tak dikenal\n" +
        "dir/y.sh${t}fix${t}nama berisi garis miring\n" +
        "${t}fix${t}tanpa nama\n" +
        "d.sh${t}fix\n"
    val m = ScriptHintParser.parse(out)
    eq("jumlah", m.size, 3)
    eq("a.py jenis", m["a.py"]?.kind, Kind.Fix)
    eq("a.py pesan", m["a.py"]?.message, "belum ada shebang (baris 1, contoh: #!/usr/bin/env python3)")
    eq("c.py jenis (CRLF dibuang)", m["c.py"]?.kind, Kind.Warn)
    eq("c.py pesan", m["c.py"]?.message, "pembungkus 'python3 << EOF' dibuka")
    eq("tanpa pesan -> kosong", m["d.sh"]?.message, "")
    eq("jenis tak dikenal diabaikan", m.containsKey("x.sh"), false)
    eq("garis miring diabaikan", m.containsKey("dir/y.sh"), false)
    eq("kosong", ScriptHintParser.parse("").isEmpty(), true)
    eq("pesan berisi tab tetap utuh", ScriptHintParser.parse("a.sh${t}warn${t}satu${t}dua")["a.sh"]?.message, "satu${t}dua")
    eq("pesan dipotong 300", ScriptHintParser.parse("a.sh${t}warn${t}" + "z".repeat(500))["a.sh"]?.message?.length, 300)
    eq("nama kembar: terakhir menang", ScriptHintParser.parse("a.sh${t}warn${t}1\na.sh${t}fix${t}2")["a.sh"]?.kind, Kind.Fix)

    val f1 = listOf(Triple("b.sh", 10L, 5), Triple("a.py", 20L, 7))
    val s1 = ScriptHintParser.signature(f1, true)
    eq("tanda tangan: urutan tak berpengaruh", ScriptHintParser.signature(f1.reversed(), true), s1)
    eq("tanda tangan: waktu ubah berpengaruh", ScriptHintParser.signature(listOf(Triple("b.sh", 11L, 5), Triple("a.py", 20L, 7)), true) != s1, true)
    eq("tanda tangan: ukuran berpengaruh", ScriptHintParser.signature(listOf(Triple("b.sh", 10L, 6), Triple("a.py", 20L, 7)), true) != s1, true)
    eq("tanda tangan: sakelar tips berpengaruh", ScriptHintParser.signature(f1, false) != s1, true)
    eq("tanda tangan: berkas hilang berpengaruh", ScriptHintParser.signature(f1.drop(1), true) != s1, true)
    eq("tanda tangan: folder kosong stabil", ScriptHintParser.signature(emptyList(), true), ScriptHintParser.signature(emptyList(), true))

    println("Selesai: $runs kasus, $fails gagal")
    if (fails > 0) System.exit(1)
}
