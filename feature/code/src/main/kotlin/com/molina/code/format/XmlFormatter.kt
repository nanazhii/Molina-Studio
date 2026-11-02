package com.molina.code.format

/**
 * Pemformat XML ringan [CODE-FORMAT]: murni, tanpa parser DOM. Hanya menata ulang spasi PEMISAH
 * antar-simpul. Teks bermakna, CDATA, komentar, deklarasi, entitas, dan nilai atribut tidak diubah.
 * Elemen yang berisi teks non-spasi (konten campuran) atau ber-`xml:space="preserve"` disalin
 * apa adanya. Tag yang ditulis multibaris diluruskan: lanjutan atribut menjorok dua tingkat (4 spasi).
 * Masukan yang tidak well-formed (tag penutup tak cocok, tag tak ditutup) ditolak dengan baris:kolom.
 */
object XmlFormatter {
    private sealed class Node
    private class Leaf(val raw: String) : Node()
    private class Text(val raw: String) : Node()
    private class El(
        val name: String,
        val open: String,
        val selfClosing: Boolean,
        val innerStart: Int,
        var innerEnd: Int = 0,
        var close: String = "",
        val children: ArrayList<Node> = ArrayList(),
    ) : Node()

    fun format(src: String, indent: String = "  "): String {
        val s = src.removePrefix("\uFEFF")
        if (s.isBlank()) throw FormatException("Berkas kosong")
        val roots = parse(s)
        val sb = StringBuilder(s.length + s.length / 4)
        for (n in roots) print(n, 0, s, indent, sb)
        return sb.toString()
    }

    private fun fail(s: String, at: Int, msg: String): Nothing = throw FormatException("$msg (${lineCol(s, at)})")

    private fun parse(s: String): List<Node> {
        val roots = ArrayList<Node>()
        val stack = ArrayList<El>()
        fun add(n: Node) { if (stack.isEmpty()) roots.add(n) else stack.last().children.add(n) }
        var i = 0
        while (i < s.length) {
            if (s[i] != '<') {
                val j = s.indexOf('<', i).let { if (it < 0) s.length else it }
                val raw = s.substring(i, j)
                if (raw.isNotBlank()) {
                    if (stack.isEmpty()) fail(s, i, "Teks di luar elemen akar")
                    add(Text(raw))
                } else if (stack.isNotEmpty()) {
                    add(Text(raw))
                }
                i = j
                continue
            }
            when {
                s.startsWith("<!--", i) -> {
                    val e = s.indexOf("-->", i + 4).let { if (it < 0) fail(s, i, "Komentar tidak ditutup") else it + 3 }
                    add(Leaf(s.substring(i, e))); i = e
                }
                s.startsWith("<![CDATA[", i) -> {
                    val e = s.indexOf("]]>", i + 9).let { if (it < 0) fail(s, i, "CDATA tidak ditutup") else it + 3 }
                    if (stack.isEmpty()) fail(s, i, "CDATA di luar elemen akar")
                    add(Text(s.substring(i, e))); i = e // dianggap teks bermakna
                }
                s.startsWith("<?", i) -> {
                    val e = s.indexOf("?>", i + 2).let { if (it < 0) fail(s, i, "Instruksi pemrosesan tidak ditutup") else it + 2 }
                    add(Leaf(s.substring(i, e))); i = e
                }
                s.startsWith("<!", i) -> {
                    val e = declEnd(s, i) ?: fail(s, i, "Deklarasi tidak ditutup")
                    add(Leaf(s.substring(i, e))); i = e
                }
                s.startsWith("</", i) -> {
                    val e = s.indexOf('>', i).let { if (it < 0) fail(s, i, "Tag penutup tidak selesai") else it + 1 }
                    val name = s.substring(i + 2, e - 1).trim()
                    val top = stack.lastOrNull() ?: fail(s, i, "Tag penutup </$name> tanpa pembuka")
                    if (top.name != name) fail(s, i, "Tag penutup </$name> tidak cocok dengan <${top.name}>")
                    top.innerEnd = i
                    top.close = "</$name>"
                    stack.removeAt(stack.size - 1)
                    i = e
                }
                else -> {
                    val e = tagEnd(s, i) ?: fail(s, i, "Tag tidak selesai")
                    val raw = s.substring(i, e)
                    val self = raw.endsWith("/>")
                    var k = 1
                    while (k < raw.length && raw[k] !in " \t\r\n/>") k++
                    val name = raw.substring(1, k)
                    if (name.isEmpty()) fail(s, i, "Nama tag kosong")
                    val el = El(name, raw, self, e)
                    if (self) el.innerEnd = e
                    add(el)
                    if (!self) {
                        if (stack.size >= MAX_DEPTH) fail(s, i, "Terlalu dalam (maks. $MAX_DEPTH)")
                        stack.add(el)
                    }
                    i = e
                }
            }
        }
        stack.lastOrNull()?.let { fail(s, s.length, "Tag <${it.name}> tidak ditutup") }
        return roots
    }

    /** Akhir tag pembuka dari [start], mengabaikan '>' di dalam nilai atribut ber-kutip. */
    private fun tagEnd(s: String, start: Int): Int? {
        var quote = 0.toChar()
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            if (quote != 0.toChar()) { if (c == quote) quote = 0.toChar() }
            else if (c == '"' || c == '\'') quote = c
            else if (c == '>') return i + 1
            i++
        }
        return null
    }

    /** Akhir `<!DOCTYPE ...>` termasuk subset internal `[...]`. */
    private fun declEnd(s: String, start: Int): Int? {
        var depth = 0
        var quote = 0.toChar()
        var i = start + 2
        while (i < s.length) {
            val c = s[i]
            if (quote != 0.toChar()) { if (c == quote) quote = 0.toChar() }
            else when (c) {
                '"', '\'' -> quote = c
                '[' -> depth++
                ']' -> depth--
                '>' -> if (depth <= 0) return i + 1
            }
            i++
        }
        return null
    }

    private fun pad(sb: StringBuilder, depth: Int, indent: String) { repeat(depth) { sb.append(indent) } }

    /** Tulis tag [raw]; baris lanjutan (tag multibaris) dijajarkan di [depth]+2. */
    private fun tagText(raw: String, depth: Int, indent: String): String {
        if ('\n' !in raw) return raw
        val lines = raw.split('\n')
        val sb = StringBuilder(lines[0].trimEnd('\r'))
        for (k in 1 until lines.size) {
            sb.append('\n')
            pad(sb, depth + 2, indent) // gaya Android Studio: atribut menjorok 2 tingkat dari tag
            sb.append(lines[k].trim())
        }
        return sb.toString()
    }

    private fun print(n: Node, depth: Int, s: String, indent: String, sb: StringBuilder) {
        when (n) {
            is Leaf -> { pad(sb, depth, indent); sb.append(n.raw.trim()); sb.append('\n') }
            is Text -> { pad(sb, depth, indent); sb.append(n.raw.trim()); sb.append('\n') }
            is El -> {
                pad(sb, depth, indent)
                sb.append(tagText(n.open, depth, indent))
                if (n.selfClosing) { sb.append('\n'); return }
                val significant = n.children.any { it is Text && it.raw.isNotBlank() } ||
                    Regex("xml:space\\s*=\\s*[\"']preserve[\"']").containsMatchIn(n.open)
                if (significant) {
                    sb.append(s, n.innerStart, n.innerEnd).append(n.close).append('\n')
                    return
                }
                val kids = n.children.filter { !(it is Text && it.raw.isBlank()) }
                if (kids.isEmpty()) { sb.append(n.close).append('\n'); return }
                sb.append('\n')
                for (k in kids) print(k, depth + 1, s, indent, sb)
                pad(sb, depth, indent)
                sb.append(n.close).append('\n')
            }
        }
    }

    const val MAX_DEPTH = 300
}
