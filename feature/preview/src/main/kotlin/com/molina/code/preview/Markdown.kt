package com.molina.code.preview

/** Potongan teks inline. [flags] adalah gabungan [MdFlag]; [href] terisi bila bagian dari tautan. */
class MdSpan(val text: String, val flags: Int = 0, val href: String? = null)

object MdFlag {
    const val BOLD = 1
    const val ITALIC = 2
    const val CODE = 4
    const val STRIKE = 8
    const val LINK = 16
}

sealed class MdBlock {
    class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock()
    class Paragraph(val spans: List<MdSpan>) : MdBlock()
    class Code(val lang: String, val text: String) : MdBlock()
    class Quote(val spans: List<MdSpan>) : MdBlock()
    class Item(val depth: Int, val marker: String, val checked: Boolean?, val spans: List<MdSpan>) : MdBlock()
    object Rule : MdBlock()
    class Table(val header: List<List<MdSpan>>, val rows: List<List<List<MdSpan>>>) : MdBlock()
}

/**
 * Parser Markdown ringkas, murni Kotlin (tanpa dependency) untuk pratinjau native. Mendukung judul,
 * paragraf, kutipan, daftar (bertingkat, bernomor, tugas), blok kode berpagar, garis, tabel, dan
 * inline tebal/miring/coret/kode/tautan. HTML mentah dan gambar tidak dirender (gambar jadi teks).
 */
/** Hasil urai: [blocks] sejajar dengan [startLines] (baris sumber, basis 0, tempat tiap blok mulai). */
class MdDoc(val blocks: List<MdBlock>, val startLines: List<Int>)

object Markdown {
    private val HEADING = Regex("^ {0,3}(#{1,6})[ \\t]+(.*?)(?:[ \\t]+#+)?[ \\t]*$")
    private val RULE = Regex("^ {0,3}([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$")
    private val QUOTE = Regex("^ {0,3}>[ ]?(.*)$")
    private val ITEM = Regex("^([ \\t]*)([-*+]|\\d{1,9}[.)])[ \\t]+(.*)$")
    private val TABLE_SEP = Regex("^[ \\t]*\\|?[ \\t]*:?-+:?[ \\t]*(\\|[ \\t]*:?-+:?[ \\t]*)*\\|?[ \\t]*$")
    private val FENCE = Regex("^ {0,3}(`{3,}|~{3,})[ \\t]*([^`]*)$")

    fun parse(source: String): List<MdBlock> = parseWithLines(source).blocks

    fun parseWithLines(source: String): MdDoc {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<MdBlock>()
        val starts = ArrayList<Int>()
        var i = 0
        var blockStart = 0
        while (i < lines.size) {
            // tiap putaran menambah paling banyak satu blok: catat awal blok putaran sebelumnya
            while (starts.size < out.size) starts.add(blockStart)
            blockStart = i
            val line = lines[i]
            if (line.isBlank()) { i++; continue }

            val fence = FENCE.find(line)
            if (fence != null) {
                val marker = fence.groupValues[1]
                val lang = fence.groupValues[2].trim().substringBefore(' ')
                val body = StringBuilder()
                i++
                while (i < lines.size) {
                    val t = lines[i].trim()
                    if (t.length >= marker.length && t.all { it == marker[0] }) { i++; break }
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines[i])
                    i++
                }
                out.add(MdBlock.Code(lang, body.toString()))
                continue
            }

            val h = HEADING.find(line)
            if (h != null) {
                out.add(MdBlock.Heading(h.groupValues[1].length, Inline.parse(h.groupValues[2])))
                i++
                continue
            }

            if (RULE.matches(line)) { out.add(MdBlock.Rule); i++; continue }

            if (isTableStart(lines, i)) {
                val header = splitRow(line).map { Inline.parse(it) }
                i += 2
                val rows = ArrayList<List<List<MdSpan>>>()
                while (i < lines.size && lines[i].isNotBlank() && '|' in lines[i]) {
                    val cells = splitRow(lines[i]).map { Inline.parse(it) }
                    rows.add(List(header.size) { c -> cells.getOrElse(c) { emptyList() } })
                    i++
                }
                out.add(MdBlock.Table(header, rows))
                continue
            }

            if (QUOTE.matches(line)) {
                val buf = StringBuilder()
                while (i < lines.size) {
                    val q = QUOTE.find(lines[i]) ?: break
                    if (buf.isNotEmpty()) buf.append(' ')
                    buf.append(q.groupValues[1].trim())
                    i++
                }
                out.add(MdBlock.Quote(Inline.parse(buf.toString().trim())))
                continue
            }

            val item = ITEM.find(line)
            if (item != null && !RULE.matches(line)) {
                val indent = item.groupValues[1].replace("\t", "    ").length
                val rawMarker = item.groupValues[2]
                var body = item.groupValues[3]
                i++
                while (i < lines.size && lines[i].isNotBlank() && lines[i].startsWith("  ") &&
                    ITEM.find(lines[i]) == null && !FENCE.matches(lines[i])
                ) {
                    body += " " + lines[i].trim()
                    i++
                }
                var checked: Boolean? = null
                if (body.startsWith("[ ] ")) { checked = false; body = body.substring(4) }
                else if (body.startsWith("[x] ") || body.startsWith("[X] ")) { checked = true; body = body.substring(4) }
                val marker = if (rawMarker[0].isDigit()) rawMarker else "\u2022"
                out.add(MdBlock.Item(indent / 2, marker, checked, Inline.parse(body.trim())))
                continue
            }

            // Paragraf: kumpulkan sampai baris kosong atau awal blok lain.
            val buf = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank() && (buf.isEmpty() || !startsBlock(lines, i))) {
                val l = lines[i]
                if (buf.isNotEmpty()) buf.append(if (buf.endsWith("  ") || buf.endsWith("\\")) "\n" else " ")
                buf.append(l.trimStart())
                i++
            }
            val text = buf.toString().trimEnd().removeSuffix("\\")
            out.add(MdBlock.Paragraph(Inline.parse(text.trimEnd())))
        }
        while (starts.size < out.size) starts.add(blockStart)
        return MdDoc(out, starts)
    }

    private fun startsBlock(lines: List<String>, i: Int): Boolean {
        val l = lines[i]
        return FENCE.matches(l) || HEADING.matches(l) || RULE.matches(l) || QUOTE.matches(l) ||
            ITEM.matches(l) || isTableStart(lines, i)
    }

    private fun isTableStart(lines: List<String>, i: Int): Boolean {
        if (i + 1 >= lines.size) return false
        val head = lines[i]
        val sep = lines[i + 1]
        return '|' in head && '-' in sep && '|' in sep && TABLE_SEP.matches(sep)
    }

    private fun splitRow(row: String): List<String> {
        var t = row.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        val cells = ArrayList<String>()
        val sb = StringBuilder()
        var k = 0
        while (k < t.length) {
            val c = t[k]
            if (c == '\\' && k + 1 < t.length && t[k + 1] == '|') { sb.append('|'); k += 2; continue }
            if (c == '|') { cells.add(sb.toString().trim()); sb.setLength(0) } else sb.append(c)
            k++
        }
        cells.add(sb.toString().trim())
        return cells
    }
}

/** Parser inline: menghasilkan daftar [MdSpan] datar. */
object Inline {
    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~<>"

    fun parse(s: String): List<MdSpan> {
        val out = ArrayList<MdSpan>()
        parseInto(s, 0, null, out)
        return out
    }

    private fun parseInto(s: String, baseFlags: Int, href: String?, out: MutableList<MdSpan>) {
        val sb = StringBuilder()
        var flags = baseFlags
        fun flush() {
            if (sb.isNotEmpty()) {
                out.add(MdSpan(sb.toString(), flags, href))
                sb.setLength(0)
            }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE -> {
                    sb.append(s[i + 1]); i += 2
                }
                c == '`' -> {
                    var n = 0
                    while (i + n < s.length && s[i + n] == '`') n++
                    val closer = "`".repeat(n)
                    val end = findRun(s, closer, i + n)
                    if (end > 0) {
                        flush()
                        out.add(MdSpan(s.substring(i + n, end).trim(), flags or MdFlag.CODE, href))
                        i = end + n
                    } else {
                        sb.append(closer); i += n
                    }
                }
                c == '!' && i + 1 < s.length && s[i + 1] == '[' -> {
                    val close = s.indexOf(']', i + 2)
                    val paren = if (close > 0 && close + 1 < s.length && s[close + 1] == '(') s.indexOf(')', close + 2) else -1
                    if (paren > 0) {
                        flush()
                        val alt = s.substring(i + 2, close).ifBlank { "gambar" }
                        out.add(MdSpan("[gambar: $alt]", flags or MdFlag.ITALIC, href))
                        i = paren + 1
                    } else {
                        sb.append(c); i++
                    }
                }
                c == '[' -> {
                    val close = s.indexOf(']', i + 1)
                    val paren = if (close > 0 && close + 1 < s.length && s[close + 1] == '(') s.indexOf(')', close + 2) else -1
                    if (close > 0 && paren > 0) {
                        flush()
                        val label = s.substring(i + 1, close)
                        val target = s.substring(close + 2, paren).trim().substringBefore(' ')
                        parseInto(label, flags or MdFlag.LINK, target, out)
                        i = paren + 1
                    } else {
                        sb.append(c); i++
                    }
                }
                c == '<' && isAutolink(s, i) -> {
                    val end = s.indexOf('>', i)
                    flush()
                    val url = s.substring(i + 1, end)
                    out.add(MdSpan(url, flags or MdFlag.LINK, url))
                    i = end + 1
                }
                c == 'h' && href == null && startsBareUrl(s, i) && (i == 0 || !s[i - 1].isLetterOrDigit()) -> {
                    var end = i
                    while (end < s.length && !s[end].isWhitespace() && s[end] != '<' && s[end] != ')') end++
                    while (end > i && s[end - 1] in ".,;:!?") end--
                    flush()
                    val url = s.substring(i, end)
                    out.add(MdSpan(url, flags or MdFlag.LINK, url))
                    i = end
                }
                c == '~' && i + 1 < s.length && s[i + 1] == '~' -> {
                    val active = flags and MdFlag.STRIKE != 0
                    if (active || s.indexOf("~~", i + 2) > 0) {
                        flush(); flags = flags xor MdFlag.STRIKE; i += 2
                    } else {
                        sb.append("~~"); i += 2
                    }
                }
                c == '*' || c == '_' -> {
                    var n = 0
                    while (i + n < s.length && s[i + n] == c) n++
                    val prev = if (i > 0) s[i - 1] else ' '
                    val next = if (i + n < s.length) s[i + n] else ' '
                    val intraword = c == '_' && prev.isLetterOrDigit() && next.isLetterOrDigit()
                    val mask = when (n) { 1 -> MdFlag.ITALIC; 2 -> MdFlag.BOLD; 3 -> MdFlag.ITALIC or MdFlag.BOLD; else -> 0 }
                    val closing = mask != 0 && (flags and mask) == mask && !prev.isWhitespace()
                    val opening = mask != 0 && !closing && !next.isWhitespace() &&
                        s.indexOf(c.toString().repeat(n), i + n) > 0
                    if (!intraword && (closing || opening)) {
                        flush(); flags = flags xor mask
                    } else {
                        sb.append(c.toString().repeat(n))
                    }
                    i += n
                }
                else -> { sb.append(c); i++ }
            }
        }
        flush()
    }

    /** Posisi awal run backtick persis [run] (tidak diapit backtick lain) mulai [from], atau -1. */
    private fun findRun(s: String, run: String, from: Int): Int {
        var k = s.indexOf(run, from)
        while (k >= 0) {
            val before = k > 0 && s[k - 1] == '`'
            val after = k + run.length < s.length && s[k + run.length] == '`'
            if (!before && !after) return k
            k = s.indexOf(run, k + 1)
        }
        return -1
    }

    private fun isAutolink(s: String, i: Int): Boolean {
        val end = s.indexOf('>', i)
        if (end < 0) return false
        val body = s.substring(i + 1, end)
        return !body.contains(' ') && (body.startsWith("http://") || body.startsWith("https://"))
    }

    private fun startsBareUrl(s: String, i: Int): Boolean =
        s.startsWith("http://", i) || s.startsWith("https://", i)
}
