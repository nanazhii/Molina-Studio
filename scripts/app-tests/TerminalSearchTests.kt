import com.molina.studio.terminal.TerminalSearch

private var fails = 0
private var runs = 0
private fun <T> eq(name: String, got: T, want: T) { runs++; if (got != want) { fails++; println("GAGAL: $name -> $got (seharusnya $want)") } }
private fun check(name: String, ok: Boolean) { runs++; if (!ok) { fails++; println("GAGAL: $name") } }

fun main() {
    eq("kueri kosong", TerminalSearch.findRows(listOf("abc"), 0, "").size, 0)
    eq("kueri spasi saja", TerminalSearch.findRows(listOf("abc"), 0, "   ").size, 0)
    eq("tanpa baris", TerminalSearch.findRows(emptyList(), 0, "abc").size, 0)
    eq("max nol", TerminalSearch.findRows(listOf("abc"), 0, "a", 0).size, 0)
    eq("tak cocok", TerminalSearch.findRows(listOf("hello", "world"), 0, "xyz").size, 0)

    val rows = listOf("user@host:~$ make", "error: Foo bar", "ok", "FOO again")
    val h = TerminalSearch.findRows(rows, -2, "foo")
    eq("jumlah tanpa huruf besar-kecil", h.size, 2)
    eq("terbaru dulu: baris layar 1", h[0].row, 1)
    eq("lalu baris layar -1 (firstRow -2 + indeks 1)", h[1].row, -1)
    eq("kolom baris FOO", h[0].col1, 0)
    eq("kolom akhir FOO", h[0].col2, 3)
    eq("kolom Foo di tengah", h[1].col1, 7)
    eq("kolom akhir Foo", h[1].col2, 10)
    eq("rentang char cuplikan", h[1].snippet.substring(h[1].start, h[1].end), "Foo")

    eq("kueri dipangkas", TerminalSearch.findRows(rows, 0, "  ok  ")[0].row, 2)
    eq("titik literal, bukan regex", TerminalSearch.findRows(listOf("axb", "a.b"), 0, "a.b").size, 1)
    eq("kurung literal", TerminalSearch.findRows(listOf("f(x)", "fx"), 0, "(x)").size, 1)
    eq("satu hasil per baris", TerminalSearch.findRows(listOf("foo foo foo"), 0, "foo").size, 1)
    eq("spasi di akhir baris diabaikan", TerminalSearch.findRows(listOf("abc      "), 0, "abc")[0].col2, 3)

    val lines = (1..10).map { "match $it" }
    eq("batas max", TerminalSearch.findRows(lines, 0, "match", 3).map { it.row }, listOf(9, 8, 7))

    val wide = TerminalSearch.findRows(listOf("日本 foo"), 0, "foo")[0]
    eq("kolom setelah karakter lebar", wide.col1, 5)
    eq("lebar kecocokan ASCII", wide.col2, 8)
    eq("lebar CJK", TerminalSearch.displayWidth("日本"), 4)
    eq("kombinasi bernilai 0", TerminalSearch.displayWidth("e\u0301"), 1)
    eq("ASCII", TerminalSearch.displayWidth("abc"), 3)

    val long = "x".repeat(500) + "NEEDLE" + "y".repeat(500)
    val lh = TerminalSearch.findRows(listOf(long), 0, "needle")[0]
    check("baris panjang dipotong", lh.snippet.length < 300)
    check("kecocokan tetap di rentang cuplikan", lh.snippet.substring(lh.start, lh.end).equals("NEEDLE", ignoreCase = true))
    check("elipsis kiri dan kanan", lh.snippet.startsWith("\u2026") && lh.snippet.endsWith("\u2026"))
    eq("kolom baris panjang", lh.col1, 500)
    eq("kolom akhir baris panjang", lh.col2, 506)

    val edge = "NEEDLE" + "y".repeat(500)
    val eh = TerminalSearch.findRows(listOf(edge), 0, "needle")[0]
    check("kecocokan di awal: tanpa elipsis kiri", !eh.snippet.startsWith("\u2026"))
    eq("kecocokan di awal tetap benar", eh.snippet.substring(eh.start, eh.end), "NEEDLE")

    eq("kueri dipotong ke 200", TerminalSearch.findRows(listOf("a".repeat(300), "a".repeat(200)), 0, "a".repeat(300))[0].row, 1)

    println("Uji TerminalSearch: $runs pemeriksaan, $fails gagal")
    if (fails > 0) kotlin.system.exitProcess(1)
}
