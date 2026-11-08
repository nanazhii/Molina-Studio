package com.molina.code.format

/**
 * Pemformat JSON ringan [CODE-FORMAT]. Murni (tanpa Android), sekali lintasan, tanpa rekursi.
 * Token string dan angka disalin APA ADANYA (escape, urutan kunci, dan bentuk angka tidak berubah);
 * hanya spasi di antara token yang ditata ulang. Masukan yang bukan JSON sah (komentar, koma
 * penutup, kutip tunggal) ditolak dengan pesan berisi baris:kolom, tidak pernah ditebak.
 */
object JsonFormatter {
    private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    private enum class Expect { Value, ValueOrEnd, KeyOrEnd, Key, Colon, CommaOrEnd, Done }

    fun format(src: String, indent: String = "  "): String {
        val s = src.removePrefix("\uFEFF")
        if (s.isBlank()) throw FormatException("Berkas kosong")
        val sb = StringBuilder(s.length + s.length / 4)
        val stack = ArrayList<Char>() // '{' atau '['
        var expect = Expect.Value
        var justOpened = false
        var pending = false
        var i = 0

        fun fail(msg: String, at: Int): Nothing = throw FormatException("$msg (${lineCol(s, at)})")
        fun nl(depth: Int) {
            sb.append('\n')
            repeat(depth) { sb.append(indent) }
        }
        fun flush() {
            if (justOpened || pending) {
                nl(stack.size)
                justOpened = false
                pending = false
            }
        }
        fun afterValue() { expect = if (stack.isEmpty()) Expect.Done else Expect.CommaOrEnd }

        while (i < s.length) {
            val c = s[i]
            when {
                c == ' ' || c == '\t' || c == '\n' || c == '\r' -> i++
                c == '{' || c == '[' -> {
                    if (expect != Expect.Value && expect != Expect.ValueOrEnd) fail("Token tak terduga '$c'", i)
                    if (stack.size >= MAX_DEPTH) fail("Terlalu dalam (maks. $MAX_DEPTH)", i)
                    flush()
                    sb.append(c)
                    stack.add(c)
                    justOpened = true
                    expect = if (c == '{') Expect.KeyOrEnd else Expect.ValueOrEnd
                    i++
                }
                c == '}' || c == ']' -> {
                    val open = if (c == '}') '{' else '['
                    val emptyOk = if (c == '}') expect == Expect.KeyOrEnd else expect == Expect.ValueOrEnd
                    if (stack.isEmpty() || stack.last() != open || !(emptyOk || expect == Expect.CommaOrEnd)) {
                        fail("Token tak terduga '$c'", i)
                    }
                    stack.removeAt(stack.size - 1)
                    if (justOpened) {
                        sb.append(c)
                        justOpened = false
                    } else {
                        nl(stack.size)
                        sb.append(c)
                    }
                    pending = false
                    afterValue()
                    i++
                }
                c == ',' -> {
                    if (expect != Expect.CommaOrEnd) fail("Koma tak terduga", i)
                    sb.append(',')
                    pending = true
                    expect = if (stack.last() == '{') Expect.Key else Expect.Value
                    i++
                }
                c == ':' -> {
                    if (expect != Expect.Colon) fail("Titik dua tak terduga", i)
                    sb.append(": ")
                    expect = Expect.Value
                    i++
                }
                c == '"' -> {
                    val end = stringEnd(s, i) ?: fail("String tidak ditutup", i)
                    when (expect) {
                        Expect.Key, Expect.KeyOrEnd -> { flush(); sb.append(s, i, end); expect = Expect.Colon }
                        Expect.Value, Expect.ValueOrEnd -> { flush(); sb.append(s, i, end); afterValue() }
                        else -> fail("String tak terduga", i)
                    }
                    i = end
                }
                else -> {
                    var j = i
                    while (j < s.length && s[j] !in " \t\r\n,:]}{[\"") j++
                    val lit = s.substring(i, j)
                    if (expect != Expect.Value && expect != Expect.ValueOrEnd) fail("Nilai tak terduga '$lit'", i)
                    if (lit != "true" && lit != "false" && lit != "null" && !NUMBER.matches(lit)) {
                        fail("Nilai tidak sah '${lit.take(20)}'", i)
                    }
                    flush()
                    sb.append(lit)
                    afterValue()
                    i = j
                }
            }
        }
        if (expect != Expect.Done) fail("JSON belum lengkap", s.length)
        sb.append('\n')
        return sb.toString()
    }

    /** Indeks tepat setelah kutip penutup string yang dimulai di [start], atau null bila tak tertutup. */
    private fun stringEnd(s: String, start: Int): Int? {
        var i = start + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                '\n', '\r' -> return null
                else -> i++
            }
        }
        return null
    }

    const val MAX_DEPTH = 256
}

/** Galat pemformatan dengan pesan siap tampil. */
class FormatException(message: String) : Exception(message)

/** "baris 3, kol 7" (basis 1) untuk indeks [at] di [s]. */
internal fun lineCol(s: String, at: Int): String {
    var line = 1
    var col = 1
    val end = at.coerceIn(0, s.length)
    for (k in 0 until end) {
        if (s[k] == '\n') { line++; col = 1 } else col++
    }
    return "baris $line, kol $col"
}
