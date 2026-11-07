import com.molina.code.guard.DiskGuard
import com.molina.code.guard.DiskVerdict
import com.molina.code.guard.ScriptPrecheck
import com.molina.code.guard.ScriptPrecheck.Kind

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun py(t: String) = ScriptPrecheck.analyze(t, Kind.PY)
private fun sh(t: String) = ScriptPrecheck.analyze(t, Kind.SH)
private fun kts(t: String) = ScriptPrecheck.analyze(t, Kind.KTS) // [CODE-KTS]
private fun msgs(r: ScriptPrecheck.Report) = r.issues.joinToString(" | ") { it.label() }

fun main() {
    // ---- DiskGuard
    check("disk sama dengan baseline", DiskGuard.verdict(true, true, 5L, 5L) == DiskVerdict.SAME)
    check("disk berubah", DiskGuard.verdict(true, true, 5L, 6L) == DiskVerdict.CHANGED)
    check("disk sudah sama dengan isi editor bukan bentrokan", DiskGuard.verdict(true, true, 5L, 6L, editorFp = 6L) == DiskVerdict.SAME)
    check("berkas hilang", DiskGuard.verdict(false, false, 5L, null) == DiskVerdict.MISSING)
    check("tak terbaca dilewati", DiskGuard.verdict(true, false, 5L, null) == DiskVerdict.UNKNOWN)
    check("tab bersih: muat ulang otomatis", DiskGuard.autoReload(DiskVerdict.CHANGED, dirty = false) && !DiskGuard.autoReload(DiskVerdict.CHANGED, dirty = true))
    check("tab kotor: ditandai basi", DiskGuard.flagStale(DiskVerdict.CHANGED, dirty = true) && !DiskGuard.flagStale(DiskVerdict.SAME, dirty = true))
    check("hanya CHANGED memblokir simpan", DiskGuard.blocksSave(DiskVerdict.CHANGED) && !DiskGuard.blocksSave(DiskVerdict.MISSING) && !DiskGuard.blocksSave(DiskVerdict.UNKNOWN))

    // ---- Dasar: banner, jumlah baris, sidik jari
    val ok1 = py("print('a')\nprint('b')\n")
    check("skrip bersih tanpa masalah", ok1.clean && !ok1.unwrapped && ok1.body == "print('a')\nprint('b')\n")
    check("jumlah baris gaya wc -l", ok1.lines == 2 && py("a\nb").lines == 2 && py("").lines == 0)
    check("sha1 12 hex stabil dan peka isi", ok1.sha12.length == 12 && ok1.sha12 == py("print('a')\nprint('b')\n").sha12 && ok1.sha12 != py("print('a')\nprint('c')\n").sha12)
    check("sha1 dari 'abc'", ScriptPrecheck.sha12("abc") == "a9993e364706")
    check("CRLF dinormalkan", py("x = 1\r\ny = 2\r\n").sha12 == py("x = 1\ny = 2\n").sha12)
    check("banner", ok1.banner() == "[cek] 2 baris \u00B7 sha1 ${ok1.sha12}")

    // ---- Heredoc terbungkus
    val w = py("python3 << 'PYEOF'\nimport os\nprint(os.name)\nPYEOF\n")
    check("heredoc: isi diambil", w.unwrapped && w.body == "import os\nprint(os.name)\n" && w.clean && w.wrapper == "python3 << 'PYEOF'")
    check("heredoc: banner menyebut pembungkus", w.banner().contains("isi heredoc python3 << 'PYEOF' diambil"))
    check("heredoc: tanpa kutip dan bash", sh("bash << SHEOF\necho hi\nSHEOF\n").body == "echo hi\n")
    check("heredoc: pembuka didahului baris kosong", py("\n\npython3 << 'PYEOF'\nx = 1\nPYEOF\n").body == "x = 1\n")
    val cut = py("python3 << 'PYEOF'\nimport os\nprint(os.name)\n")
    check("heredoc terpotong: pembatas hilang", !cut.clean && cut.issues[0].message.contains("tidak ditemukan") && cut.body == "import os\nprint(os.name)\n")
    val extra = py("python3 << 'PYEOF'\nx = 1\nPYEOF\nsisa teks\n")
    check("teks setelah pembatas diperingatkan", extra.issues.size == 1 && extra.issues[0].line == 4 && extra.body == "x = 1\n")
    check("nomor baris isi heredoc mengikuti teks asli", py("python3 << 'PYEOF'\nx = (1,\nPYEOF\n").issues.any { it.line == 2 })
    check("tanda -  pada <<- menerima tab", py("python3 <<- 'PYEOF'\n\tx = 1\n\tPYEOF\n").unwrapped && py("python3 <<- 'PYEOF'\n\tx = 1\n\tPYEOF\n").body == "\tx = 1\n")

    // ---- Sisa tempelan
    check("pembatas PYEOF tertinggal di akhir", !py("x = 1\nPYEOF\n").clean && py("x = 1\nPYEOF\n").issues[0].line == 2)
    check("pembatas PYEOF di tengah tidak diperingatkan", py("x = 1\nPYEOF\ny = 2\n").issues.none { it.message.contains("ikut tertempel") })
    check("pagar markdown pembuka dan penutup", py("```python\nx = 1\n```\n").issues.size == 2)
    check("pagar markdown di tengah (isi string) tidak diperingatkan", py("s = '''\n```\nx\n```\n'''\nprint(s)\n").clean)
    check("EOF di skrip shell sah (heredoc biasa)", sh("cat << 'EOF'\nhalo\nEOF\n").clean)

    // ---- Python: tidak tertutup
    check("python: tiga kutip tak tertutup", py("x = '''abc\nprint(1)\n").issues.single().let { it.line == 1 && it.message.contains("tiga kutip") })
    check("python: kurung tak tertutup", py("x = foo(1,\n  2\n").issues.any { it.line == 1 && it.message.contains("'('") })
    check("python: kurung salah pasangan", py("x = (1, 2]\n").issues.any { it.message.contains("ditutup dengan") })
    check("python: penutup tanpa pembuka", py("x = 1)\n").issues.any { it.message.contains("tanpa pembuka") })
    check("python: string satu baris tak tertutup", py("x = 'abc\ny = 2\n").issues.single().line == 1)
    check("python: kutip di dalam string dan komentar diabaikan", py("x = \"it's (ok\"  # kurung ( di komentar ' juga\ny = 'a\"b'\n").clean)
    check("python: escape kutip", py("x = 'it\\'s'\ny = \"a\\\"b\"\n").clean)
    check("python: tiga kutip berisi kutip dan kurung", py("d = \"\"\"a ' \" ( [\n\"\"\"\nprint(d)\n").clean)
    check("python: tiga kutip dengan backslash berurutan", py("x = '''a\\\\'''\nprint(x)\n").clean)
    check("python: berakhir dengan titik dua", py("def f():\n    x = 1\nif x:\n").issues.single().let { it.line == 3 && it.message.contains("':'") })
    check("python: berakhir dengan koma/operator", py("x = 1 +\n").issues.single().message.contains("tengah ekspresi"))
    check("python: berakhir dengan backslash", py("x = 1 + \\\n").issues.any { it.message.contains("lanjutan") })
    check("python: komentar di akhir tidak mengubah hasil", py("def f():\n    return 1\n# selesai:\n").clean)
    check("python: lanjutan baris sah", py("x = 1 + \\\n    2\nprint(x)\n").clean)
    check("python: dict dan slice", py("d = {'a': [1, 2][0:1], 'b': (3,)}\nprint(d)\n").clean)
    check("python: import bintang", py("from os import *\n").clean)
    check("python: nomor baris melewati string multibaris", py("s = '''a\nb\nc'''\nx = (1,\n").issues.any { it.line == 4 })
    check("python: skrip patch panjang dengan heredoc di string tetap bersih", py("TXT = '''\npython3 << 'PYEOF'\nx\nPYEOF\n'''\nprint(TXT)\n").clean)

    // ---- Shell
    check("shell: bersih", sh("#!/bin/bash\nset -e\necho \"a $(date +%Y)\" # komentar 'x\nfor i in 1 2; do echo \$i; done\n").clean)
    check("shell: kutip tunggal tak tertutup", sh("echo 'abc\necho 2\n").issues.single().line == 1)
    check("shell: kutip ganda tak tertutup", sh("echo \"abc\necho 2\n").issues.single().message.contains("ganda"))
    // [CODE-KTS] apostrof di komentar // salah dibaca pemindai shell, bukan KTS
    check("[CODE-KTS] kts: apostrof di komentar", !sh("// it's fine\nprintln(1)\n").clean && kts("// it's fine\nprintln(1)\n").clean)
    check("[CODE-KTS] kts: template string bersih", kts("val n = 1\nprintln(\"n=\$n\")\n").clean)
    check("[CODE-KTS] kts: pagar markdown terdeteksi", kts("```kotlin\nprintln(1)\n```\n").issues.isNotEmpty())
    check("shell: kutip bersarang di dalam \$( )", sh("x=\"\$(echo \"a b\" | tr a z)\"\necho \"\$x\"\n").clean)
    check("shell: heredoc tak ditutup", sh("cat << 'EOF'\nisi\n").issues.single().message.contains("tidak ditutup"))
    check("shell: heredoc ditutup", sh("cat << 'EOF' > out.txt\nisi 'x\nEOF\necho selesai\n").clean)
    check("shell: dua heredoc dalam satu baris", sh("cat << A << B\n1\nA\n2\nB\necho ok\n").clean)
    check("shell: <<< bukan heredoc", sh("read -r x <<< \"halo\"\necho \$x\n").clean)
    check("shell: aritmetika << bukan heredoc", sh("echo \$(( 1 << 2 ))\n").clean)
    check("shell: berakhir dengan pipa", sh("ls |\n").issues.single().message.contains("operator"))
    check("shell: berakhir dengan &&", sh("make &&\n").issues.single().message.contains("operator"))
    check("shell: latar belakang & sah", sh("sleep 1 &\n").clean)
    check("shell: berakhir dengan backslash", sh("echo a \\\n").issues.single().message.contains("lanjutan"))
    check("shell: komentar bukan di awal kata tidak memotong", sh("echo a#b 'c'\n").clean)
    check("shell: \$# dan \${#x} bukan komentar", sh("echo \$# \${#PATH} 'x'\n").clean)
    check("shell: nomor baris setelah heredoc", sh("cat << EOF\na\nb\nEOF\necho 'x\n").issues.single().line == 5)

    // ---- Batas laporan
    val many = py((1..10).joinToString("") { "x = $it)\n" })
    check("laporan dipotong 6 + sisa", many.issues.size == ScriptPrecheck.MAX_ISSUES && many.moreIssues == 4)

    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails != 0) kotlin.system.exitProcess(1)
}
