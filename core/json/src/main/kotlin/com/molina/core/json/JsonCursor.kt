package com.molina.core.json

/**
 * Kursor baca JSON ketat untuk [MiniJson]. [build] = false: hanya memeriksa sintaks (tanpa Map/List, tanpa konversi angka).
 * Kedalaman dibatasi [JsonLimits.maxDepth], jadi rekursi aman. Galat -> [JsonException].
 */
internal class JsonCursor(private val s: String, private val lim: JsonLimits, private val build: Boolean) {
    private var i = 0

    fun readDocument(): Any? {
        ws()
        val v = value(0)
        ws()
        if (i != s.length) throw JsonException("sisa teks")
        return v
    }

    private fun bad(): Nothing = throw JsonException("JSON rusak")

    private fun c(): Char = if (i < s.length) s[i] else '\u0000'

    private fun ws() {
        while (c() == ' ' || c() == '\n' || c() == '\r' || c() == '\t') i++
    }

    private fun value(depth: Int): Any? {
        if (i >= s.length) bad()
        return when (s[i]) {
            '{' -> obj(depth + 1)
            '[' -> arr(depth + 1)
            '"' -> str()
            't' -> lit("true", true)
            'f' -> lit("false", false)
            'n' -> lit("null", null)
            else -> num()
        }
    }

    private fun lit(w: String, r: Any?): Any? {
        if (!s.startsWith(w, i)) bad()
        i += w.length
        return r
    }

    private fun obj(d: Int): Any? {
        if (d > lim.maxDepth) bad()
        i++
        val m = if (build) LinkedHashMap<String, Any?>() else null
        ws()
        if (c() == '}') { i++; return m }
        while (true) {
            ws()
            if (c() != '"') bad()
            val k = str()
            ws()
            if (c() != ':') bad()
            i++
            ws()
            val v = value(d)
            m?.put(k, v)
            ws()
            when (c()) {
                ',' -> i++
                '}' -> { i++; return m }
                else -> bad()
            }
        }
    }

    private fun arr(d: Int): Any? {
        if (d > lim.maxDepth) bad()
        i++
        val l = if (build) ArrayList<Any?>() else null
        ws()
        if (c() == ']') { i++; return l }
        while (true) {
            ws()
            val v = value(d)
            l?.add(v)
            ws()
            when (c()) {
                ',' -> i++
                ']' -> { i++; return l }
                else -> bad()
            }
        }
    }

    private fun digits() {
        if (c() !in '0'..'9') bad()
        while (c() in '0'..'9') i++
    }

    /** Tata bahasa angka RFC 8259: -?(0|[1-9][0-9]*)(.[0-9]+)?([eE][+-]?[0-9]+)? */
    private fun num(): Any? {
        val a = i
        if (c() == '-') i++
        when (c()) {
            '0' -> i++
            in '1'..'9' -> while (c() in '0'..'9') i++
            else -> bad()
        }
        var integral = true
        if (c() == '.') { integral = false; i++; digits() }
        if (c() == 'e' || c() == 'E') {
            integral = false
            i++
            if (c() == '+' || c() == '-') i++
            digits()
        }
        if (!build) return null
        val t = s.substring(a, i)
        if (integral && lim.numbers == JsonNumbers.AUTO) t.toLongOrNull()?.let { return it }
        val d = t.toDouble()
        if (!d.isFinite()) bad()
        return d
    }

    private fun hex(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> -1
    }

    private fun str(): String {
        i++
        val b = StringBuilder()
        while (true) {
            if (i >= s.length) bad()
            val x = s[i++]
            if (x == '"') return b.toString()
            if (x < ' ') bad()
            if (x != '\\') { b.append(x); continue }
            if (i >= s.length) bad()
            when (val e = s[i++]) {
                '"', '\\', '/' -> b.append(e)
                'b' -> b.append('\b')
                'f' -> b.append('\u000C')
                'n' -> b.append('\n')
                'r' -> b.append('\r')
                't' -> b.append('\t')
                'u' -> {
                    if (i + 4 > s.length) bad()
                    var code = 0
                    for (k in 0 until 4) {
                        val h = hex(s[i + k])
                        if (h < 0) bad()
                        code = code * 16 + h
                    }
                    b.append(code.toChar())
                    i += 4
                }
                else -> bad()
            }
        }
    }
}
