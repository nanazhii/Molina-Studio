import com.molina.code.format.BuiltinPlan
import com.molina.code.format.ExternalPlan
import com.molina.code.format.FormatException
import com.molina.code.format.FragmentIndent
import com.molina.code.format.Formatters
import com.molina.code.format.JsonFormatter
import com.molina.code.format.XmlFormatter

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun fmtError(block: () -> Unit): String? = try { block(); null } catch (e: FormatException) { e.message }

fun main() {
    // ---- JSON
    val j1 = JsonFormatter.format("{\"b\":1,\"a\":[1,2,{\"x\":null}],\"e\":{},\"f\":[]}")
    val j1Want = "{\n  \"b\": 1,\n  \"a\": [\n    1,\n    2,\n    {\n      \"x\": null\n    }\n  ],\n  \"e\": {},\n  \"f\": []\n}\n"
    check("json: tata ulang dasar", j1 == j1Want)
    check("json: idempoten", JsonFormatter.format(j1) == j1)
    val raw = "{\"n\":1.0e5,\"s\":\"\\u00e9\\\"x\\\\\",\"neg\":-0.50}"
    val j2 = JsonFormatter.format(raw)
    check("json: angka dan escape disalin apa adanya", j2.contains("1.0e5") && j2.contains("\"\\u00e9\\\"x\\\\\"") && j2.contains("-0.50"))
    check("json: urutan kunci terjaga", JsonFormatter.format("{\"z\":1,\"a\":2}").indexOf("\"z\"") < JsonFormatter.format("{\"z\":1,\"a\":2}").indexOf("\"a\""))
    check("json: nilai tunggal", JsonFormatter.format("  42 ") == "42\n")
    check("json: BOM dibuang", JsonFormatter.format("\uFEFF[]") == "[]\n")
    check("json: tab indent 4 spasi", JsonFormatter.format("[1]", "    ") == "[\n    1\n]\n")
    check("json: koma penutup ditolak", fmtError { JsonFormatter.format("[1,]") } != null)
    check("json: koma penutup objek ditolak", fmtError { JsonFormatter.format("{\"a\":1,}") } != null)
    check("json: komentar ditolak", fmtError { JsonFormatter.format("// x\n{}") } != null)
    check("json: kutip tunggal ditolak", fmtError { JsonFormatter.format("{'a':1}") } != null)
    check("json: tanpa titik dua ditolak", fmtError { JsonFormatter.format("{\"a\" 1}") } != null)
    check("json: kunci bukan string ditolak", fmtError { JsonFormatter.format("{a:1}") } != null)
    check("json: tak tertutup ditolak", fmtError { JsonFormatter.format("{\"a\":[1,2}") } != null)
    check("json: belum lengkap ditolak", fmtError { JsonFormatter.format("{\"a\":1") } != null)
    check("json: dua nilai akar ditolak", fmtError { JsonFormatter.format("1 2") } != null)
    check("json: string tak ditutup ditolak", fmtError { JsonFormatter.format("{\"a\":\"x}") } != null)
    check("json: angka 01 ditolak", fmtError { JsonFormatter.format("[01]") } != null)
    check("json: kosong ditolak", fmtError { JsonFormatter.format("   ") } != null)
    check("json: pesan memuat baris:kolom", fmtError { JsonFormatter.format("{\n\"a\":,\n}") }?.contains("baris 2") == true)
    val deep = "[".repeat(300) + "]".repeat(300)
    check("json: terlalu dalam ditolak tanpa crash", fmtError { JsonFormatter.format(deep) } != null)
    val deepOk = "[".repeat(200) + "]".repeat(200)
    check("json: kedalaman 200 lolos", fmtError { JsonFormatter.format(deepOk) } == null)

    // ---- XML
    val layout = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\">\n<!-- judul -->\n<TextView\n        android:id=\"@+id/t\"\n   android:text=\"a > b\" />\n<LinearLayout>\n\n<Button/>\n</LinearLayout>\n</LinearLayout>\n"
    val x1 = XmlFormatter.format(layout)
    val x1Want = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
        "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
        "  <!-- judul -->\n" +
        "  <TextView\n" +
        "      android:id=\"@+id/t\"\n" +
        "      android:text=\"a > b\" />\n" +
        "  <LinearLayout>\n" +
        "    <Button/>\n" +
        "  </LinearLayout>\n" +
        "</LinearLayout>\n"
    check("xml: tata ulang layout Android", x1 == x1Want)
    check("xml: idempoten", XmlFormatter.format(x1) == x1)
    val mixed = "<resources><string name=\"a\">  Halo <b>dunia</b>\n  lagi </string><x/></resources>"
    val x2 = XmlFormatter.format(mixed)
    check("xml: konten campuran disalin apa adanya", x2.contains("<string name=\"a\">  Halo <b>dunia</b>\n  lagi </string>"))
    check("xml: elemen saudara tetap ditata", x2 == "<resources>\n  <string name=\"a\">  Halo <b>dunia</b>\n  lagi </string>\n  <x/>\n</resources>\n")
    val cdata = XmlFormatter.format("<a><![CDATA[ <x> ]]></a>")
    check("xml: CDATA utuh", cdata == "<a><![CDATA[ <x> ]]></a>\n")
    val preserve = XmlFormatter.format("<a xml:space=\"preserve\">\n  <b/>\n</a>")
    check("xml: xml:space preserve utuh", preserve == "<a xml:space=\"preserve\">\n  <b/>\n</a>\n")
    val doctype = XmlFormatter.format("<!DOCTYPE a [<!ENTITY e \"x>y\">]><a/>")
    check("xml: doctype subset internal", doctype == "<!DOCTYPE a [<!ENTITY e \"x>y\">]>\n<a/>\n")
    check("xml: elemen kosong satu baris", XmlFormatter.format("<a>\n</a>") == "<a></a>\n")
    check("xml: tag penutup tak cocok ditolak", fmtError { XmlFormatter.format("<a><b></a></b>") } != null)
    check("xml: tag tak ditutup ditolak", fmtError { XmlFormatter.format("<a><b></b>") }?.contains("tidak ditutup") == true)
    check("xml: penutup tanpa pembuka ditolak", fmtError { XmlFormatter.format("</a>") } != null)
    check("xml: teks di luar akar ditolak", fmtError { XmlFormatter.format("halo <a/>") } != null)
    check("xml: komentar tak ditutup ditolak", fmtError { XmlFormatter.format("<a><!-- x </a>") } != null)
    check("xml: terlalu dalam ditolak tanpa crash", fmtError { XmlFormatter.format("<a>".repeat(400) + "</a>".repeat(400)) } != null)
    check("xml: kosong ditolak", fmtError { XmlFormatter.format("") } != null)

    // ---- Pemilih formatter
    check("pilih: json bawaan", Formatters.of("a.json", null) is BuiltinPlan)
    check("pilih: svg bawaan", Formatters.of("a.SVG", null) is BuiltinPlan)
    val py = Formatters.of("/x/a.py", null) as ExternalPlan
    check("pilih: py = black stdin", py.argv == listOf("black", "-q", "-"))
    val sh = Formatters.of("a.sh", null) as ExternalPlan
    check("pilih: sh = shfmt bash", sh.label == "shfmt" && sh.argv.takeLast(2) == listOf("-ln", "bash"))
    val posix = Formatters.of(null, "#!/bin/sh") as ExternalPlan
    check("pilih: shebang sh = shfmt posix", posix.argv.takeLast(1) == listOf("posix"))
    val pyShebang = Formatters.of("tanpa-ekstensi", "#!/usr/bin/env python3") as ExternalPlan
    check("pilih: shebang python = black", pyShebang.label == "black")
    val pr = Formatters.of("app.TSX", null) as ExternalPlan
    check("pilih: tsx = prettier dengan nama berkas", pr.argv == listOf("prettier", "--stdin-filepath=app.TSX"))
    val pr2 = Formatters.of("/a/b/style.css", null) as ExternalPlan
    check("pilih: nama berkas tanpa folder", pr2.argv[1] == "--stdin-filepath=style.css")
    check("pilih: kt belum didukung", Formatters.of("a.kt", null) == null)
    check("pilih: tanpa ekstensi belum didukung", Formatters.of("Baru", null) == null)

    // ---- Format seleksi: jorokan bersama
    val frag = "    def a():\n        return 1\n\n    x=2"
    check("indent: awalan bersama terdeteksi", FragmentIndent.common(frag) == "    ")
    check("indent: baris kosong diabaikan", FragmentIndent.common("  a\n\n  b") == "  ")
    check("indent: tab dan spasi campur = awalan sama saja", FragmentIndent.common("\ta\n  b") == "")
    check("indent: tanpa jorokan", FragmentIndent.common("a\n  b") == "")
    val dd = FragmentIndent.dedent(frag, "    ")
    check("indent: dedent melepas awalan", dd == "def a():\n    return 1\n\nx=2")
    check("indent: bolak-balik identik", FragmentIndent.reindent(dd, "    ") == frag)
    check("indent: baris kosong tak diberi spasi sisa", !FragmentIndent.reindent("a\n\nb", "  ").contains("  \n"))
    val nestedJson = "    {\"a\":1,\"b\":[1,2]}"
    val nf = FragmentIndent.reindent(JsonFormatter.format(FragmentIndent.dedent(nestedJson, "    ")).trimEnd('\n'), "    ")
    check("indent: potongan JSON menjorok diformat dan dijorokkan lagi", nf == "    {\n      \"a\": 1,\n      \"b\": [\n        1,\n        2\n      ]\n    }")

    if (fails > 0) { println("\n$fails GAGAL"); System.exit(1) } else println("\nsemua lulus")
}
