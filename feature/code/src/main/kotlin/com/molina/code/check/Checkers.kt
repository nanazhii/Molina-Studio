package com.molina.code.check

import com.molina.code.format.FormatException
import com.molina.code.format.JsonFormatter
import com.molina.code.format.XmlFormatter
import com.molina.code.preview.RunPlans

// [CODE-CHECK] Diagnostik sekali jalan (langkah 6a Cosmic). Kotlin murni (tanpa Android) agar bisa diuji di JVM:
// scripts/code-tests/run_check.sh. Tidak ada proses yang menetap: pemeriksa dijalankan sekali lalu selesai.

enum class Severity { ERROR, WARNING, INFO }

/** [CODE-CHECK-FIX] Satu penggantian dari saran perbaikan pemeriksa (posisi basis 1, kolom akhir eksklusif). */
class FixEdit(val line: Int, val col: Int, val endLine: Int, val endCol: Int, val replacement: String)

/** Semua posisi basis 1. [endLine]/[endCol] = 0 bila pemeriksa hanya memberi titik awal (UI memperluas ke akhir token). */
class Diagnostic(
    val line: Int,
    val col: Int,
    val endLine: Int,
    val endCol: Int,
    val severity: Severity,
    val code: String?,
    val message: String,
    val source: String,
    val fix: List<FixEdit> = emptyList(), // [CODE-CHECK-FIX]
)

/** Galat pemeriksa dengan pesan siap tampil. */
class CheckException(message: String) : Exception(message)

sealed interface CheckPlan {
    val label: String
}

/** Pemeriksa ringan bawaan (JSON, XML): jalan di aplikasi, tanpa Debian. */
class BuiltinCheck(override val label: String, val run: (String) -> List<Diagnostic>) : CheckPlan

/**
 * Pemeriksa di Debian PRoot. Teks masuk lewat stdin; [parse] mengubah (stdout, stderr, exitCode) jadi daftar diagnostik
 * atau melempar [CheckException]. Exit code temuan (mis. 1 pada shellcheck) BUKAN galat: itu urusan [parse].
 */
class ExternalCheck(
    override val label: String,
    val argv: List<String>,
    val installHint: String,
    val parse: (stdout: String, stderr: String, exitCode: Int) -> List<Diagnostic>,
) : CheckPlan

object Checkers {
    private val JSON = BuiltinCheck("JSON bawaan") { text -> builtinFromFormat("JSON", text) { JsonFormatter.format(it) } }
    private val XML = BuiltinCheck("XML bawaan") { text -> builtinFromFormat("XML", text) { XmlFormatter.format(it) } }

    private val PYFLAKES = ExternalCheck(
        "pyflakes",
        listOf("pyflakes3"),
        "pyflakes belum ada di Debian. Di Terminal jalankan: apt install -y pyflakes3",
    ) { out, err, code -> Parsers.pyflakes(out, err, code) }

    private fun shellcheck(dialect: String) = ExternalCheck(
        "shellcheck",
        listOf("shellcheck", "-f", "json", "-s", dialect, "-"),
        "shellcheck belum ada di Debian. Di Terminal jalankan: apt install -y shellcheck",
    ) { out, err, code -> Parsers.shellcheck(out, err, code) }

    private val SHELLCHECK_BASH = shellcheck("bash")
    private val SHELLCHECK_SH = shellcheck("sh")

    private val XML_EXT = setOf("xml", "svg", "xsd", "xsl", "xslt", "plist", "gpx", "kml", "rss", "atom", "wsdl")

    /** Rencana periksa dari nama berkas dan baris pertama (shebang menang atas ekstensi). Null = belum ada pemeriksa. */
    fun of(fileName: String?, firstLine: String?): CheckPlan? {
        val name = fileName?.substringAfterLast('/').orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        RunPlans.of(fileName, firstLine)?.let { run ->
            return when (run.label) {
                "python3" -> PYFLAKES
                "bash" -> SHELLCHECK_BASH
                else -> SHELLCHECK_SH
            }
        }
        return when {
            ext == "json" -> JSON
            ext in XML_EXT -> XML
            else -> null
        }
    }

    const val SUPPORTED_SUMMARY = "py, sh, json, xml/svg"

    private val POS = Regex("""^(.*) \(baris (\d+), kol (\d+)\)$""", RegexOption.DOT_MATCHES_ALL)

    /** Pakai pemformat sebagai pemeriksa sintaks: galat bergaya "pesan (baris L, kol C)" jadi satu diagnostik ERROR. */
    private fun builtinFromFormat(source: String, text: String, f: (String) -> String): List<Diagnostic> {
        if (text.isBlank()) return emptyList()
        try {
            f(text.replace("\r\n", "\n"))
            return emptyList()
        } catch (e: FormatException) {
            val msg = e.message ?: "Galat sintaks"
            val m = POS.find(msg)
            return if (m != null) {
                listOf(Diagnostic(m.groupValues[2].toInt(), m.groupValues[3].toInt(), 0, 0, Severity.ERROR, null, m.groupValues[1], source))
            } else {
                listOf(Diagnostic(1, 1, 0, 0, Severity.ERROR, null, msg, source))
            }
        } catch (e: StackOverflowError) {
            throw CheckException("Struktur terlalu dalam untuk diperiksa")
        }
    }
}

object Parsers {
    private val PYFLAKES_LINE = Regex("""^<stdin>:(\d+):(?:(\d+):?)?\s*(.*)$""")

    /**
     * pyflakes: temuan di stdout, galat sintaks di stderr, format `<stdin>:BARIS:KOL: pesan`. Exit 0 = bersih, 1 = ada temuan;
     * selain itu dianggap gagal. Galat sintaks dan "undefined name" = ERROR, sisanya WARNING.
     */
    fun pyflakes(stdout: String, stderr: String, exitCode: Int): List<Diagnostic> {
        if (exitCode != 0 && exitCode != 1) throw CheckException("pyflakes: " + firstLine(stderr, stdout))
        val out = ArrayList<Diagnostic>()
        fun scan(text: String, fromStderr: Boolean) {
            for (raw in text.lineSequence()) {
                val m = PYFLAKES_LINE.find(raw.trimEnd()) ?: continue
                val line = m.groupValues[1].toIntOrNull() ?: continue
                val col = m.groupValues[2].toIntOrNull() ?: 1
                val msg = m.groupValues[3].ifBlank { "pyflakes" }
                val sev = if (fromStderr || msg.startsWith("undefined name")) Severity.ERROR else Severity.WARNING
                out.add(Diagnostic(line.coerceAtLeast(1), col.coerceAtLeast(1), 0, 0, sev, null, msg, "pyflakes"))
            }
        }
        scan(stdout, false)
        scan(stderr, true)
        if (exitCode == 1 && out.isEmpty()) throw CheckException("pyflakes: " + firstLine(stderr, stdout))
        return out.sortedWith(compareBy({ it.line }, { it.col }))
    }

    /** shellcheck -f json: larik objek {line,endLine,column,endColumn,level,code,message}. Exit 0/1 = normal; lainnya gagal. */
    fun shellcheck(stdout: String, stderr: String, exitCode: Int): List<Diagnostic> {
        if (exitCode != 0 && exitCode != 1) throw CheckException("shellcheck: " + firstLine(stderr, stdout))
        if (stdout.isBlank()) return emptyList()
        val root = try {
            MiniJson.parse(stdout)
        } catch (e: CheckException) {
            throw CheckException("shellcheck: keluaran tidak terbaca")
        }
        val arr = (root as? List<*>) ?: ((root as? Map<*, *>)?.get("comments") as? List<*>)
            ?: throw CheckException("shellcheck: keluaran tidak terduga")
        val out = ArrayList<Diagnostic>(arr.size)
        for (o in arr) {
            val m = o as? Map<*, *> ?: continue
            val line = (m["line"] as? Double)?.toInt() ?: continue
            val col = (m["column"] as? Double)?.toInt() ?: 1
            val endLine = (m["endLine"] as? Double)?.toInt() ?: 0
            val endCol = (m["endColumn"] as? Double)?.toInt() ?: 0
            val code = (m["code"] as? Double)?.toInt()?.let { "SC$it" }
            val sev = when ((m["level"] as? String)?.lowercase()) {
                "error" -> Severity.ERROR
                "warning" -> Severity.WARNING
                else -> Severity.INFO
            }
            out.add(Diagnostic(line.coerceAtLeast(1), col.coerceAtLeast(1), endLine, endCol, sev, code, (m["message"] as? String) ?: "shellcheck", "shellcheck", parseFix(m["fix"])))
        }
        return out.sortedWith(compareBy({ it.line }, { it.col }))
    }

    /** [CODE-CHECK-FIX] `fix` shellcheck = {"replacements":[{line,column,endLine,endColumn,replacement,...}]} atau null. Bentuk janggal = tanpa perbaikan. */
    private fun parseFix(raw: Any?): List<FixEdit> {
        val reps = ((raw as? Map<*, *>)?.get("replacements") as? List<*>) ?: return emptyList()
        if (reps.isEmpty() || reps.size > 16) return emptyList()
        val out = ArrayList<FixEdit>(reps.size)
        for (r in reps) {
            val m = r as? Map<*, *> ?: return emptyList()
            val line = (m["line"] as? Double)?.toInt() ?: return emptyList()
            val col = (m["column"] as? Double)?.toInt() ?: return emptyList()
            val endLine = (m["endLine"] as? Double)?.toInt() ?: line
            val endCol = (m["endColumn"] as? Double)?.toInt() ?: col
            val text = m["replacement"] as? String ?: return emptyList()
            out.add(FixEdit(line, col, endLine, endCol, text))
        }
        return out
    }

    internal fun firstLine(stderr: String, stdout: String): String {
        val l = (stderr.ifBlank { stdout }).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        return (l ?: "gagal (tanpa pesan)").take(160)
    }
}

/** Pengurai JSON kecil (objek -> Map, larik -> List, angka -> Double). Cukup untuk keluaran shellcheck; kedalaman dibatasi. */
internal object MiniJson {
    private const val MAX_DEPTH = 64

    fun parse(s: String): Any? {
        val p = P(s)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != s.length) throw CheckException("sisa teks")
        return v
    }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun fail(): Nothing = throw CheckException("JSON rusak")

        fun value(d: Int): Any? {
            if (d > MAX_DEPTH) fail()
            if (i >= s.length) fail()
            return when (val c = s[i]) {
                '{' -> obj(d)
                '[' -> arr(d)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail()
            }
        }

        fun lit(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) fail()
            i += w.length
            return v
        }

        fun num(): Double {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return s.substring(st, i).toDoubleOrNull() ?: fail()
        }

        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail()
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail()
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail()
                                sb.append((s.substring(i, i + 4).toIntOrNull(16) ?: fail()).toChar())
                                i += 4
                            }
                            else -> fail()
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun arr(d: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                ws()
                out.add(value(d + 1))
                ws()
                if (i >= s.length) fail()
                when (s[i++]) { ',' -> continue; ']' -> return out; else -> fail() }
            }
        }

        fun obj(d: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail()
                val k = str()
                ws()
                if (i >= s.length || s[i++] != ':') fail()
                ws()
                out[k] = value(d + 1)
                ws()
                if (i >= s.length) fail()
                when (s[i++]) { ',' -> continue; '}' -> return out; else -> fail() }
            }
        }
    }
}

/** Menaruh diagnostik ke posisi karakter. Murni: [text] = isi editor. Mengembalikan (mulai, akhir, diagnostik) siap pakai. */
object CheckRange {
    class Span(val start: Int, val end: Int, val d: Diagnostic)

    fun spans(text: String, ds: List<Diagnostic>): List<Span> {
        if (ds.isEmpty()) return emptyList()
        val starts = ArrayList<Int>()
        starts.add(0)
        for (k in text.indices) if (text[k] == '\n') starts.add(k + 1)
        fun lineEnd(l: Int): Int = if (l + 1 < starts.size) starts[l + 1] - 1 else text.length
        fun idx(line: Int, col: Int): Int {
            val l = (line - 1).coerceIn(0, starts.size - 1)
            return (starts[l] + (col - 1).coerceAtLeast(0)).coerceAtMost(lineEnd(l))
        }
        val out = ArrayList<Span>(ds.size)
        for (d in ds) {
            var s = idx(d.line, d.col)
            var e = if (d.endLine > 0 && d.endCol > 0) idx(d.endLine, d.endCol) else s
            if (e <= s) { // titik awal saja: perluas ke akhir kata, minimal satu karakter, tanpa melewati akhir baris
                val le = lineEnd((d.line - 1).coerceIn(0, starts.size - 1))
                e = s
                while (e < le && (text[e].isLetterOrDigit() || text[e] == '_')) e++
                if (e == s) e = (s + 1).coerceAtMost(le)
                if (e == s) { // di akhir baris/berkas: garis bawahi satu karakter sebelumnya
                    if (s > 0) s-- else e = (s + 1).coerceAtMost(text.length)
                }
            }
            if (e > s) out.add(Span(s, e, d))
        }
        return out
    }
}

/**
 * [CODE-CHECK-FIX] Quick fix dari saran pemeriksa (shellcheck): mis. SC2086 mengutip variabel. Murni (tanpa Android).
 * Ditolak (null) bila tidak aman: posisi di luar teks, penggantian saling tumpang tindih, atau baris yang disentuh
 * memuat tab/surrogate (shellcheck menghitung kolom tab per 8 spasi sehingga posisinya bisa meleset).
 */
object CheckFix {
    class Edit(val start: Int, val end: Int, val replacement: String)
    class Patch(val start: Int, val end: Int, val text: String)

    /** Label tombol untuk diagnostik yang punya perbaikan, null bila tidak ada. */
    fun label(d: Diagnostic): String? {
        if (d.fix.isEmpty()) return null
        return when (d.code) {
            "SC2086", "SC2248" -> "Kutip variabel"
            "SC2046" -> "Kutip substitusi perintah"
            "SC2006" -> "Ganti backtick dengan \$()"
            "SC2162" -> "Tambah -r pada read"
            else -> "Terapkan perbaikan shellcheck"
        }
    }

    /** Ubah penggantian berposisi baris/kolom jadi indeks teks, terurut. Null bila tidak aman. [text] memakai LF. */
    fun edits(text: String, fix: List<FixEdit>): List<Edit>? {
        if (fix.isEmpty()) return null
        val starts = ArrayList<Int>()
        starts.add(0)
        for (k in text.indices) if (text[k] == '\n') starts.add(k + 1)
        fun lineEnd(l: Int): Int = if (l + 1 < starts.size) starts[l + 1] - 1 else text.length
        fun unsafeLine(l: Int): Boolean {
            for (k in starts[l] until lineEnd(l)) {
                val c = text[k]
                if (c == '\t' || c.isSurrogate()) return true
            }
            return false
        }
        fun idx(line: Int, col: Int): Int {
            val l = line - 1
            if (l < 0 || l >= starts.size || col < 1 || unsafeLine(l)) return -1
            val i = starts[l] + col - 1
            return if (i <= lineEnd(l)) i else -1
        }
        val out = ArrayList<Edit>(fix.size)
        for (f in fix) {
            val s = idx(f.line, f.col)
            val e = idx(f.endLine, f.endCol)
            if (s < 0 || e < s) return null
            out.add(Edit(s, e, f.replacement))
        }
        val sorted = out.sortedWith(compareBy({ it.start }, { it.end }))
        for (k in 1 until sorted.size) if (sorted[k].start < sorted[k - 1].end) return null
        return sorted
    }

    fun apply(text: String, edits: List<Edit>): String {
        val sb = StringBuilder(text.length + 16)
        var pos = 0
        for (e in edits) {
            sb.append(text, pos, e.start)
            sb.append(e.replacement)
            pos = e.end
        }
        sb.append(text, pos, text.length)
        return sb.toString()
    }

    /** Teks hasil perbaikan [d] pada [text], atau null bila tak aman / tidak mengubah apa pun. */
    fun fixed(text: String, d: Diagnostic): String? {
        val ed = edits(text, d.fix) ?: return null
        val r = apply(text, ed)
        return if (r == text) null else r
    }

    /** Selisih minimal (awalan/akhiran sama dibuang) agar editor cukup satu `replace` = satu langkah undo. */
    fun minimalPatch(old: String, new: String): Patch {
        val max = minOf(old.length, new.length)
        var p = 0
        while (p < max && old[p] == new[p]) p++
        var s = 0
        while (s < max - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
        return Patch(p, old.length - s, new.substring(p, new.length - s))
    }
}
