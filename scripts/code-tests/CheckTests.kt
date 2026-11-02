import com.molina.code.check.BuiltinCheck
import com.molina.code.check.CheckException
import com.molina.code.check.CheckFix
import com.molina.code.check.CheckPlan
import com.molina.code.check.CheckRange
import com.molina.code.check.Checkers
import com.molina.code.check.Diagnostic
import com.molina.code.check.ExternalCheck
import com.molina.code.check.FixEdit
import com.molina.code.check.Parsers
import com.molina.code.check.Severity

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun throwsCheck(f: () -> Unit): Boolean = try { f(); false } catch (e: CheckException) { true }

fun main() {
    // ---- pemilih
    check("pilih: .py -> pyflakes", (Checkers.of("a.py", null) as? ExternalCheck)?.label == "pyflakes")
    check("pilih: .sh -> shellcheck", (Checkers.of("a.sh", null) as? ExternalCheck)?.label == "shellcheck")
    check("pilih: shebang bash tanpa ekstensi", (Checkers.of("run", "#!/usr/bin/env bash") as? ExternalCheck)?.argv?.contains("bash") == true)
    check("pilih: shebang sh -> dialek sh", (Checkers.of("run", "#!/bin/sh") as? ExternalCheck)?.argv?.contains("sh") == true)
    check("pilih: json bawaan", Checkers.of("x.json", null) is BuiltinCheck)
    check("pilih: svg bawaan", Checkers.of("x.svg", null) is BuiltinCheck)
    check("pilih: .kt belum ada", Checkers.of("A.kt", null) == null)
    check("pilih: stdin shellcheck diakhiri -", (Checkers.of("a.sh", null) as ExternalCheck).argv.last() == "-")

    // ---- pyflakes
    val pf = "<stdin>:1:1: 'os' imported but unused\n<stdin>:5:9: undefined name 'foo'\n"
    val d1 = Parsers.pyflakes(pf, "", 1)
    check("pyflakes: dua temuan terurut", d1.size == 2 && d1[0].line == 1 && d1[1].line == 5 && d1[1].col == 9)
    check("pyflakes: unused = WARNING, undefined = ERROR", d1[0].severity == Severity.WARNING && d1[1].severity == Severity.ERROR)
    check("pyflakes: bersih", Parsers.pyflakes("", "", 0).isEmpty())
    val d2 = Parsers.pyflakes("", "<stdin>:3:7: invalid syntax\n    x = (\n        ^\n", 1)
    check("pyflakes: galat sintaks dari stderr = ERROR, baris sumber diabaikan", d2.size == 1 && d2[0].severity == Severity.ERROR && d2[0].line == 3 && d2[0].col == 7)
    check("pyflakes: tanpa kolom -> kol 1", Parsers.pyflakes("<stdin>:2: peringatan\n", "", 1).first().col == 1)
    check("pyflakes: exit aneh = gagal", throwsCheck { Parsers.pyflakes("", "boom", 2) })
    check("pyflakes: exit 1 tanpa temuan terbaca = gagal", throwsCheck { Parsers.pyflakes("aneh", "", 1) })

    // ---- shellcheck
    val sc = """[{"file":"-","line":3,"endLine":3,"column":6,"endColumn":10,"level":"warning","code":2086,"message":"Double quote to prevent globbing \"x\".","fix":null},
        {"file":"-","line":1,"endLine":1,"column":1,"endColumn":2,"level":"error","code":1072,"message":"Unexpected."},
        {"file":"-","line":2,"endLine":2,"column":1,"endColumn":3,"level":"style","code":2250,"message":"Prefer braces."}]"""
    val s1 = Parsers.shellcheck(sc, "", 1)
    check("shellcheck: tiga temuan terurut baris", s1.map { it.line } == listOf(1, 2, 3))
    check("shellcheck: level dipetakan", s1[0].severity == Severity.ERROR && s1[1].severity == Severity.INFO && s1[2].severity == Severity.WARNING)
    check("shellcheck: kode SC", s1[2].code == "SC2086" && s1[0].code == "SC1072")
    check("shellcheck: pesan dengan escape", s1[2].message.contains("\"x\""))
    check("shellcheck: rentang akhir", s1[2].endLine == 3 && s1[2].endCol == 10)
    check("shellcheck: bersih (larik kosong)", Parsers.shellcheck("[]", "", 0).isEmpty())
    check("shellcheck: keluaran kosong", Parsers.shellcheck("", "", 0).isEmpty())
    check("shellcheck: bentuk json1 (comments)", Parsers.shellcheck("""{"comments":[{"line":2,"column":3,"level":"info","code":2034,"message":"m"}]}""", "", 1).size == 1)
    check("shellcheck: JSON rusak = gagal", throwsCheck { Parsers.shellcheck("[{", "", 1) })
    check("shellcheck: exit 3 = gagal", throwsCheck { Parsers.shellcheck("", "Usage:", 3) })
    check("shellcheck: bentuk tak terduga = gagal", throwsCheck { Parsers.shellcheck("123", "", 1) })
    val deep = "[".repeat(200) + "]".repeat(200)
    check("shellcheck: kedalaman dibatasi tanpa crash", throwsCheck { Parsers.shellcheck(deep, "", 1) })

    // ---- bawaan JSON/XML
    val jp = Checkers.of("a.json", null) as BuiltinCheck
    check("json: valid = kosong", jp.run("{\"a\": [1, 2]}").isEmpty())
    val je = jp.run("{\n  \"a\": 1,\n}\n")
    check("json: koma penutup = ERROR dengan posisi", je.size == 1 && je[0].severity == Severity.ERROR && je[0].line == 3)
    check("json: pesan tanpa sufiks posisi", !je[0].message.contains("baris"))
    check("json: teks kosong = tanpa temuan", jp.run("   ").isEmpty())
    val xp = Checkers.of("a.xml", null) as BuiltinCheck
    check("xml: valid = kosong", xp.run("<a><b/></a>").isEmpty())
    check("xml: tag tak tertutup = ERROR", xp.run("<a>\n<b>\n</a>").let { it.size == 1 && it[0].severity == Severity.ERROR && it[0].line >= 1 })

    // ---- CheckRange
    val text = "echo \$foo\nls -l\n\nlast"
    val dsc = Diagnostic(1, 6, 1, 10, Severity.WARNING, "SC2086", "m", "shellcheck")
    val sp = CheckRange.spans(text, listOf(dsc))
    check("rentang: baris/kolom -> indeks", sp.size == 1 && sp[0].start == 5 && sp[0].end == 9)
    val dpt = Diagnostic(2, 1, 0, 0, Severity.ERROR, null, "m", "pyflakes")
    val sp2 = CheckRange.spans(text, listOf(dpt))
    check("rentang: titik awal diperluas ke akhir kata", sp2[0].start == 10 && sp2[0].end == 12)
    val dend = Diagnostic(4, 99, 0, 0, Severity.ERROR, null, "m", "pyflakes")
    val sp3 = CheckRange.spans(text, listOf(dend))
    check("rentang: kolom di luar baris dijepit, tetap 1 karakter", sp3.size == 1 && sp3[0].end - sp3[0].start == 1 && sp3[0].end == text.length)
    val dempty = Diagnostic(3, 1, 0, 0, Severity.ERROR, null, "m", "x")
    check("rentang: baris kosong tetap punya rentang", CheckRange.spans(text, listOf(dempty)).size == 1)
    check("rentang: baris di luar berkas dijepit", CheckRange.spans(text, listOf(Diagnostic(99, 1, 0, 0, Severity.INFO, null, "m", "x"))).size == 1)
    check("rentang: berkas kosong tanpa crash", CheckRange.spans("", listOf(dpt)).isEmpty())
    check("rentang: daftar kosong", CheckRange.spans(text, emptyList()).isEmpty())

    // ---- [CODE-CHECK-FIX] quick fix shellcheck
    val q = "\""
    val fixJson = """[{"file":"-","line":1,"endLine":1,"column":6,"endColumn":10,"level":"info","code":2086,"message":"Double quote.","fix":{"replacements":[
        {"line":1,"column":6,"endLine":1,"endColumn":6,"precedence":7,"insertionPoint":"afterEnd","replacement":"\""},
        {"line":1,"column":10,"endLine":1,"endColumn":10,"precedence":7,"insertionPoint":"beforeStart","replacement":"\""}]}}]"""
    val fx = Parsers.shellcheck(fixJson, "", 1)
    check("fix: parser membaca dua penggantian", fx.size == 1 && fx[0].fix.size == 2 && fx[0].fix[0].replacement == q)
    check("fix: label SC2086", CheckFix.label(fx[0]) == "Kutip variabel")
    check("fix: tanpa fix = tanpa label", CheckFix.label(s1[0]) == null && s1[0].fix.isEmpty())
    check("fix: fix null = kosong", s1[2].fix.isEmpty())
    val srcText = "echo \$foo\nls\n"
    check("fix: kutip variabel", CheckFix.fixed(srcText, fx[0]) == "echo \"\$foo\"\nls\n")
    val rev = Diagnostic(1, 6, 1, 10, Severity.INFO, "SC2086", "m", "shellcheck", fx[0].fix.reversed())
    check("fix: urutan penggantian tidak penting", CheckFix.fixed(srcText, rev) == "echo \"\$foo\"\nls\n")
    val ml = Diagnostic(2, 1, 2, 3, Severity.INFO, "SC9999", "m", "shellcheck", listOf(FixEdit(2, 1, 2, 3, "ll -a")))
    check("fix: penggantian berisi", CheckFix.fixed(srcText, ml) == "echo \$foo\nll -a\n")
    check("fix: label umum", CheckFix.label(ml) == "Terapkan perbaikan shellcheck")
    val span = Diagnostic(1, 6, 2, 3, Severity.INFO, "SC1", "m", "shellcheck", listOf(FixEdit(1, 6, 2, 3, "X")))
    check("fix: rentang lintas baris", CheckFix.fixed(srcText, span) == "echo X\n")
    val tabLine = "\techo \$foo\n"
    val dtab = Diagnostic(1, 14, 1, 18, Severity.INFO, "SC2086", "m", "shellcheck", fx[0].fix)
    check("fix: baris bertab ditolak", CheckFix.fixed(tabLine, dtab) == null)
    check("fix: baris surrogate ditolak", CheckFix.fixed("echo \uD83D\uDE00 \$foo\n", fx[0]) == null)
    val dover = Diagnostic(1, 6, 1, 10, Severity.INFO, "SC2086", "m", "shellcheck", listOf(FixEdit(1, 6, 1, 99, "x")))
    check("fix: kolom di luar baris ditolak", CheckFix.fixed(srcText, dover) == null)
    val dline = Diagnostic(1, 6, 1, 10, Severity.INFO, "SC2086", "m", "shellcheck", listOf(FixEdit(9, 1, 9, 1, "x")))
    check("fix: baris di luar berkas ditolak", CheckFix.fixed(srcText, dline) == null)
    val dbackwards = Diagnostic(1, 6, 1, 10, Severity.INFO, "SC2086", "m", "shellcheck", listOf(FixEdit(1, 9, 1, 6, "x")))
    check("fix: akhir sebelum awal ditolak", CheckFix.fixed(srcText, dbackwards) == null)
    val dover2 = Diagnostic(1, 1, 1, 6, Severity.INFO, "SC1", "m", "shellcheck", listOf(FixEdit(1, 1, 1, 5, "a"), FixEdit(1, 3, 1, 6, "b")))
    check("fix: tumpang tindih ditolak", CheckFix.fixed(srcText, dover2) == null)
    val dnoop = Diagnostic(1, 1, 1, 5, Severity.INFO, "SC1", "m", "shellcheck", listOf(FixEdit(1, 1, 1, 5, "echo")))
    check("fix: tanpa perubahan = null", CheckFix.fixed(srcText, dnoop) == null)
    check("fix: tanpa penggantian = null", CheckFix.edits(srcText, emptyList()) == null)
    check("fix: penggantian janggal diabaikan",
        Parsers.shellcheck("""[{"line":1,"column":1,"level":"info","code":1,"message":"m","fix":{"replacements":[{"line":1}]}}]""", "", 1)[0].fix.isEmpty())
    check("fix: fix bukan objek diabaikan",
        Parsers.shellcheck("""[{"line":1,"column":1,"level":"info","code":1,"message":"m","fix":"x"}]""", "", 1)[0].fix.isEmpty())
    val many = (1..17).joinToString(",") { """{"line":1,"column":1,"endLine":1,"endColumn":1,"replacement":"a"}""" }
    check("fix: lebih dari 16 penggantian diabaikan",
        Parsers.shellcheck("""[{"line":1,"column":1,"level":"info","code":1,"message":"m","fix":{"replacements":[$many]}}]""", "", 1)[0].fix.isEmpty())
    val mp = CheckFix.minimalPatch(srcText, "echo \"\$foo\"\nls\n")
    check("fix: selisih minimal = satu replace", mp.start == 5 && mp.end == 9 && mp.text == "\"\$foo\"")
    check("fix: selisih minimal bila sama", CheckFix.minimalPatch("abc", "abc").let { it.start == it.end && it.text.isEmpty() })
    check("fix: selisih minimal ujung berulang", CheckFix.minimalPatch("aaa", "aaaa").let { "aaa".replaceRange(it.start, it.end, it.text) == "aaaa" })
    check("fix: selisih minimal teks kosong", CheckFix.minimalPatch("", "x").let { it.start == 0 && it.end == 0 && it.text == "x" })
    check("fix: Diagnostic lama tetap kompatibel", Diagnostic(1, 1, 0, 0, Severity.INFO, null, "m", "x").fix.isEmpty())

    val plan: CheckPlan = Checkers.of("a.py", null)!!
    check("plan: label", plan.label == "pyflakes")

    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails != 0) kotlin.system.exitProcess(1)
}
