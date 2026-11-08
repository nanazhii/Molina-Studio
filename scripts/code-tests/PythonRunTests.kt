import com.molina.code.preview.CommandBlockSpec
import com.molina.code.preview.PythonTerminalRun as P

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

fun main() {
    check("isPythonName .py", P.isPythonName("a.py") && P.isPythonName("A.PY") && !P.isPythonName("a.sh") && !P.isPythonName(".py") && !P.isPythonName(null))

    check("nama tanpa ekstensi", P.normalizeName("tes").name == "tes.py")
    check("nama .py tetap", P.normalizeName("tes.py").name == "tes.py")
    check("nama .PY jadi huruf kecil", P.normalizeName(" tes.PY ").name == "tes.py")
    check("nama dengan titik", P.normalizeName("a.b").name == "a.b.py")
    check("kosong ditolak", P.normalizeName("  ").error != null && P.normalizeName(".py").error != null)
    check("path ditolak", P.normalizeName("../x").error != null && P.normalizeName("a/b").error != null)
    check("spasi dan simbol ditolak", P.normalizeName("a b").error != null && P.normalizeName("a;b").error != null)
    check("titik ujung ditolak", P.normalizeName(".x").error != null && P.normalizeName("x.").error != null)
    check("terlalu panjang", P.normalizeName("a".repeat(61)).error != null && P.normalizeName("a".repeat(60)).name != null)

    check("stem dari nama", P.suggestStem("tes.py") == "tes" && P.suggestStem("Untitled 2") == "skrip" && P.suggestStem(null) == "skrip")

    check("heredoc bentuk dasar", P.heredoc("print(1)\n") == "python3 << 'PYEOF'\nprint(1)\nPYEOF")
    check("heredoc CRLF jadi LF", P.heredoc("a=1\r\nprint(a)\r\n") == "python3 << 'PYEOF'\na=1\nprint(a)\nPYEOF")
    check("heredoc kosong null", P.heredoc("  \n\n") == null && P.heredoc("") == null)
    check("heredoc menjaga \$ dan backtick", P.heredoc("x='\$HOME `id`'")!!.contains("x='\$HOME `id`'"))
    check("pembatas bentrok", P.heredoc("a\nPYEOF\nb") == "python3 << 'PYEOF_1'\na\nPYEOF\nb\nPYEOF_1")
    check("pembatas bentrok dua kali", P.uniqueDelimiter("PYEOF\nPYEOF_1") == "PYEOF_2")
    check("PYEOF di tengah baris aman", P.uniqueDelimiter("print('PYEOF')") == "PYEOF")

    check("fileCommand biasa", P.fileCommand("/sdcard/Proyek/tes.py") == "(cd '/sdcard/Proyek' && python3 'tes.py')")
    check("fileCommand root", P.fileCommand("/x.py") == "(cd '/' && python3 'x.py')")
    check("fileCommand kutip", P.fileCommand("/sdcard/a'b/x.py") == "(cd '/sdcard/a'\\''b' && python3 'x.py')")

    check("preview pendek", P.preview("a\nb") == "a\nb")
    check("preview panjang", P.preview("1\n2\n3\n4\n5\n6", 4) == "1\n2\n3\n4\n...")


    // [CODE-PYRUN2] dukungan .sh dan deteksi bahasa
    val SH = com.molina.code.preview.TerminalRunLang.SH
    val PY = com.molina.code.preview.TerminalRunLang.PY
    check("langOf py/sh", P.langOf("a.py") == PY && P.langOf("A.SH") == SH && P.langOf("a.txt") == null && P.langOf(".sh") == null && P.langOf(null) == null)
    check("sh nama otomatis", P.normalizeName("deploy", SH).name == "deploy.sh" && P.normalizeName("deploy.SH", SH).name == "deploy.sh")
    check("sh stem", P.suggestStem("deploy.sh", SH) == "deploy" && P.suggestStem("x.py", SH) == "x.py")
    check("sh heredoc", P.heredoc("echo \$HOME\n", SH) == "bash << 'SHEOF'\necho \$HOME\nSHEOF")
    check("sh pembatas bentrok", P.heredoc("a\nSHEOF\n", SH) == "bash << 'SHEOF_1'\na\nSHEOF\nSHEOF_1")
    check("sh fileCommand", P.fileCommand("/sdcard/a/d.sh", SH) == "(cd '/sdcard/a' && bash 'd.sh')")
    check("py tetap default", P.heredoc("print(1)", PY) == P.heredoc("print(1)") && P.fileCommand("/a/b.py", PY) == P.fileCommand("/a/b.py"))

    // [CODE-CMDBLOCK] aturan potong pratinjau seragam
    check("clip pendek apa adanya", CommandBlockSpec.clip("a\nb\n") == "a\nb")
    check("clip baris panjang", CommandBlockSpec.clip("x".repeat(130)) == "x".repeat(120) + "...")
    check("clip sisa baris", CommandBlockSpec.clip("1\n2\n3\n4\n5\n6", 4) == "1\n2\n3\n4\n...")
    check("clip sisa dengan hitungan", CommandBlockSpec.clip("1\n2\n3\n4\n5\n6", 4, withCount = true) == "1\n2\n3\n4\n... (+2 baris lagi)")
    check("clip CRLF", CommandBlockSpec.clip("a\r\nb\r\n") == "a\nb")
    check("preview mendelegasikan ke clip", P.preview("1\n2\n3\n4\n5") == CommandBlockSpec.clip("1\n2\n3\n4\n5"))

    if (fails > 0) { println("$fails GAGAL"); System.exit(1) } else println("SEMUA LULUS")
}
