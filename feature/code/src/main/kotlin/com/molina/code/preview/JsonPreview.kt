package com.molina.code.preview

enum class JsonTok { KEY, STRING, NUMBER, LITERAL, PUNCT, SPACE }

/** Potongan teks hasil cetak rapi; [start, end) menunjuk ke [JsonPretty.Ok.text]. */
class JsonRun(val start: Int, val end: Int, val kind: JsonTok)

sealed class JsonPretty {
    class Ok(val text: String, val runs: List<JsonRun>, val lineCount: Int) : JsonPretty()
    class Invalid(val message: String, val line: Int, val column: Int, val snippet: String) : JsonPretty()
    class TooLarge(val chars: Int, val limit: Int) : JsonPretty()
}

/**
 * Validator + pencetak rapi JSON, murni Kotlin (tanpa org.json) supaya bisa diuji di JVM dan
 * melaporkan posisi galat (baris:kolom, basis 1). Ketat sesuai RFC 8259; kedalaman dibatasi.
 */
object JsonPreview {
    const val MAX_CHARS = 200_000
    private const val MAX_DEPTH = 120

    fun render(source: String, maxChars: Int = MAX_CHARS): JsonPretty {
        if (source.length > maxChars) return JsonPretty.TooLarge(source.length, maxChars)
        val p = Parser(source)
        return try {
            p.document()
            val text = p.sb.toString()
            JsonPretty.Ok(text, p.runs, text.count { it == '\n' } + 1)
        } catch (e: JsonErr) {
            val (line, col) = lineCol(source, e.at)
            JsonPretty.Invalid(e.message ?: "JSON tidak valid", line, col, snippet(source, e.at))
        }
    }

    private class JsonErr(msg: String, val at: Int) : Exception(msg)

    private fun lineCol(s: String, at: Int): Pair<Int, Int> {
        val pos = at.coerceIn(0, s.length)
        var line = 1
        var last = -1
        for (k in 0 until pos) if (s[k] == '\n') { line++; last = k }
        return line to (pos - last)
    }

    private fun snippet(s: String, at: Int): String {
        val pos = at.coerceIn(0, s.length)
        val a = s.lastIndexOf('\n', pos - 1).let { if (it < 0) 0 else it + 1 }
        val b = s.indexOf('\n', pos).let { if (it < 0) s.length else it }
        val line = s.substring(a, b)
        return if (line.length > 120) line.take(120) + "\u2026" else line
    }

    private class Parser(val s: String) {
        var p = 0
        val sb = StringBuilder()
        val runs = ArrayList<JsonRun>()

        fun fail(msg: String, at: Int = p): Nothing = throw JsonErr(msg, at)

        fun emit(text: String, kind: JsonTok) {
            val start = sb.length
            sb.append(text)
            runs.add(JsonRun(start, sb.length, kind))
        }

        fun newline(level: Int) = emit("\n" + "  ".repeat(level), JsonTok.SPACE)

        fun ws() {
            while (p < s.length && (s[p] == ' ' || s[p] == '\t' || s[p] == '\n' || s[p] == '\r')) p++
        }

        fun document() {
            ws()
            if (p >= s.length) fail("Dokumen kosong")
            value(0)
            ws()
            if (p < s.length) fail("Ada teks setelah akhir dokumen")
        }

        fun value(level: Int) {
            if (level > MAX_DEPTH) fail("Terlalu dalam (maks $MAX_DEPTH tingkat)")
            if (p >= s.length) fail("Teks berakhir tak terduga")
            when (val c = s[p]) {
                '{' -> obj(level)
                '[' -> arr(level)
                '"' -> string(JsonTok.STRING)
                '-', in '0'..'9' -> number()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> fail("Karakter tak terduga '$c'")
            }
        }

        fun obj(level: Int) {
            emit("{", JsonTok.PUNCT); p++
            ws()
            if (p < s.length && s[p] == '}') { emit("}", JsonTok.PUNCT); p++; return }
            while (true) {
                newline(level + 1)
                ws()
                if (p >= s.length) fail("Teks berakhir tak terduga")
                if (s[p] != '"') fail("Kunci objek harus berupa string")
                string(JsonTok.KEY)
                ws()
                if (p >= s.length || s[p] != ':') fail("Diharapkan ':' setelah kunci")
                emit(":", JsonTok.PUNCT); emit(" ", JsonTok.SPACE); p++
                ws()
                value(level + 1)
                ws()
                if (p >= s.length) fail("Teks berakhir tak terduga")
                when (s[p]) {
                    ',' -> {
                        emit(",", JsonTok.PUNCT); p++
                        ws()
                        if (p < s.length && s[p] == '}') fail("Koma berlebih sebelum '}'")
                    }
                    '}' -> { newline(level); emit("}", JsonTok.PUNCT); p++; return }
                    else -> fail("Diharapkan ',' atau '}'")
                }
            }
        }

        fun arr(level: Int) {
            emit("[", JsonTok.PUNCT); p++
            ws()
            if (p < s.length && s[p] == ']') { emit("]", JsonTok.PUNCT); p++; return }
            while (true) {
                newline(level + 1)
                ws()
                value(level + 1)
                ws()
                if (p >= s.length) fail("Teks berakhir tak terduga")
                when (s[p]) {
                    ',' -> {
                        emit(",", JsonTok.PUNCT); p++
                        ws()
                        if (p < s.length && s[p] == ']') fail("Koma berlebih sebelum ']'")
                    }
                    ']' -> { newline(level); emit("]", JsonTok.PUNCT); p++; return }
                    else -> fail("Diharapkan ',' atau ']'")
                }
            }
        }

        fun string(kind: JsonTok) {
            val start = p
            p++
            while (true) {
                if (p >= s.length) fail("Kutip penutup string hilang", start)
                val c = s[p]
                when {
                    c == '"' -> { p++; break }
                    c == '\\' -> {
                        if (p + 1 >= s.length) fail("Escape tak lengkap")
                        when (s[p + 1]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> p += 2
                            'u' -> {
                                if (p + 6 > s.length || !(2..5).all { s[p + it].isHex() }) fail("Escape \\u harus 4 digit heksadesimal")
                                p += 6
                            }
                            else -> fail("Escape tidak dikenal '\\${s[p + 1]}'")
                        }
                    }
                    c < ' ' -> fail("Karakter kontrol tidak boleh ada di dalam string")
                    else -> p++
                }
            }
            emit(s.substring(start, p), kind)
        }

        fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        fun number() {
            val start = p
            if (s[p] == '-') p++
            if (p >= s.length) fail("Angka tidak lengkap")
            if (s[p] == '0') {
                p++
            } else if (s[p] in '1'..'9') {
                while (p < s.length && s[p] in '0'..'9') p++
            } else fail("Angka tidak valid")
            if (p < s.length && s[p] == '.') {
                p++
                val d = p
                while (p < s.length && s[p] in '0'..'9') p++
                if (p == d) fail("Angka butuh digit setelah titik")
            }
            if (p < s.length && (s[p] == 'e' || s[p] == 'E')) {
                p++
                if (p < s.length && (s[p] == '+' || s[p] == '-')) p++
                val d = p
                while (p < s.length && s[p] in '0'..'9') p++
                if (p == d) fail("Eksponen butuh digit")
            }
            emit(s.substring(start, p), JsonTok.NUMBER)
        }

        fun literal(word: String) {
            if (!s.startsWith(word, p)) fail("Karakter tak terduga '${s[p]}'")
            val end = p + word.length
            if (end < s.length && (s[end].isLetterOrDigit() || s[end] == '_')) fail("Nilai tidak dikenal", p)
            emit(word, JsonTok.LITERAL)
            p = end
        }
    }
}
