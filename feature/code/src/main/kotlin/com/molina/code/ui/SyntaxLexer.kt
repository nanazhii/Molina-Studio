package com.molina.code.ui

/** Jenis token highlight. Pemetaan ke warna ada di [ObsidianClaude]. */
internal enum class Tok { Plain, Keyword, Type, Func, Str, Num, Comment, Annot, Tag, Attr, Heading }

internal enum class SyntaxKind { Code, Markup, Markdown }

/** Aturan leksikal satu keluarga bahasa. Lexer murni Kotlin (tanpa Android) agar bisa diuji di JVM. */
internal class SyntaxSpec(
    val kind: SyntaxKind = SyntaxKind.Code,
    val keywords: Set<String> = emptySet(),
    val lineComments: List<String> = emptyList(),
    val blockStart: String? = null,
    val blockEnd: String? = null,
    val quotes: String = "\"'",
    val multilineQuotes: String = "",
    val tripleQuotes: Boolean = false,
    val annotations: Boolean = false,
    val jsonKeys: Boolean = false
)

internal object SyntaxLexer {
    /** Di atas batas ini berkas tidak di-highlight agar editor tetap lancar. */
    const val MAX_CHARS = 400_000

    /**
     * Memecah [text] menjadi transisi token. [out] dipanggil berurutan dengan (baris, kolom, token):
     * token berlaku mulai posisi itu sampai transisi berikutnya. Setiap token non-Plain selalu
     * ditutup dengan transisi Plain. Mengembalikan indeks baris terakhir.
     */
    fun lex(text: CharSequence, spec: SyntaxSpec, cancelled: () -> Boolean, out: (Int, Int, Tok) -> Unit): Int {
        // Satu emisi tertahan: dua transisi di posisi yang sama digabung (yang terakhir menang),
        // sehingga posisi yang sampai ke [out] selalu naik ketat.
        var pl = -1
        var pc = -1
        var pt = Tok.Plain
        var has = false
        val sink: (Int, Int, Tok) -> Unit = { l, c, t ->
            if (has && l == pl && c == pc) {
                pt = t
            } else {
                if (has) out(pl, pc, pt)
                pl = l
                pc = c
                pt = t
                has = true
            }
        }
        val sc = Scan(text, sink)
        when (spec.kind) {
            SyntaxKind.Code -> lexCode(sc, spec, cancelled)
            SyntaxKind.Markup -> lexMarkup(sc, cancelled)
            SyntaxKind.Markdown -> lexMarkdown(sc, cancelled)
        }
        if (has) out(pl, pc, pt)
        return sc.line
    }

    private class Scan(val s: CharSequence, val out: (Int, Int, Tok) -> Unit) {
        val n = s.length
        var i = 0
        var line = 0
        var lineStart = 0

        fun emit(t: Tok) = out(line, i - lineStart, t)

        fun at(p: String): Boolean {
            if (i + p.length > n) return false
            for (k in p.indices) if (s[i + k] != p[k]) return false
            return true
        }

        /** Dipanggil setelah [i] melewati '\n'. */
        fun newline() {
            line++
            lineStart = i
        }

        fun skipToEol() {
            while (i < n && s[i] != '\n') i++
        }

        /** Maju sampai [end] ditemukan (ikut dilewati) atau akhir teks, sambil menghitung baris. */
        fun skipUntil(end: String) {
            while (i < n) {
                if (at(end)) {
                    i += end.length
                    return
                }
                if (s[i] == '\n') {
                    i++
                    newline()
                } else {
                    i++
                }
            }
        }

        fun nextNonBlankIs(from: Int, ch: Char): Boolean {
            var j = from
            while (j < n && (s[j] == ' ' || s[j] == '\t')) j++
            return j < n && s[j] == ch
        }
    }

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_' || c == '$'
    private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    // ---------------------------------------------------------------- kode

    private fun lexCode(sc: Scan, spec: SyntaxSpec, cancelled: () -> Boolean) {
        val s = sc.s
        val bs = spec.blockStart
        val be = spec.blockEnd
        var guard = 0
        while (sc.i < sc.n) {
            if ((++guard and 0x3FFF) == 0 && cancelled()) return
            val c = s[sc.i]
            if (c == '\n') {
                sc.i++
                sc.newline()
                continue
            }
            if (spec.lineComments.any { sc.at(it) }) {
                sc.emit(Tok.Comment)
                sc.skipToEol()
                sc.emit(Tok.Plain)
                continue
            }
            if (bs != null && be != null && sc.at(bs)) {
                sc.emit(Tok.Comment)
                sc.i += bs.length
                sc.skipUntil(be)
                sc.emit(Tok.Plain)
                continue
            }
            if (spec.quotes.indexOf(c) >= 0) {
                lexString(sc, spec, c)
                continue
            }
            if (c.isDigit() || (c == '.' && sc.i + 1 < sc.n && s[sc.i + 1].isDigit())) {
                sc.emit(Tok.Num)
                sc.i++
                while (sc.i < sc.n) {
                    val ch = s[sc.i]
                    if (ch.isLetterOrDigit() || ch == '_') sc.i++
                    else if (ch == '.' && sc.i + 1 < sc.n && s[sc.i + 1].isDigit()) sc.i++
                    else break
                }
                sc.emit(Tok.Plain)
                continue
            }
            if (isIdentStart(c)) {
                val st = sc.i
                val startCol = st - sc.lineStart
                while (sc.i < sc.n && isIdentPart(s[sc.i])) sc.i++
                val word = s.subSequence(st, sc.i).toString()
                val tok = when {
                    word in spec.keywords -> Tok.Keyword
                    word[0].isUpperCase() && word.any { it.isLowerCase() } -> Tok.Type
                    sc.nextNonBlankIs(sc.i, '(') -> Tok.Func
                    else -> Tok.Plain
                }
                if (tok != Tok.Plain) {
                    sc.out(sc.line, startCol, tok)
                    sc.emit(Tok.Plain)
                }
                continue
            }
            if (c == '@' && spec.annotations && sc.i + 1 < sc.n && s[sc.i + 1].isLetter()) {
                sc.emit(Tok.Annot)
                sc.i++
                while (sc.i < sc.n && (isIdentPart(s[sc.i]) || s[sc.i] == '.')) sc.i++
                sc.emit(Tok.Plain)
                continue
            }
            sc.i++
        }
    }

    private fun lexString(sc: Scan, spec: SyntaxSpec, q: Char) {
        val s = sc.s
        val startI = sc.i
        val startLine = sc.line
        val startLineStart = sc.lineStart
        val startCol = sc.i - sc.lineStart
        val triple = spec.tripleQuotes && sc.at("$q$q$q")
        val closeTriple = "$q$q$q"
        val multiline = spec.multilineQuotes.indexOf(q) >= 0
        sc.i += if (triple) 3 else 1
        var closed = false
        while (sc.i < sc.n) {
            val ch = s[sc.i]
            if (ch == '\\' && sc.i + 1 < sc.n) {
                if (s[sc.i + 1] == '\n') {
                    sc.i += 2
                    sc.newline()
                } else {
                    sc.i += 2
                }
                continue
            }
            if (triple) {
                if (sc.at(closeTriple)) {
                    sc.i += 3
                    closed = true
                    break
                }
            } else if (ch == q) {
                sc.i++
                closed = true
                break
            }
            if (ch == '\n') {
                if (!triple && !multiline) break
                sc.i++
                sc.newline()
                continue
            }
            sc.i++
        }
        if (!closed && q == '\'' && !triple) {
            // apostrof tak berpasangan (mis. teks bebas): bukan string, lewati satu karakter saja
            sc.i = startI + 1
            sc.line = startLine
            sc.lineStart = startLineStart
            return
        }
        var tok = Tok.Str
        if (spec.jsonKeys && closed && sc.nextNonBlankIs(sc.i, ':')) tok = Tok.Attr
        sc.out(startLine, startCol, tok)
        sc.emit(Tok.Plain)
    }

    // ---------------------------------------------------------------- markup

    private fun lexMarkup(sc: Scan, cancelled: () -> Boolean) {
        val s = sc.s
        var guard = 0
        while (sc.i < sc.n) {
            if ((++guard and 0x3FFF) == 0 && cancelled()) return
            val c = s[sc.i]
            if (c == '\n') {
                sc.i++
                sc.newline()
                continue
            }
            if (sc.at("<!--")) {
                sc.emit(Tok.Comment)
                sc.i += 4
                sc.skipUntil("-->")
                sc.emit(Tok.Plain)
                continue
            }
            if (c == '<' && sc.i + 1 < sc.n && (s[sc.i + 1].isLetter() || s[sc.i + 1] == '/' || s[sc.i + 1] == '!' || s[sc.i + 1] == '?')) {
                sc.emit(Tok.Tag)
                sc.i++
                while (sc.i < sc.n && (s[sc.i] == '/' || s[sc.i] == '!' || s[sc.i] == '?')) sc.i++
                while (sc.i < sc.n && (s[sc.i].isLetterOrDigit() || s[sc.i] == '-' || s[sc.i] == '_' || s[sc.i] == ':' || s[sc.i] == '.')) sc.i++
                sc.emit(Tok.Plain)
                lexTagBody(sc)
                continue
            }
            sc.i++
        }
    }

    private fun lexTagBody(sc: Scan) {
        val s = sc.s
        while (sc.i < sc.n) {
            val ch = s[sc.i]
            if (ch == '>') {
                sc.emit(Tok.Tag)
                sc.i++
                sc.emit(Tok.Plain)
                return
            }
            if ((ch == '/' || ch == '?') && sc.i + 1 < sc.n && s[sc.i + 1] == '>') {
                sc.emit(Tok.Tag)
                sc.i += 2
                sc.emit(Tok.Plain)
                return
            }
            if (ch == '\n') {
                sc.i++
                sc.newline()
                continue
            }
            if (ch == '"' || ch == '\'') {
                sc.emit(Tok.Str)
                sc.i++
                sc.skipUntil(ch.toString())
                sc.emit(Tok.Plain)
                continue
            }
            if (ch.isLetter() || ch == '_' || ch == ':') {
                sc.emit(Tok.Attr)
                while (sc.i < sc.n && (s[sc.i].isLetterOrDigit() || s[sc.i] == '-' || s[sc.i] == '_' || s[sc.i] == ':' || s[sc.i] == '.')) sc.i++
                sc.emit(Tok.Plain)
                continue
            }
            sc.i++
        }
    }

    // ---------------------------------------------------------------- markdown

    private fun lexMarkdown(sc: Scan, cancelled: () -> Boolean) {
        val s = sc.s
        var inFence = false
        var guard = 0
        while (sc.i < sc.n) {
            if ((++guard and 0xFFF) == 0 && cancelled()) return
            val ls = sc.i
            var eol = ls
            while (eol < sc.n && s[eol] != '\n') eol++
            var t = ls
            while (t < eol && (s[t] == ' ' || s[t] == '\t')) t++
            val isFence = t + 2 < eol + 0 && ((s[t] == '`' && s[t + 1] == '`' && s[t + 2] == '`') || (s[t] == '~' && s[t + 1] == '~' && s[t + 2] == '~'))
            val ln = sc.line
            when {
                inFence -> {
                    sc.out(ln, 0, Tok.Str)
                    if (isFence) inFence = false
                }
                isFence -> {
                    sc.out(ln, 0, Tok.Str)
                    inFence = true
                }
                t < eol && s[t] == '#' && headingAt(s, t, eol) -> sc.out(ln, 0, Tok.Heading)
                t < eol && s[t] == '>' -> sc.out(ln, 0, Tok.Comment)
                else -> {
                    sc.out(ln, 0, Tok.Plain)
                    val m = listMarkerLen(s, t, eol)
                    if (m > 0) {
                        sc.out(ln, t - ls, Tok.Keyword)
                        sc.out(ln, t - ls + m, Tok.Plain)
                    }
                    inlineCode(sc, ls, eol)
                }
            }
            sc.i = eol
            if (sc.i < sc.n) {
                sc.i++
                sc.newline()
            }
        }
    }

    private fun headingAt(s: CharSequence, t: Int, eol: Int): Boolean {
        var k = t
        while (k < eol && s[k] == '#') k++
        return k - t in 1..6 && (k == eol || s[k] == ' ')
    }

    private fun listMarkerLen(s: CharSequence, t: Int, eol: Int): Int {
        if (t >= eol) return 0
        if ((s[t] == '-' || s[t] == '*' || s[t] == '+') && t + 1 < eol && s[t + 1] == ' ') return 1
        var k = t
        while (k < eol && s[k].isDigit()) k++
        if (k > t && k < eol && (s[k] == '.' || s[k] == ')') && k + 1 < eol && s[k + 1] == ' ') return k - t + 1
        return 0
    }

    private fun inlineCode(sc: Scan, ls: Int, eol: Int) {
        val s = sc.s
        var k = ls
        while (k < eol) {
            if (s[k] == '`') {
                var e = k + 1
                while (e < eol && s[e] != '`') e++
                if (e < eol) {
                    sc.out(sc.line, k - ls, Tok.Str)
                    sc.out(sc.line, e + 1 - ls, Tok.Plain)
                    k = e + 1
                    continue
                }
            }
            k++
        }
    }
}

/** Pemilihan aturan bahasa dari nama berkas. Tidak dikenal = null (teks polos, tanpa analisis). */
internal object SyntaxLanguages {
    private fun words(s: String) = s.split(' ').filter { it.isNotEmpty() }.toSet()

    private val kotlin = SyntaxSpec(
        keywords = words(
            "package import class interface object fun val var if else when for while do return break continue " +
                "throw try catch finally in is as null true false this super override open abstract final sealed data " +
                "enum annotation companion private public protected internal inline suspend lateinit by init " +
                "constructor typealias where out vararg reified crossinline noinline operator infix tailrec const " +
                "external expect actual"
        ),
        lineComments = listOf("//"), blockStart = "/*", blockEnd = "*/", tripleQuotes = true, annotations = true
    )
    private val java = SyntaxSpec(
        keywords = words(
            "package import class interface enum extends implements new if else switch case default for while do " +
                "return break continue throw throws try catch finally null true false this super public private " +
                "protected static final abstract synchronized volatile transient native void int long double float " +
                "boolean char byte short instanceof assert var record sealed permits yield def"
        ),
        lineComments = listOf("//"), blockStart = "/*", blockEnd = "*/", annotations = true
    )
    private val jsTs = SyntaxSpec(
        keywords = words(
            "function const let var if else switch case default for while do return break continue throw try " +
                "catch finally new delete typeof instanceof in of void this super class extends import export from " +
                "as async await yield null undefined true false static get set interface type enum implements " +
                "private public protected readonly abstract declare namespace"
        ),
        lineComments = listOf("//"), blockStart = "/*", blockEnd = "*/", quotes = "\"'`", multilineQuotes = "`",
        annotations = true
    )
    private val cFamily = SyntaxSpec(
        keywords = words(
            "if else switch case default for while do return break continue goto struct union enum typedef " +
                "static const extern volatile register inline sizeof void int long short char float double signed " +
                "unsigned bool true false null nullptr NULL class public private protected virtual override " +
                "namespace using template typename new delete this try catch throw auto final explicit operator " +
                "friend constexpr noexcept include define ifdef ifndef endif pragma string var foreach in is as " +
                "base readonly sealed abstract interface lock async await"
        ),
        lineComments = listOf("//"), blockStart = "/*", blockEnd = "*/"
    )
    private val go = SyntaxSpec(
        keywords = words(
            "package import func var const type struct interface map chan if else switch case default for range " +
                "return break continue goto defer go select fallthrough nil true false"
        ),
        lineComments = listOf("//"), blockStart = "/*", blockEnd = "*/", quotes = "\"'`", multilineQuotes = "`"
    )
    private val rust = SyntaxSpec(
        keywords = words(
            "fn let mut const static struct enum impl trait pub use mod crate self Self super if else match for " +
                "while loop return break continue as in where move ref type dyn unsafe async await true false"
        ),
        lineComments = listOf("//"), blockStart = "/*", blockEnd = "*/", quotes = "\""
    )
    private val python = SyntaxSpec(
        keywords = words(
            "def class if elif else for while return break continue pass import from as try except finally " +
                "raise with lambda yield global nonlocal in is not and or None True False async await del assert"
        ),
        lineComments = listOf("#"), tripleQuotes = true, annotations = true
    )
    private val shell = SyntaxSpec(
        keywords = words(
            "if then else elif fi for while until do done case esac in function return break continue exit " +
                "export local readonly unset echo cd source alias true false"
        ),
        lineComments = listOf("#")
    )
    private val config = SyntaxSpec(
        keywords = words("true false null yes no on off"),
        lineComments = listOf("#")
    )
    private val json = SyntaxSpec(keywords = words("true false null"), quotes = "\"", jsonKeys = true)
    private val css = SyntaxSpec(blockStart = "/*", blockEnd = "*/")
    private val markup = SyntaxSpec(kind = SyntaxKind.Markup)
    private val markdown = SyntaxSpec(kind = SyntaxKind.Markdown)

    fun specFor(fileName: String?): SyntaxSpec? {
        if (fileName.isNullOrEmpty()) return null
        val name = fileName.lowercase()
        when (name) {
            "dockerfile", "makefile", ".gitignore", ".gitattributes", ".env" -> return config
        }
        return when (name.substringAfterLast('.', "")) {
            "kt", "kts" -> kotlin
            "java", "gradle" -> java
            "js", "mjs", "cjs", "jsx", "ts", "tsx" -> jsTs
            "c", "h", "cpp", "cc", "cxx", "hpp", "cs" -> cFamily
            "go" -> go
            "rs" -> rust
            "py" -> python
            "sh", "bash", "zsh" -> shell
            "yml", "yaml", "toml", "ini", "conf", "cfg", "properties" -> config
            "json", "jsonc" -> json
            "css" -> css
            "xml", "html", "htm", "xhtml", "svg" -> markup
            "md", "markdown" -> markdown
            else -> null
        }
    }
}
