package com.molina.core.common

/** Kursor baca JSON untuk [MiniJson]: pemindai skalar. Galat -> IllegalStateException. */
internal class JsonCursor(private val s: String) {
    var i = 0
    val atEnd get() = i == s.length
    fun c() = if (i < s.length) s[i] else '\u0000'
    fun f(): Nothing = throw IllegalStateException()
    fun ws() { while (c() == ' ' || c() == '\n' || c() == '\r' || c() == '\t') i++ }
    fun end(q: Char): Boolean { ws(); if (c() != q) return false; i++; return true }
    fun more(q: Char): Boolean {
        ws()
        val x = c()
        i++
        return if (x == ',') true else if (x == q) false else f()
    }

    fun lit(w: String, r: Any?): Any? {
        if (!s.startsWith(w, i)) f()
        i += w.length
        return r
    }

    fun num(): Any {
        val a = i
        while (c() in '0'..'9' || c() in "+-.eE") i++
        val t = s.substring(a, i)
        return t.toLongOrNull() ?: t.toDoubleOrNull()?.takeIf { it.isFinite() } ?: f()
    }

    fun str(): String {
        i++
        val b = StringBuilder()
        while (true) {
            val x = if (i < s.length) s[i++] else f()
            if (x == '"') return b.toString()
            if (x < ' ') f()
            if (x != '\\') { b.append(x); continue }
            val e = if (i < s.length) s[i++] else f()
            val k = "\"\\/bfnrt".indexOf(e)
            if (k >= 0) { b.append("\"\\/\b\u000c\n\r\t"[k]); continue }
            if (e != 'u' || i + 4 > s.length) f()
            val h = s.substring(i, i + 4)
            if (h.any { Character.digit(it, 16) < 0 }) f()
            b.append(h.toInt(16).toChar())
            i += 4
        }
    }
}
