package com.molina.code.guard

import java.security.MessageDigest

/**
 * [CODE-GUARD] Pemeriksaan sebelum menjalankan skrip: murni Kotlin (tanpa Android), diuji di JVM
 * (scripts/code-tests/GuardTests.kt). Tujuannya menangkap tempelan yang rusak sebelum menyentuh berkas apa pun:
 *  - skrip dibungkus `python3 << 'PYEOF' ... PYEOF` (isinya diambil otomatis; pembatas hilang = terpotong),
 *  - string/kurung/heredoc yang tidak tertutup dan akhir skrip yang menggantung (`:`, `,`, `=`, `\`),
 *  - sisa pembatas heredoc atau pagar markdown yang ikut tertempel,
 *  - jumlah baris dan sidik jari SHA-1 (12 hex pertama, sama dengan `sha1sum berkas | cut -c1-12` bila akhir
 *    baris dan baris terakhir utuh) untuk dicocokkan dengan pengirim. Terpotong tepat di batas pernyataan
 *    adalah satu-satunya kasus yang lolos dari pemindai; hanya jumlah baris/sidik jari yang bisa menangkapnya.
 *
 * Pemindai Python memperlakukan f-string seperti string biasa, jadi kutip bersarang gaya Python 3.12
 * (`f"{x["a"]}"`) bisa terdeteksi keliru; pengguna tetap bisa memilih "Tetap jalankan".
 */
object ScriptPrecheck {
    enum class Kind { PY, SH }

    /** [line] 1-based pada teks asli; 0 = masalah umum. */
    class Issue(val line: Int, val message: String) {
        fun label(): String = if (line > 0) "baris $line: $message" else message
    }

    class Report(
        /** Yang dijalankan: isi heredoc bila skrip terbungkus, selain itu teks apa adanya (akhir baris LF). */
        val body: String,
        val unwrapped: Boolean,
        /** Baris pembuka heredoc yang dibuang, mis. `python3 << 'PYEOF'`. */
        val wrapper: String?,
        /** Jumlah baris teks asli, gaya `wc -l` (baris terakhir tanpa newline tetap dihitung). */
        val lines: Int,
        val sha12: String,
        /** Paling banyak [MAX_ISSUES], terurut menurut baris. */
        val issues: List<Issue>,
        /** Masalah tambahan yang tidak ditampilkan. */
        val moreIssues: Int
    ) {
        val clean: Boolean get() = issues.isEmpty()

        /** Satu baris untuk panel Keluaran atau toast. */
        fun banner(): String {
            val sb = StringBuilder("[cek] ").append(lines).append(" baris \u00B7 sha1 ").append(sha12)
            if (unwrapped) sb.append(" \u00B7 isi heredoc ").append(wrapper).append(" diambil")
            return sb.toString()
        }
    }

    const val MAX_ISSUES = 6

    private val WRAP = Regex("""^\s*(?:python3?|bash|sh)\s*<<-?\s*(?:'([A-Za-z_][A-Za-z0-9_]*)'|"([A-Za-z_][A-Za-z0-9_]*)"|([A-Za-z_][A-Za-z0-9_]*))\s*$""")
    private val STRAY = Regex("""^(?:PYEOF|SHEOF|EOF)(?:_\d+)?$""")

    fun normalize(text: String): String =
        if ('\r' !in text) text else text.replace("\r\n", "\n").replace('\r', '\n')

    fun countLines(s: String): Int {
        if (s.isEmpty()) return 0
        var n = 0
        for (c in s) if (c == '\n') n++
        return if (s.endsWith("\n")) n else n + 1
    }

    fun sha12(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in d) sb.append(String.format("%02x", b.toInt() and 0xff))
        return sb.substring(0, 12)
    }

    fun analyze(text: String, kind: Kind): Report {
        val src = normalize(text)
        val all = src.split('\n')
        val issues = ArrayList<Issue>()
        var body = src
        var offset = 0
        var wrapper: String? = null
        var unwrapped = false

        val first = all.indexOfFirst { it.isNotBlank() }
        val m = if (first >= 0) WRAP.matchEntire(all[first]) else null
        if (m != null) {
            val delim = m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }
            val dash = "<<-" in all[first]
            wrapper = all[first].trim()
            var close = -1
            for (i in first + 1 until all.size) {
                val l = if (dash) all[i].trimEnd().trimStart('\t') else all[i].trimEnd()
                if (l == delim) { close = i; break }
            }
            val end = if (close >= 0) close else all.size
            val joined = all.subList(first + 1, end).joinToString("\n").trimEnd('\n')
            body = if (joined.isBlank()) "" else joined + "\n"
            offset = first + 1
            unwrapped = true
            if (close < 0) {
                issues.add(Issue(first + 1, "Pembatas penutup '$delim' tidak ditemukan, tempelan kemungkinan terpotong"))
            } else {
                for (k in close + 1 until all.size) {
                    if (all[k].isNotBlank()) {
                        issues.add(Issue(k + 1, "Ada teks setelah pembatas '$delim'; bagian ini diabaikan"))
                        break
                    }
                }
            }
        } else {
            val last = all.indexOfLast { it.isNotBlank() }
            // Hanya baris terakhir: pembatas di tengah bisa sah (isi string yang memuat contoh heredoc).
            if (kind == Kind.PY && last >= 0 && STRAY.matches(all[last].trim())) {
                issues.add(Issue(last + 1, "Pembatas heredoc '${all[last].trim()}' ikut tertempel"))
            }
            if (first >= 0 && all[first].trimStart().startsWith("```")) issues.add(Issue(first + 1, "Pagar markdown ``` ikut tertempel"))
            if (last > first && all[last].trimStart().startsWith("```")) issues.add(Issue(last + 1, "Pagar markdown ``` ikut tertempel"))
        }

        issues.addAll(if (kind == Kind.PY) scanPython(body, offset) else ShellScan(body, offset).run())

        val sorted = issues.sortedBy { it.line }
        return Report(
            body = body,
            unwrapped = unwrapped,
            wrapper = wrapper,
            lines = countLines(src),
            sha12 = sha12(src),
            issues = sorted.take(MAX_ISSUES),
            moreIssues = maxOf(0, sorted.size - MAX_ISSUES)
        )
    }

    private fun matches(open: Char, close: Char): Boolean =
        (open == '(' && close == ')') || (open == '[' && close == ']') || (open == '{' && close == '}')

    // ---------------------------------------------------------------- Python

    private fun scanPython(s: String, offset: Int): List<Issue> {
        val out = ArrayList<Issue>()
        val stack = ArrayList<Pair<Char, Int>>()
        val n = s.length
        var i = 0
        var line = 1
        var lastCode = ' '
        var lastLine = 0
        while (i < n) {
            val c = s[i]
            when {
                c == '\n' -> { line++; i++ }
                c == ' ' || c == '\t' || c == '\r' || c == '\u000C' -> i++
                c == '#' -> { while (i < n && s[i] != '\n') i++ }
                c == '\\' -> {
                    if (i + 1 < n && s[i + 1] == '\n') {
                        lastCode = '\\'; lastLine = line
                        line++; i += 2
                    } else {
                        lastCode = if (i + 1 < n) s[i + 1] else c; lastLine = line
                        i += 2
                    }
                }
                c == '\'' || c == '"' -> {
                    val startLine = line
                    if (s.startsWith("$c$c$c", i)) {
                        var j = i + 3
                        var closed = false
                        while (j < n) {
                            val d = s[j]
                            if (d == '\\') { if (j + 1 < n && s[j + 1] == '\n') line++; j += 2; continue }
                            if (d == c && s.startsWith("$c$c$c", j)) { closed = true; j += 3; break }
                            if (d == '\n') line++
                            j++
                        }
                        if (!closed) {
                            out.add(Issue(offset + startLine, "String tiga kutip dibuka tapi tidak ditutup"))
                            return out
                        }
                        i = j
                    } else {
                        var j = i + 1
                        var closed = false
                        while (j < n) {
                            val d = s[j]
                            if (d == '\\') { if (j + 1 < n && s[j + 1] == '\n') line++; j += 2; continue }
                            if (d == c) { closed = true; j++; break }
                            if (d == '\n') break
                            j++
                        }
                        if (!closed) out.add(Issue(offset + startLine, "String berkutip $c tidak ditutup di akhir baris"))
                        i = minOf(j, n)
                    }
                    lastCode = c; lastLine = line
                }
                c == '(' || c == '[' || c == '{' -> { stack.add(c to line); lastCode = c; lastLine = line; i++ }
                c == ')' || c == ']' || c == '}' -> {
                    if (stack.isEmpty()) {
                        out.add(Issue(offset + line, "Kurung penutup '$c' tanpa pembuka"))
                    } else {
                        val (o, ol) = stack.removeAt(stack.size - 1)
                        if (!matches(o, c)) out.add(Issue(offset + line, "Kurung '$o' (baris ${offset + ol}) ditutup dengan '$c'"))
                    }
                    lastCode = c; lastLine = line; i++
                }
                else -> { lastCode = c; lastLine = line; i++ }
            }
        }
        for (k in 0 until minOf(3, stack.size)) {
            out.add(Issue(offset + stack[k].second, "Kurung '${stack[k].first}' dibuka tapi tidak pernah ditutup"))
        }
        if (stack.isEmpty()) {
            when {
                lastCode == ':' -> out.add(Issue(offset + lastLine, "Skrip berakhir dengan ':' tanpa isi blok"))
                lastCode == '\\' -> out.add(Issue(offset + lastLine, "Skrip berakhir dengan '\\' (lanjutan baris tanpa lanjutan)"))
                lastCode in ",=+-/%&|^" -> out.add(Issue(offset + lastLine, "Skrip berakhir di tengah ekspresi (karakter terakhir '$lastCode')"))
            }
        }
        return out
    }

    // ---------------------------------------------------------------- Shell

    private class ShellScan(val s: String, val offset: Int) {
        val n = s.length
        var line = 1
        val out = ArrayList<Issue>()

        fun single(i: Int): Int {
            val e = s.indexOf('\'', i + 1)
            if (e < 0) return -1
            for (k in i until e) if (s[k] == '\n') line++
            return e + 1
        }

        fun backtick(i: Int): Int {
            var j = i + 1
            while (j < n) {
                val c = s[j]
                if (c == '\\') { if (j + 1 < n && s[j + 1] == '\n') line++; j += 2; continue }
                if (c == '\n') line++
                if (c == '`') return j + 1
                j++
            }
            return -1
        }

        fun dquote(i: Int): Int {
            var j = i + 1
            while (j < n) {
                val c = s[j]
                when {
                    c == '\\' -> { if (j + 1 < n && s[j + 1] == '\n') line++; j += 2 }
                    c == '"' -> return j + 1
                    c == '\n' -> { line++; j++ }
                    c == '$' && j + 1 < n && s[j + 1] == '(' -> { val e = sub(j + 2); if (e < 0) return -1; j = e }
                    c == '`' -> { val e = backtick(j); if (e < 0) return -1; j = e }
                    else -> j++
                }
            }
            return -1
        }

        /** [start] = indeks setelah `$(`. Kembalikan indeks setelah `)` penutup, atau -1. */
        fun sub(start: Int): Int {
            var depth = 1
            var j = start
            while (j < n) {
                val c = s[j]
                when {
                    c == '\\' -> { if (j + 1 < n && s[j + 1] == '\n') line++; j += 2 }
                    c == '\'' -> { val e = single(j); if (e < 0) return -1; j = e }
                    c == '"' -> { val e = dquote(j); if (e < 0) return -1; j = e }
                    c == '`' -> { val e = backtick(j); if (e < 0) return -1; j = e }
                    c == '(' -> { depth++; j++ }
                    c == ')' -> { depth--; j++; if (depth == 0) return j }
                    c == '\n' -> { line++; j++ }
                    else -> j++
                }
            }
            return -1
        }

        fun run(): List<Issue> {
            val pending = ArrayList<Triple<String, Boolean, Int>>()
            var lastCode = ' '
            var prev = '\n'
            var i = 0
            while (i < n) {
                val c = s[i]
                when {
                    c == '\n' -> {
                        line++; i++; prev = '\n'
                        if (pending.isNotEmpty()) {
                            for (p in pending) {
                                var found = false
                                while (i < n) {
                                    var e = s.indexOf('\n', i)
                                    if (e < 0) e = n
                                    var t = s.substring(i, e).trimEnd()
                                    if (p.second) t = t.trimStart('\t')
                                    if (e < n) { line++; i = e + 1 } else i = n
                                    if (t == p.first) { found = true; break }
                                }
                                if (!found) out.add(Issue(offset + p.third, "Heredoc '${p.first}' tidak ditutup"))
                            }
                            pending.clear()
                        }
                    }
                    c == ' ' || c == '\t' || c == '\r' -> { prev = c; i++ }
                    c == '\\' -> {
                        if (i + 1 < n && s[i + 1] == '\n') { lastCode = '\\'; line++ } else lastCode = if (i + 1 < n) s[i + 1] else c
                        prev = 'x'; i += 2
                    }
                    c == '#' && (prev == '\n' || prev == ' ' || prev == '\t' || prev == ';' || prev == '(' || prev == '&' || prev == '|') -> {
                        while (i < n && s[i] != '\n') i++
                    }
                    c == '\'' -> {
                        val st = line
                        val e = single(i)
                        if (e < 0) { out.add(Issue(offset + st, "Kutip tunggal tidak ditutup")); return out }
                        i = e; lastCode = c; prev = c
                    }
                    c == '"' -> {
                        val st = line
                        val e = dquote(i)
                        if (e < 0) { out.add(Issue(offset + st, "Kutip ganda tidak ditutup")); return out }
                        i = e; lastCode = c; prev = c
                    }
                    c == '`' -> {
                        val st = line
                        val e = backtick(i)
                        if (e < 0) { out.add(Issue(offset + st, "Backtick tidak ditutup")); return out }
                        i = e; lastCode = c; prev = c
                    }
                    c == '$' && i + 1 < n && s[i + 1] == '(' -> {
                        val st = line
                        val e = sub(i + 2)
                        if (e < 0) { out.add(Issue(offset + st, "Substitusi \$( tidak ditutup")); return out }
                        i = e; lastCode = ')'; prev = ')'
                    }
                    c == '<' && s.startsWith("<<<", i) -> { i += 3; lastCode = '<'; prev = '<' }
                    c == '<' && s.startsWith("<<", i) -> {
                        var j = i + 2
                        val dash = j < n && s[j] == '-'
                        if (dash) j++
                        while (j < n && (s[j] == ' ' || s[j] == '\t')) j++
                        var delim = ""
                        if (j < n && (s[j] == '\'' || s[j] == '"')) {
                            val q = s[j]
                            val e = s.indexOf(q, j + 1)
                            val nlPos = s.indexOf('\n', j + 1)
                            if (e > 0 && (nlPos < 0 || e < nlPos)) { delim = s.substring(j + 1, e); j = e + 1 }
                        } else {
                            if (j < n && s[j] == '\\') j++
                            val w = j
                            while (j < n && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                            delim = s.substring(w, j)
                        }
                        if (delim.isNotEmpty() && (delim[0].isLetter() || delim[0] == '_')) pending.add(Triple(delim, dash, line))
                        i = maxOf(j, i + 2); lastCode = '<'; prev = '<'
                    }
                    else -> { lastCode = c; prev = c; i++ }
                }
            }
            for (p in pending) out.add(Issue(offset + p.third, "Heredoc '${p.first}' tidak ditutup"))
            when {
                lastCode == '\\' -> out.add(Issue(offset + line, "Skrip berakhir dengan '\\' (lanjutan baris tanpa lanjutan)"))
                lastCode == '|' || s.trimEnd().endsWith("&&") -> out.add(Issue(offset + line, "Skrip berakhir di operator pipa/&&"))
            }
            return out
        }
    }
}
