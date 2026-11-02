// Uji JVM murni untuk com.molina.code.preview (tanpa Android/Gradle).
// Jalankan: bash scripts/code-tests/run.sh
import com.molina.code.preview.*
import kotlin.random.Random

var failed = 0
var total = 0
fun check(name: String, ok: Boolean, detail: () -> String = { "" }) {
    total++
    if (!ok) { failed++; println("GAGAL: $name ${detail()}") }
}
fun <T> eq(name: String, a: T, b: T) = check(name, a == b) { "diharapkan=$b hasil=$a" }

fun spansText(s: List<MdSpan>) = s.joinToString("") { it.text }

val rnd0 = Random(7)

fun main() {
    // --- PreviewKinds / RunPlans
    eq("md", PreviewKinds.of("a.md"), PreviewKind.MARKDOWN)
    eq("MD upper", PreviewKinds.of("README.MD"), PreviewKind.MARKDOWN)
    eq("json", PreviewKinds.of("x.json"), PreviewKind.JSON)
    eq("sh", PreviewKinds.of("x.sh"), PreviewKind.SCRIPT)
    eq("py", PreviewKinds.of("x.py"), PreviewKind.SCRIPT)
    eq("kt none", PreviewKinds.of("x.kt"), PreviewKind.NONE)
    eq("no ext", PreviewKinds.of("Makefile"), PreviewKind.NONE)
    eq("untitled none", PreviewKinds.of(null, null), PreviewKind.NONE)
    eq("untitled shebang", PreviewKinds.of(null, "#!/bin/bash"), PreviewKind.SCRIPT)
    eq("untitled shebang env py", PreviewKinds.of("Baru", "#!/usr/bin/env python3"), PreviewKind.SCRIPT)
    eq("shebang unknown", PreviewKinds.of("Baru", "#!/usr/bin/perl"), PreviewKind.NONE)
    eq("plan sh ext -> bash", RunPlans.of("a.sh", "echo hi")?.interpreter, "/bin/bash")
    eq("plan shebang sh", RunPlans.of("a.sh", "#!/bin/sh")?.interpreter, "/bin/sh")
    eq("plan shebang beats ext", RunPlans.of("a.py", "#!/bin/bash")?.interpreter, "/bin/bash")
    eq("plan env -S", RunPlans.of(null, "#!/usr/bin/env -S python3 -u")?.interpreter, "/usr/bin/python3")
    eq("plan python3.12", RunPlans.of(null, "#!/usr/bin/python3.12")?.interpreter, "/usr/bin/python3")
    check("plan py needs python", RunPlans.of("a.py", null)?.needsPython == true)
    check("plan bash not python", RunPlans.of("a.sh", null)?.needsPython == false)
    eq("plan none", RunPlans.of("a.txt", "hello") == null, true)
    eq("plan env only", RunPlans.fromShebang("#!/usr/bin/env") == null, true)

    // --- GuestPaths
    val root = "/storage/emulated/0"
    eq("gp ws file", GuestPaths.of("$root/Molina-Kerja/a/b.py", root, "Molina-Kerja", false), "/sdcard/Molina-Kerja/a/b.py")
    eq("gp ws dir", GuestPaths.of("$root/Molina-Kerja", root, "Molina-Kerja", false), "/sdcard/Molina-Kerja")
    eq("gp outside ws", GuestPaths.of("$root/Download/x.sh", root, "Molina-Kerja", false), null)
    eq("gp prefix trap", GuestPaths.of("$root/Molina-Kerja2/x", root, "Molina-Kerja", false), null)
    eq("gp full", GuestPaths.of("$root/Download/x.sh", root, "Molina-Kerja", true), "/sdcard/Download/x.sh")
    eq("gp full root", GuestPaths.of(root, root, "Molina-Kerja", true), "/sdcard")
    eq("gp dotdot", GuestPaths.of("$root/Molina-Kerja/../x", root, "Molina-Kerja", true), null)
    eq("gp other root", GuestPaths.of("/data/x", root, "Molina-Kerja", true), null)
    eq("gp extra root", GuestPaths.of("$root/Molina/Media/Video", root, "Molina-Kerja", false, listOf("Molina")), "/sdcard/Molina/Media/Video")
    eq("gp extra prefix trap", GuestPaths.of("$root/Molina2/x", root, "Molina-Kerja", false, listOf("Molina")), null)

    // --- OutputBuffer
    run {
        val b = OutputBuffer()
        b.append("halo\ndunia", false)
        eq("ob partial", b.snapshot().map { it.text }, listOf("halo", "dunia"))
        b.append("!\n", false)
        eq("ob finish", b.snapshot().map { it.text }, listOf("halo", "dunia!"))
    }
    run {
        val b = OutputBuffer()
        b.append("a\r\nb\r\n", false)
        eq("ob crlf", b.snapshot().map { it.text }, listOf("a", "b"))
    }
    run {
        val b = OutputBuffer()
        b.append("10%\r50%\r100%\ndone\n", false)
        eq("ob progress cr", b.snapshot().map { it.text }, listOf("100%", "done"))
    }
    run {
        val b = OutputBuffer()
        b.append("ab\r", false); b.append("cd\n", false)
        eq("ob cr across chunks", b.snapshot().map { it.text }, listOf("cd"))
    }
    run {
        val b = OutputBuffer()
        b.append("\u001B[31mmerah\u001B[0m \u001B]0;judul\u0007ok\u001B]8;;\u001B\\x\n", false)
        eq("ob ansi", b.snapshot().map { it.text }, listOf("merah okx"))
    }
    run {
        val b = OutputBuffer()
        b.append("out1\n", false); b.append("err1\n", true); b.append("o", false); b.append("e", true)
        val s = b.snapshot()
        eq("ob streams", s.map { it.text to it.error }, listOf("out1" to false, "err1" to true, "o" to false, "e" to true))
    }
    run {
        val b = OutputBuffer(maxLines = 3, maxLineChars = 10)
        for (k in 1..10) b.append("baris$k\n", false)
        eq("ob maxlines", b.snapshot().map { it.text }, listOf("baris8", "baris9", "baris10"))
        eq("ob dropped", b.droppedLines, 7L)
        val c = OutputBuffer(maxLines = 5, maxLineChars = 10)
        c.append("x".repeat(100) + "\n", false)
        check("ob line clip", c.snapshot()[0].text.length <= 11 && c.snapshot()[0].text.endsWith("\u2026"))
    }
    run {
        val b = OutputBuffer()
        b.append("a\tb\u0007\u0008c\n", false)
        eq("ob tab/ctrl", b.snapshot().map { it.text }, listOf("a    bc"))
        b.clear()
        eq("ob clear", b.snapshot().size, 0)
    }
    run { // fuzz: tidak melempar, batas terjaga
        val rnd = Random(7)
        val b = OutputBuffer(maxLines = 50, maxLineChars = 80)
        repeat(3000) {
            val n = rnd.nextInt(0, 40)
            val sb = StringBuilder()
            repeat(n) { sb.append(listOf('a', 'Z', '\n', '\r', '\u001B', '[', ']', '\u0007', '\\', 'm', '0', ' ', '\t', 'é')[rnd.nextInt(14)]) }
            b.append(sb.toString(), rnd.nextBoolean())
            val s = b.snapshot()
            check("ob fuzz size", s.size <= 51 && s.all { it.text.length <= 81 })
        }
    }

    // --- Markdown blok
    run {
        val md = """
# Judul Satu
Paragraf **tebal** dan *miring* dengan `kode`.
Lanjut baris dua.

## Sub ##
---
> kutipan satu
> kutipan dua

- item a
  - anak b
- [ ] tugas
- [x] selesai
1. satu
2. dua

```kotlin
val x = 1
```

| Nama | Nilai |
|------|-------|
| a    | 1     |
| b    | 2     |

Penutup.
""".trimIndent()
        val b = Markdown.parse(md)
        val kinds = b.map { it::class.simpleName }
        eq("md kinds", kinds, listOf(
            "Heading", "Paragraph", "Heading", "Rule", "Quote",
            "Item", "Item", "Item", "Item", "Item", "Item", "Code", "Table", "Paragraph"
        ))
        val h1 = b[0] as MdBlock.Heading
        eq("md h1 level", h1.level, 1)
        eq("md h1 text", spansText(h1.spans), "Judul Satu")
        eq("md h2 trailing #", spansText((b[2] as MdBlock.Heading).spans), "Sub")
        val p = b[1] as MdBlock.Paragraph
        eq("md para soft break", spansText(p.spans), "Paragraf tebal dan miring dengan kode. Lanjut baris dua.")
        check("md bold", p.spans.any { it.text == "tebal" && it.flags == MdFlag.BOLD })
        check("md italic", p.spans.any { it.text == "miring" && it.flags == MdFlag.ITALIC })
        check("md code", p.spans.any { it.text == "kode" && it.flags == MdFlag.CODE })
        eq("md quote joined", spansText((b[4] as MdBlock.Quote).spans), "kutipan satu kutipan dua")
        val it1 = b[6] as MdBlock.Item
        eq("md nested depth", it1.depth, 1)
        eq("md task unchecked", (b[7] as MdBlock.Item).checked, false)
        eq("md task checked", (b[8] as MdBlock.Item).checked, true)
        eq("md ordered marker", (b[9] as MdBlock.Item).marker, "1.")
        eq("md bullet marker", (b[5] as MdBlock.Item).marker, "\u2022")
        val code = b[11] as MdBlock.Code
        eq("md code lang", code.lang, "kotlin")
        eq("md code body", code.text, "val x = 1")
        val t = b[12] as MdBlock.Table
        eq("md table header", t.header.map { spansText(it) }, listOf("Nama", "Nilai"))
        eq("md table rows", t.rows.map { r -> r.map { spansText(it) } }, listOf(listOf("a", "1"), listOf("b", "2")))
    }
    run { // inline
        val s = Inline.parse("lihat [situs](https://contoh.id/x) dan ~~coret~~ serta snake_case_name dan 2*3*4")
        check("inl link", s.any { it.text == "situs" && it.href == "https://contoh.id/x" && it.flags and MdFlag.LINK != 0 })
        check("inl strike", s.any { it.text == "coret" && it.flags and MdFlag.STRIKE != 0 })
        check("inl snake literal", spansText(s).contains("snake_case_name"))
        val t = Inline.parse("a * b dan **tak tertutup")
        eq("inl stray stars literal", spansText(t), "a * b dan **tak tertutup")
        val bi = Inline.parse("***tebal miring***")
        check("inl bold+italic", bi.size == 1 && bi[0].flags == (MdFlag.BOLD or MdFlag.ITALIC))
        val nested = Inline.parse("**tebal *miring* lagi**")
        check("inl nested", nested.any { it.text == "miring" && it.flags == (MdFlag.BOLD or MdFlag.ITALIC) })
        val esc = Inline.parse("\\*bukan miring\\*")
        eq("inl escape", spansText(esc), "*bukan miring*")
        val auto = Inline.parse("buka https://contoh.id/a, lalu <https://contoh.id/b>.")
        check("inl bare url", auto.any { it.href == "https://contoh.id/a" })
        check("inl autolink", auto.any { it.href == "https://contoh.id/b" })
        check("inl bare url trailing comma", auto.none { it.href?.endsWith(",") == true })
        val img = Inline.parse("![logo](a.png) teks")
        check("inl image placeholder", spansText(img).startsWith("[gambar: logo]"))
        val dbl = Inline.parse("kode `` a`b `` selesai")
        check("inl double backtick", dbl.any { it.text == "a`b" && it.flags and MdFlag.CODE != 0 })
        val un = Inline.parse("tanda ` tunggal")
        eq("inl unmatched backtick", spansText(un), "tanda ` tunggal")
    }
    run { // fenced tidak tertutup, kosong, CRLF, hr vs list
        eq("md empty", Markdown.parse("").size, 0)
        eq("md blank only", Markdown.parse("\n\n  \n").size, 0)
        val open = Markdown.parse("```py\nprint(1)\nprint(2)")
        eq("md unclosed fence", (open[0] as MdBlock.Code).text, "print(1)\nprint(2)")
        val crlf = Markdown.parse("# A\r\n\r\nteks\r\n")
        eq("md crlf", crlf.map { it::class.simpleName }, listOf("Heading", "Paragraph"))
        eq("md hr star", Markdown.parse("* * *")[0] is MdBlock.Rule, true)
        eq("md list star", Markdown.parse("* a")[0] is MdBlock.Item, true)
        val hardBreak = Markdown.parse("baris satu  \nbaris dua")
        check("md hard break", spansText((hardBreak[0] as MdBlock.Paragraph).spans).contains("\n"))
        val tblNoPipeEdge = Markdown.parse("a | b\n--|--\n1 | 2")
        eq("md table no edge pipes", tblNoPipeEdge[0] is MdBlock.Table, true)
        val ragged = Markdown.parse("| a | b | c |\n|---|---|---|\n| 1 |")
        eq("md table ragged padded", (ragged[0] as MdBlock.Table).rows[0].size, 3)
        val notTable = Markdown.parse("a | b\nteks biasa")
        eq("md pipe not table", notTable[0] is MdBlock.Paragraph, true)
    }
    run { // fuzz Markdown: tidak melempar dan selesai cepat
        val rnd = Random(11)
        val alphabet = listOf("#", "##", "*", "**", "_", "`", "```", "~~", "[", "]", "(", ")", "!", "|", "-", "---", ">", "1.", " ", "  ", "\n", "\n\n", "a", "bc", "http://x.y", "<", ">", "\\", "\t", "[ ]", "[x]")
        val t0 = System.nanoTime()
        repeat(4000) {
            val sb = StringBuilder()
            repeat(rnd.nextInt(0, 60)) { sb.append(alphabet[rnd.nextInt(alphabet.size)]) }
            try { Markdown.parse(sb.toString()) } catch (e: Throwable) { check("md fuzz no throw", false) { "${e}: ${sb}" } }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        check("md fuzz speed", ms < 5000) { "${ms}ms" }
        val big = ("# Judul\n\nParagraf **tebal** [x](y) `kode`.\n\n- a\n- b\n\n").repeat(5000)
        val t1 = System.nanoTime()
        val blocks = Markdown.parse(big)
        val ms2 = (System.nanoTime() - t1) / 1_000_000
        check("md big speed", ms2 < 3000 && blocks.size > 10000) { "${ms2}ms ${blocks.size}" }
        val pathological = "*".repeat(20000) + "a"
        val t2 = System.nanoTime()
        Inline.parse(pathological)
        check("md inline pathological speed", (System.nanoTime() - t2) / 1_000_000 < 3000)
        val pathological2 = "[".repeat(5000) + "](" .repeat(2000)
        val t3 = System.nanoTime()
        Inline.parse(pathological2)
        check("md inline brackets speed", (System.nanoTime() - t3) / 1_000_000 < 3000)
    }


    run { // posisi baris awal blok
        val doc = Markdown.parseWithLines("# A\n\npar satu\nlanjut\n\n- x\n- y\n\n```\nkode\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\nakhir")
        eq("mdl size", doc.startLines.size, doc.blocks.size)
        eq("mdl starts", doc.startLines, listOf(0, 2, 5, 6, 8, 12, 16))
        check("mdl sorted", doc.startLines.zipWithNext().all { it.first < it.second })
        eq("mdl empty", Markdown.parseWithLines("").startLines.size, 0)
        val rnd2 = Random(3)
        repeat(2000) {
            val sb = StringBuilder()
            repeat(rnd2.nextInt(0, 50)) { sb.append(listOf("#", "- ", "\n", "\n\n", "a", "```\n", "> ", "| a |\n|-|\n", " ")[rnd2.nextInt(9)]) }
            val d = Markdown.parseWithLines(sb.toString())
            check("mdl fuzz aligned", d.startLines.size == d.blocks.size && d.startLines.zipWithNext().all { it.first <= it.second })
        }
    }


    // --- HTML
    fun hb(html: String) = HtmlPreview.parse(html).blocks
    fun htext(b: MdBlock): String = when (b) {
        is MdBlock.Heading -> spansText(b.spans)
        is MdBlock.Paragraph -> spansText(b.spans)
        is MdBlock.Quote -> spansText(b.spans)
        is MdBlock.Item -> spansText(b.spans)
        is MdBlock.Code -> b.text
        else -> ""
    }
    run {
        val html = """<!DOCTYPE html>
<html><head><title>Judul Halaman</title><style>p{color:red}</style><script>var x = "<p>bukan</p>";</script></head>
<body>
<h1>Selamat   Datang</h1>
<p>Halo <b>dunia</b> dan <i>semua</i>, lihat <a href="https://contoh.id/a?x=1&amp;y=2">tautan ini</a>.<br>Baris baru &amp; &copy; &#65;&#x42;.</p>
<ul><li>satu</li><li>dua<ul><li>anak</li></ul></li></ul>
<ol><li>pertama</li><li>kedua</li></ol>
<blockquote>kutipan <em>miring</em></blockquote>
<hr>
<pre>baris1
  baris2 &lt;tag&gt;</pre>
<table><thead><tr><th>Nama</th><th>Nilai</th></tr></thead>
<tbody><tr><td>a</td><td>1</td></tr><tr><td>b</td><td><b>2</b></td></tr></tbody></table>
<div>akhir <del>coret</del> <code>kode</code></div>
</body></html>"""
        val b = hb(html)
        eq("html kinds", b.map { it::class.simpleName }, listOf(
            "Heading", "Paragraph", "Item", "Item", "Item", "Item", "Item", "Quote", "Rule", "Code", "Table", "Paragraph"))
        eq("html h1", htext(b[0]), "Selamat Datang")
        eq("html h1 level", (b[0] as MdBlock.Heading).level, 1)
        val p1 = b[1] as MdBlock.Paragraph
        eq("html para text", spansText(p1.spans), "Halo dunia dan semua, lihat tautan ini.\nBaris baru & \u00A9 AB.")
        check("html bold", p1.spans.any { it.text == "dunia" && it.flags == MdFlag.BOLD })
        check("html italic", p1.spans.any { it.text == "semua" && it.flags == MdFlag.ITALIC })
        check("html link", p1.spans.any { it.text == "tautan ini" && it.href == "https://contoh.id/a?x=1&y=2" && it.flags and MdFlag.LINK != 0 })
        check("html script skipped", b.none { htext(it).contains("bukan") || htext(it).contains("color") || htext(it).contains("Judul Halaman") })
        eq("html li 1", (b[2] as MdBlock.Item).depth to htext(b[2]), 0 to "satu")
        eq("html li nested order", listOf(htext(b[3]), htext(b[4])), listOf("dua", "anak"))
        eq("html li nested depth", (b[4] as MdBlock.Item).depth, 1)
        eq("html ol markers", listOf((b[5] as MdBlock.Item).marker, (b[6] as MdBlock.Item).marker), listOf("1.", "2."))
        eq("html quote", htext(b[7]), "kutipan miring")
        eq("html pre", (b[9] as MdBlock.Code).text, "baris1\n  baris2 <tag>")
        val t = b[10] as MdBlock.Table
        eq("html table header", t.header.map { spansText(it) }, listOf("Nama", "Nilai"))
        eq("html table rows", t.rows.map { r -> r.map { spansText(it) } }, listOf(listOf("a", "1"), listOf("b", "2")))
        check("html table bold cell", t.rows[1][1].any { it.flags and MdFlag.BOLD != 0 })
        val last = b[11] as MdBlock.Paragraph
        check("html strike", last.spans.any { it.text == "coret" && it.flags and MdFlag.STRIKE != 0 })
        check("html code", last.spans.any { it.text == "kode" && it.flags and MdFlag.CODE != 0 })
        val d = HtmlPreview.parse(html)
        eq("html lines aligned", d.startLines.size, d.blocks.size)
        check("html lines monotone", d.startLines.zipWithNext().all { it.first <= it.second })
        check("html h1 line", d.startLines[0] == 3) { d.startLines.toString() }
    }
    run { // toleransi dan kasus tepi
        eq("html empty", hb("").size, 0)
        eq("html plain text", htext(hb("hanya teks &lt;3")[0]), "hanya teks <3")
        eq("html unclosed p", hb("<p>satu<p>dua").map { htext(it) }, listOf("satu", "dua"))
        eq("html unclosed table", (hb("<table><tr><td>x") .firstOrNull() as? MdBlock.Table)?.header?.map { spansText(it) }, listOf("x"))
        eq("html stray lt", htext(hb("a < b dan c")[0]), "a < b dan c")
        eq("html unterminated tag", htext(hb("teks <b")[0]), "teks <b")
        eq("html comment", hb("<!-- <p>x</p> --><p>y</p>").map { htext(it) }, listOf("y"))
        eq("html unclosed comment", hb("<p>a</p><!-- x").map { htext(it) }, listOf("a"))
        eq("html unclosed script", hb("<p>a</p><script>alert(1)").map { htext(it) }, listOf("a"))
        eq("html entity unknown", htext(hb("<p>&zzz; &amp</p>")[0]), "&zzz; &amp")
        eq("html entity numeric bad", htext(hb("<p>&#0; &#xD800; &#99999999;</p>")[0]), "&#0; &#xD800; &#99999999;")
        eq("html img alt", htext(hb("<p>a <img src=x.png alt='Logo'> b</p>")[0]), "a [gambar: Logo] b")
        eq("html quote attr gt", htext(hb("<p title=\"a>b\">teks</p>")[0]), "teks")
        eq("html unbalanced quote", hb("<p title=\"x>teks</p><p>dua</p>").isNotEmpty(), true)
        eq("html heading level", (hb("<h4>x</h4>")[0] as MdBlock.Heading).level, 4)
        eq("html a no href", hb("<a>kosong</a>").map { htext(it) }, listOf("kosong"))
        check("html nested table no crash", hb("<table><tr><td><table><tr><td>dalam</td></tr></table></td><td>y</td></tr></table>").isNotEmpty())
        eq("html whitespace collapse", htext(hb("<p>  a \n\n  b\t\tc  </p>")[0]), "a b c")
        eq("html adjacent inline space", htext(hb("<p><b>a</b> <i>b</i></p>")[0]), "a b")
        eq("html entity nbsp spacing", htext(hb("<p>a&nbsp;b</p>")[0]), "a b")
        check("html kind detect", PreviewKinds.of("index.html") == PreviewKind.HTML && PreviewKinds.of("a.HTM") == PreviewKind.HTML)
    }
    run { // fuzz + kecepatan HTML
        val rnd = Random(21)
        val parts = listOf("<p>", "</p>", "<b>", "</b>", "<a href=\"x\">", "</a>", "<ul>", "</ul>", "<li>", "</li>", "<table>", "</table>", "<tr>", "<td>", "</td>", "<th>", "</tr>",
            "<h1>", "</h1>", "<pre>", "</pre>", "<script>", "</script>", "<!--", "-->", "<", ">", "&amp;", "&", "txt ", "\n", "<br>", "<hr>", "<img alt=x>", "<blockquote>", "</blockquote>", "\"", "'", "<ol>", "</ol>", "<style>", "</style>", "<code>", "</code>")
        val t0 = System.nanoTime()
        repeat(4000) {
            val sb = StringBuilder()
            repeat(rnd.nextInt(0, 50)) { sb.append(parts[rnd.nextInt(parts.size)]) }
            try {
                val d = HtmlPreview.parse(sb.toString())
                check("html fuzz aligned", d.startLines.size == d.blocks.size && d.startLines.zipWithNext().all { it.first <= it.second }) { sb.toString() }
            } catch (e: Throwable) { check("html fuzz no throw", false) { "$e: $sb" } }
        }
        check("html fuzz speed", (System.nanoTime() - t0) / 1_000_000 < 8000)
        val big = "<html><body>" + "<h2>Judul</h2><p>Teks <b>tebal</b> <a href=\"https://x.id\">tautan</a></p><ul><li>a</li><li>b</li></ul><table><tr><td>1</td><td>2</td></tr></table>".repeat(4000) + "</body></html>"
        val t1 = System.nanoTime()
        val blocks = HtmlPreview.parse(big).blocks
        val ms = (System.nanoTime() - t1) / 1_000_000
        check("html big speed", ms < 4000 && blocks.size >= 16000) { "${ms}ms ${blocks.size}" }
        val deepTags = "<div>".repeat(50000) + "x"
        val t2 = System.nanoTime(); HtmlPreview.parse(deepTags)
        check("html deep div speed", (System.nanoTime() - t2) / 1_000_000 < 3000)
        val manyLt = "<a ".repeat(20000)
        val t3 = System.nanoTime(); HtmlPreview.parse(manyLt)
        check("html many unterminated speed", (System.nanoTime() - t3) / 1_000_000 < 4000)
        val manyScripts = "<script>".repeat(20000)
        val t4 = System.nanoTime(); HtmlPreview.parse(manyScripts)
        check("html many scripts speed", (System.nanoTime() - t4) / 1_000_000 < 4000)
    }

    // --- JSON
    run {
        val ok = JsonPreview.render("""{"a":1,"b":[true,null,"x\n"],"c":{},"d":[],"e":-1.5e+3}""")
        check("json ok", ok is JsonPretty.Ok)
        ok as JsonPretty.Ok
        eq("json pretty", ok.text, """
{
  "a": 1,
  "b": [
    true,
    null,
    "x\n"
  ],
  "c": {},
  "d": [],
  "e": -1.5e+3
}""".trimIndent())
        eq("json lineCount", ok.lineCount, 11)
        check("json runs cover", ok.runs.all { it.start < it.end && it.end <= ok.text.length })
        check("json key kind", ok.runs.any { it.kind == JsonTok.KEY && ok.text.substring(it.start, it.end) == "\"a\"" })
        eq("json scalar doc", (JsonPreview.render("42") as JsonPretty.Ok).text, "42")
    }
    fun bad(name: String, src: String, line: Int, col: Int) {
        val r = JsonPreview.render(src)
        check("json bad $name type", r is JsonPretty.Invalid)
        if (r is JsonPretty.Invalid) {
            check("json bad $name pos", r.line == line && r.column == col) { "dapat ${r.line}:${r.column} (${r.message}) diharapkan $line:$col" }
        }
    }
    bad("empty", "", 1, 1)
    bad("trailing comma obj", "{\"a\":1,}", 1, 8)
    bad("trailing comma arr", "[1,2,]", 1, 6)
    bad("single quote", "{'a':1}", 1, 2)
    bad("missing colon", "{\"a\" 1}", 1, 6)
    bad("unterminated string", "{\"a\":\"x", 1, 6)
    bad("leading zero", "[01]", 1, 3)
    bad("bare dot", "[1.]", 1, 4)
    bad("garbage after", "{} x", 1, 4)
    bad("bad escape", "[\"\\q\"]", 1, 3)
    bad("bad unicode", "[\"\\u12G4\"]", 1, 3)
    bad("literal typo", "[tru]", 1, 2)
    bad("literal glued", "[nullx]", 1, 2)
    bad("control char", "[\"a\nb\"]", 1, 4)
    bad("multi-line pos", "{\n  \"a\": 1,\n  \"b\": ]\n}", 3, 8)
    bad("comment", "{/*x*/}", 1, 2)
    bad("nan", "[NaN]", 1, 2)
    bad("unexpected end", "{\"a\":", 1, 6)
    run {
        val deep = "[".repeat(500) + "]".repeat(500)
        check("json deep rejected not crash", JsonPreview.render(deep) is JsonPretty.Invalid)
        val big = "[" + "1,".repeat(150_000) + "1]"
        check("json too large", JsonPreview.render(big) is JsonPretty.TooLarge)
        val inv = JsonPreview.render("{\n  \"a\": oops\n}") as JsonPretty.Invalid
        eq("json snippet", inv.snippet, "  \"a\": oops")
    }
    run { // properti: hasil cetak rapi valid dan setara dengan sumber (tanpa spasi di luar string)
        val rnd = Random(5)
        fun gen(d: Int): String = when (if (d > 3) rnd.nextInt(4) else rnd.nextInt(6)) {
            0 -> rnd.nextInt(-1000, 1000).toString()
            1 -> "\"s${rnd.nextInt(100)}\\n\\u00e9\""
            2 -> listOf("true", "false", "null")[rnd.nextInt(3)]
            3 -> "${rnd.nextInt(10)}.${rnd.nextInt(100)}e${rnd.nextInt(-5, 5)}"
            4 -> "[" + (0 until rnd.nextInt(0, 4)).joinToString(",") { gen(d + 1) } + "]"
            else -> "{" + (0 until rnd.nextInt(0, 4)).joinToString(",") { "\"k$it\":" + gen(d + 1) } + "}"
        }
        fun strip(s: String): String {
            val sb = StringBuilder(); var inStr = false; var k = 0
            while (k < s.length) {
                val c = s[k]
                if (inStr) { sb.append(c); if (c == '\\') { sb.append(s[k + 1]); k++ } else if (c == '"') inStr = false }
                else if (c == '"') { inStr = true; sb.append(c) } else if (!c.isWhitespace()) sb.append(c)
                k++
            }
            return sb.toString()
        }
        repeat(500) {
            val src = gen(0)
            val r = JsonPreview.render(src)
            check("json prop valid", r is JsonPretty.Ok) { src }
            if (r is JsonPretty.Ok) {
                eq("json prop equal", strip(r.text), strip(src))
                check("json prop idempotent", (JsonPreview.render(r.text) as? JsonPretty.Ok)?.text == r.text) { src }
            }
        }
        repeat(3000) { // fuzz: tidak melempar
            val alphabet = "{}[],:\"\\ 0123456789.eE+-truefalsn\n\tu".toList()
            val s = String(CharArray(rnd.nextInt(0, 30)) { alphabet[rnd.nextInt(alphabet.size)] })
            try { JsonPreview.render(s) } catch (e: Throwable) { check("json fuzz no throw", false) { "$e: $s" } }
        }
    }

    // --- ScriptLaunch (Script Execution)
    eq("lines empty", ScriptLaunch.countLines(""), 0)
    eq("lines one", ScriptLaunch.countLines("a"), 1)
    eq("lines trailing lf", ScriptLaunch.countLines("a\nb\n"), 2)
    eq("lines no trailing", ScriptLaunch.countLines("a\nb"), 2)
    eq("lines crlf", ScriptLaunch.countLines("a\r\nb\r\n"), 2)
    eq("lines cr only", ScriptLaunch.countLines("a\rb\rc"), 3)
    eq("lines blank lines", ScriptLaunch.countLines("\n\n"), 2)
    eq("lines 3000 ok", ScriptLaunch.countLines("x\n".repeat(3000)), 3000)
    check("lines 3001 over", ScriptLaunch.countLines("x\n".repeat(3001)) > ScriptLaunch.MAX_LINES)
    eq("eol crlf", ScriptLaunch.normalizeEol("a\r\nb\rc\n"), "a\nb\nc\n")
    eq("eol same instance", ScriptLaunch.normalizeEol("a\nb"), "a\nb")
    eq("plan title py", ScriptLaunch.planFor("x.py", "print(1)").interpreter, "/usr/bin/python3")
    eq("plan title sh", ScriptLaunch.planFor("x.sh", "echo").interpreter, "/bin/bash")
    eq("plan default bash", ScriptLaunch.planFor("Baru", "echo hi").interpreter, "/bin/bash")
    eq("plan shebang python", ScriptLaunch.planFor("Baru", "#!/usr/bin/env python3\nprint(1)").interpreter, "/usr/bin/python3")
    eq("plan shebang crlf", ScriptLaunch.planFor("Baru", "#!/bin/sh\r\necho").interpreter, "/bin/sh")
    val bashPlan = RunPlans.of("a.sh", null)!!
    val pyPlan = RunPlans.of("a.py", null)!!
    eq("fname basic", ScriptLaunch.fileName("ab12", "Backup Harian.sh", bashPlan), "backup-harian-ab12.sh")
    eq("fname py ext", ScriptLaunch.fileName("ab12", "tool.py", pyPlan), "tool-ab12.py")
    eq("fname empty", ScriptLaunch.fileName("ab12", "   ", bashPlan), "skrip-ab12.sh")
    eq("fname symbols", ScriptLaunch.fileName("ab12", "../../etc/passwd", bashPlan), "etc-passwd-ab12.sh")
    eq("fname unicode", ScriptLaunch.fileName("ab12", "\u00e9\u00e8\u4e2d", bashPlan), "skrip-ab12.sh")
    eq("fname long", ScriptLaunch.fileName("ab12", "a".repeat(100), bashPlan).length, 40 + "-ab12.sh".length)
    check("fname no slash", '/' !in ScriptLaunch.fileName("a/b", "x/y", bashPlan))
    eq("fname id sanitized", ScriptLaunch.fileName("a/b;c", "x", bashPlan), "x-abc.sh")
    eq("quote plain", ScriptLaunch.shellQuote("/root/a.sh"), "'/root/a.sh'")
    eq("quote apostrophe", ScriptLaunch.shellQuote("a'b"), "'a'\\''b'")
    eq("cmd no shebang", ScriptLaunch.terminalCommand("/root/x.sh", bashPlan, "echo hi"), "chmod +x '/root/x.sh' && /bin/bash '/root/x.sh'")
    eq("cmd shebang", ScriptLaunch.terminalCommand("/root/x.sh", bashPlan, "#!/bin/bash\necho"), "chmod +x '/root/x.sh' && '/root/x.sh'")
    eq("cmd python", ScriptLaunch.terminalCommand("/root/x.py", pyPlan, "print(1)"), "chmod +x '/root/x.py' && /usr/bin/python3 '/root/x.py'")
    check("cmd single line", '\n' !in ScriptLaunch.terminalCommand("/root/x.sh", bashPlan, "echo\nhi"))
    eq("cmd args", ScriptLaunch.terminalCommand("/root/x.sh", bashPlan, "echo hi", " --a \"b c\" "), "chmod +x '/root/x.sh' && /bin/bash '/root/x.sh' --a \"b c\"")
    check("cmd args no newline", '\n' !in ScriptLaunch.terminalCommand("/root/x.sh", bashPlan, "echo", "a\nrm -rf x"))
    repeat(2000) { // fuzz: nama berkas selalu aman
        val alphabet = "abcXYZ019 ./\\;'\"\$`|&\n\t-_\u00e9".toList()
        val t = String(CharArray(rnd0.nextInt(0, 60)) { alphabet[rnd0.nextInt(alphabet.size)] })
        val f = ScriptLaunch.fileName("id1", t, bashPlan)
        check("fname fuzz safe", f.all { it in 'a'..'z' || it in '0'..'9' || it in "._-A" } && f.endsWith("-id1.sh") && f.length <= 60) { "$t -> $f" }
    }

    println("Selesai: ${total - failed}/$total lulus" + if (failed > 0) ", $failed GAGAL" else "")
    if (failed > 0) kotlin.system.exitProcess(1)
}
