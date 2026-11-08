import com.molina.code.lsp.DefinitionOutcome
import com.molina.code.lsp.LspDefinitionLogic
import com.molina.code.lsp.LspDefinitionTarget
import com.molina.code.lsp.WordSpan

// [CODE-LSP6] Uji JVM murni: kata di kursor, penyaringan target, label, dan pesan.

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private const val ROOT = "/storage/emulated/0/Proyek"
private const val CUR = "$ROOT/a.py"
private fun t(path: String?, line: Int, col: Int, uri: String = "file:///usr/lib/python3/x.py") = LspDefinitionTarget(path, uri, line, col)

fun main() {
    // ---------------------------------------------------------------- wordAt
    val s = "foo.bar(x)"
    check("wordAt: di awal kata", LspDefinitionLogic.wordAt(s, 0) == WordSpan(0, 3, 0))
    check("wordAt: di tengah kata", LspDefinitionLogic.wordAt(s, 5) == WordSpan(4, 7, 5))
    check("wordAt: tepat setelah kata (kursor di akhir) -> kolom permintaan di dalam kata", LspDefinitionLogic.wordAt(s, 7) == WordSpan(4, 7, 6))
    check("wordAt: di titik -> kata sebelumnya", LspDefinitionLogic.wordAt(s, 3) == WordSpan(0, 3, 2))
    check("wordAt: kolom melebihi panjang dijepit", LspDefinitionLogic.wordAt(s, 99) == null)
    check("wordAt: kolom melebihi panjang pada baris berakhir kata", LspDefinitionLogic.wordAt("abc", 99) == WordSpan(0, 3, 2))
    check("wordAt: kolom negatif dijepit ke 0", LspDefinitionLogic.wordAt("abc", -4) == WordSpan(0, 3, 0))
    check("wordAt: baris kosong = null", LspDefinitionLogic.wordAt("", 0) == null)
    check("wordAt: di antara dua spasi sebelum operator = null", LspDefinitionLogic.wordAt("a  = b", 2) == null)
    check("wordAt: hanya spasi = null", LspDefinitionLogic.wordAt("    ", 2) == null)
    check("wordAt: angka bukan pengenal", LspDefinitionLogic.wordAt("x = 123", 5) == null)
    check("wordAt: angka heksa bukan pengenal", LspDefinitionLogic.wordAt("x = 0xFF", 6) == null)
    check("wordAt: nama berangka di belakang boleh", LspDefinitionLogic.wordAt("var2 = 1", 2) == WordSpan(0, 4, 2))
    check("wordAt: garis bawah dan unicode", LspDefinitionLogic.wordAt("_é_ñ = 1", 1) == WordSpan(0, 4, 1))
    check("wordAt: spasi sebelum kata, kursor di spasi = kata sebelumnya bila menempel", LspDefinitionLogic.wordAt("ab cd", 2) == WordSpan(0, 2, 1))
    check("wordAt: kursor di spasi di antara dua spasi = null", LspDefinitionLogic.wordAt("ab  cd", 3) == null)

    // ---------------------------------------------------------------- classify
    val w = WordSpan(4, 7, 5) // "bar" di baris 10
    fun cls(vararg ts: LspDefinitionTarget, exists: (String) -> Boolean = { true }) =
        LspDefinitionLogic.classify(ts.toList(), CUR, w, 10, ROOT, exists)

    check("classify: tanpa target = NotFound", cls() == DefinitionOutcome.NotFound)
    check("classify: satu target di berkas lain = Jump", cls(t("$ROOT/b.py", 2, 4)) == DefinitionOutcome.Jump("$ROOT/b.py", 2, 4))
    check("classify: satu target di berkas aktif, baris lain = Jump", cls(t(CUR, 3, 0)) == DefinitionOutcome.Jump(CUR, 3, 0))
    check("classify: target di posisi kata sendiri = AtDefinition", cls(t(CUR, 10, 4)) == DefinitionOutcome.AtDefinition)
    check("classify: target di akhir kata sendiri juga AtDefinition", cls(t(CUR, 10, 7)) == DefinitionOutcome.AtDefinition)
    check("classify: baris sama tapi kolom di luar kata = Jump", cls(t(CUR, 10, 20)) == DefinitionOutcome.Jump(CUR, 10, 20))
    check("classify: baris lain kolom sama = Jump", cls(t(CUR, 11, 4)) == DefinitionOutcome.Jump(CUR, 11, 4))
    check("classify: diri sendiri dibuang bila ada target lain", cls(t(CUR, 10, 4), t("$ROOT/b.py", 0, 0)) == DefinitionOutcome.Jump("$ROOT/b.py", 0, 0))
    val many = cls(t("$ROOT/z.py", 1, 0), t("$ROOT/b.py", 9, 0), t(CUR, 2, 0), t("$ROOT/b.py", 3, 0)) as DefinitionOutcome.Choose
    check("classify: urutan = berkas aktif dulu, lalu path, baris",
        many.choices.map { it.hostPath to it.line } == listOf(CUR to 2, "$ROOT/b.py" to 3, "$ROOT/b.py" to 9, "$ROOT/z.py" to 1))
    check("classify: label relatif ke folder proyek, baris basis 1", many.choices.map { it.label } == listOf("a.py:3", "b.py:4", "b.py:10", "z.py:2"))
    check("classify: duplikat dibuang", (cls(t("$ROOT/b.py", 1, 1), t("$ROOT/b.py", 1, 1)) as DefinitionOutcome.Jump).line == 1)
    check("classify: semua di Debian = OnlyInDebian dengan path guest",
        cls(t(null, 5, 0, "file:///usr/lib/python3/os.py"), t(null, 6, 0)) == DefinitionOutcome.OnlyInDebian("/usr/lib/python3/os.py", 2))
    check("classify: path tidak ada di disk dianggap tidak terlihat",
        cls(t("$ROOT/hantu.py", 1, 0, "file:///sdcard/Proyek/hantu.py"), exists = { false }) is DefinitionOutcome.OnlyInDebian)
    check("classify: campuran terlihat dan Debian = Jump ke yang terlihat", cls(t(null, 5, 0), t("$ROOT/b.py", 1, 2)) == DefinitionOutcome.Jump("$ROOT/b.py", 1, 2))
    check("classify: exists menyaring sebagian", cls(t("$ROOT/hantu.py", 1, 0), t("$ROOT/b.py", 4, 0), exists = { it.endsWith("b.py") }) == DefinitionOutcome.Jump("$ROOT/b.py", 4, 0))
    val cap = LspDefinitionLogic.classify(List(100) { t("$ROOT/f$it.py", it, 0) }, CUR, w, 10, ROOT) as DefinitionOutcome.Choose
    check("classify: pilihan dibatasi ${LspDefinitionLogic.MAX_CHOICES}", cap.choices.size == LspDefinitionLogic.MAX_CHOICES)
    check("classify: uri rusak dipakai apa adanya pada OnlyInDebian",
        LspDefinitionLogic.classify(listOf(t(null, 0, 0, "bukan-uri")), CUR, w, 10, ROOT) == DefinitionOutcome.OnlyInDebian("bukan-uri", 1))

    // ---------------------------------------------------------------- label dan pesan
    check("label: di luar folder proyek hanya nama berkas", LspDefinitionLogic.label("/storage/emulated/0/lain/m.py", 0, ROOT) == "m.py:1")
    check("label: rootDir null hanya nama berkas", LspDefinitionLogic.label("$ROOT/sub/m.py", 4, null) == "m.py:5")
    check("label: awalan mirip tapi bukan folder (Proyek2)", LspDefinitionLogic.label("${ROOT}2/m.py", 0, ROOT) == "m.py:1")
    check("label: sub folder tetap relatif", LspDefinitionLogic.label("$ROOT/sub/m.py", 0, "$ROOT/") == "sub/m.py:1")
    check("pesan: NotFound", LspDefinitionLogic.message(DefinitionOutcome.NotFound) == "Definisi tidak ditemukan")
    check("pesan: AtDefinition", LspDefinitionLogic.message(DefinitionOutcome.AtDefinition) == "Kursor sudah di definisi")
    check("pesan: OnlyInDebian memuat path", LspDefinitionLogic.message(DefinitionOutcome.OnlyInDebian("/usr/x.py", 1))!!.contains("/usr/x.py"))
    check("pesan: Jump dan Choose tanpa pesan",
        LspDefinitionLogic.message(DefinitionOutcome.Jump("p", 0, 0)) == null && LspDefinitionLogic.message(DefinitionOutcome.Choose(emptyList())) == null)

    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails > 0) System.exit(1)
}
