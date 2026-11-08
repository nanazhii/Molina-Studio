package com.molina.code.preview

/**
 * Pratinjau HTML sederhana yang tetap native: HTML diubah menjadi model blok yang sama dengan
 * Markdown ([MdDoc]), jadi dirender oleh komposabel yang sama (judul, paragraf, daftar bertingkat,
 * tabel, kutipan, blok kode, tautan, tebal/miring/kode/coret). Tidak ada CSS, JavaScript, gambar, atau
 * jaringan: <script>, <style>, <head>, <svg>, <template> dilewati. Toleran terhadap HTML rusak dan
 * tidak pernah melempar. Untuk halaman yang butuh CSS penuh tersedia opsi WebView di Settings.
 */
object HtmlPreview {
    private val SKIP = setOf("script", "style", "head", "svg", "template", "noscript", "iframe", "object")
    private val BLOCK = setOf(
        "p", "div", "section", "article", "header", "footer", "nav", "main", "aside", "figure",
        "figcaption", "form", "fieldset", "details", "summary", "address", "dl", "dt", "dd", "center"
    )
    private val VOID = setOf("br", "hr", "img", "input", "meta", "link", "area", "base", "col", "embed", "source", "wbr")
    private const val MAX_LIST_DEPTH = 12
    private val HREF = Regex("(?i)\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
    private val WS = Regex("[ \\t\\r\\n\\u000C]+")
    private val TAGS = Regex("<[^>]*>")
    private val ALT = Regex("(?i)\\balt\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")

    private val ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "copy" to "\u00A9", "reg" to "\u00AE", "trade" to "\u2122", "hellip" to "\u2026",
        "mdash" to "\u2014", "ndash" to "\u2013", "laquo" to "\u00AB", "raquo" to "\u00BB",
        "bull" to "\u2022", "middot" to "\u00B7", "rarr" to "\u2192", "larr" to "\u2190",
        "times" to "\u00D7", "euro" to "\u20AC", "lsquo" to "\u2018", "rsquo" to "\u2019",
        "ldquo" to "\u201C", "rdquo" to "\u201D", "deg" to "\u00B0", "plusmn" to "\u00B1"
    )

    fun parse(source: String): MdDoc = Builder(source).run()

    /** Ganti entitas (&amp; &#65; &#x41;) menjadi karakter; entitas tak dikenal dibiarkan apa adanya. */
    fun decode(s: String): String {
        if ('&' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i + 1)
                if (semi in (i + 2)..(i + 10)) {
                    val name = s.substring(i + 1, semi)
                    val rep = when {
                        name.startsWith("#x") || name.startsWith("#X") -> codePoint(name.substring(2), 16)
                        name.startsWith("#") -> codePoint(name.substring(1), 10)
                        else -> ENTITIES[name]
                    }
                    if (rep != null) { sb.append(rep); i = semi + 1; continue }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    private fun codePoint(digits: String, radix: Int): String? {
        val cp = digits.toIntOrNull(radix) ?: return null
        if (cp <= 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) return null
        if (cp < 0x20 && cp != 0x09 && cp != 0x0A) return null
        return String(Character.toChars(cp))
    }

    private enum class Kind { PARA, HEADING, ITEM, QUOTE, CELL }

    private class ListCtx(val ordered: Boolean) { var counter = 1 }

    private class Builder(val src: String) {
        val blocks = ArrayList<MdBlock>()
        val starts = ArrayList<Int>()

        // teks inline yang belum jadi blok
        var pending = ArrayList<MdSpan>()
        var kind = Kind.PARA
        var headingLevel = 1
        var itemDepth = 0
        var itemMarker = "\u2022"
        var pendingLine = -1
        var endsWithSpace = true

        var bold = 0; var italic = 0; var code = 0; var strike = 0
        val hrefs = ArrayList<String>()
        val lists = ArrayList<ListCtx>()
        var quoteDepth = 0

        // tabel (hanya tabel terluar)
        var tableDepth = 0
        var rows = ArrayList<List<List<MdSpan>>>()
        var row: ArrayList<List<MdSpan>>? = null
        var rowHasTh = false
        var headerRow: List<List<MdSpan>>? = null
        var tableLine = 0

        // penghitung baris sumber (monoton)
        var scan = 0
        var lineNo = 0
        fun lineAt(pos: Int): Int {
            val end = pos.coerceIn(scan, src.length)
            while (scan < end) { if (src[scan] == '\n') lineNo++; scan++ }
            return lineNo
        }

        fun emit(b: MdBlock, line: Int) {
            blocks.add(b)
            starts.add(maxOf(line, starts.lastOrNull() ?: 0))
        }

        fun addText(raw: String, pos: Int, preserve: Boolean = false) {
            var t = decode(raw)
            if (!preserve) {
                t = t.replace(WS, " ")
                if (t.isEmpty()) return
                if (endsWithSpace && t.startsWith(" ")) t = t.substring(1)
                if (t.isEmpty()) return
            }
            if (pendingLine < 0 && t.isNotBlank()) pendingLine = lineAt(pos)
            endsWithSpace = t.endsWith(" ") || t.endsWith("\n")
            pending.add(MdSpan(t, flags(), hrefs.lastOrNull()))
        }

        fun flags(): Int {
            var f = 0
            if (bold > 0) f = f or MdFlag.BOLD
            if (italic > 0) f = f or MdFlag.ITALIC
            if (code > 0) f = f or MdFlag.CODE
            if (strike > 0) f = f or MdFlag.STRIKE
            if (hrefs.isNotEmpty()) f = f or MdFlag.LINK
            return f
        }

        /** Rapikan spasi tepi lalu ambil span; kosong bila tidak ada teks. */
        fun takePending(): List<MdSpan> {
            val spans = pending
            pending = ArrayList()
            endsWithSpace = true
            if (spans.isEmpty()) return spans
            val first = spans.first()
            if (first.text.startsWith(" ")) spans[0] = MdSpan(first.text.trimStart(' '), first.flags, first.href)
            val last = spans.last()
            if (last.text.endsWith(" ")) spans[spans.size - 1] = MdSpan(last.text.trimEnd(' '), last.flags, last.href)
            val cleaned = spans.filter { it.text.isNotEmpty() }
            return if (cleaned.all { it.text.isBlank() }) emptyList() else cleaned
        }

        /** Jadikan teks tertunda sebagai blok sesuai [kind]. Dalam tabel, teks tetap menunggu sel selesai. */
        fun flush() {
            if (tableDepth > 0) return
            val line = if (pendingLine >= 0) pendingLine else (starts.lastOrNull() ?: 0)
            val spans = takePending()
            pendingLine = -1
            if (spans.isEmpty()) return
            val k = if (kind == Kind.PARA && quoteDepth > 0) Kind.QUOTE else kind
            when (k) {
                Kind.HEADING -> emit(MdBlock.Heading(headingLevel, spans), line)
                Kind.ITEM -> emit(MdBlock.Item(itemDepth, itemMarker, null, spans), line)
                Kind.QUOTE -> emit(MdBlock.Quote(spans), line)
                else -> emit(MdBlock.Paragraph(spans), line)
            }
        }

        fun endCell() {
            val r = row ?: return
            r.add(takePending())
            pendingLine = -1
        }

        fun endRow() {
            val r = row ?: return
            endCellIfOpen()
            if (r.isNotEmpty()) {
                if (headerRow == null && (rowHasTh || rows.isEmpty())) headerRow = r else rows.add(r)
            }
            row = null
            rowHasTh = false
        }

        var cellOpen = false
        fun endCellIfOpen() { if (cellOpen) { endCell(); cellOpen = false } }

        fun endTable() {
            endRow()
            val header = headerRow ?: rows.firstOrNull()?.also { rows.removeAt(0) }
            if (header != null) {
                val n = header.size
                val body = rows.map { r -> List(n) { c -> r.getOrElse(c) { emptyList() } } }
                emit(MdBlock.Table(header, body), tableLine)
            }
            rows = ArrayList(); headerRow = null; row = null; rowHasTh = false; cellOpen = false
        }

        fun run(): MdDoc {
            var i = 0
            val n = src.length
            while (i < n) {
                val lt = src.indexOf('<', i)
                if (lt < 0) { addText(src.substring(i), i); break }
                if (lt > i) addText(src.substring(i, lt), i)
                // komentar
                if (src.startsWith("<!--", lt)) {
                    val e = src.indexOf("-->", lt + 4)
                    i = if (e < 0) n else e + 3
                    continue
                }
                val c1 = if (lt + 1 < n) src[lt + 1] else ' '
                if (c1 == '!' || c1 == '?') {
                    val e = src.indexOf('>', lt + 1)
                    i = if (e < 0) n else e + 1
                    continue
                }
                if (!(c1.isLetter() || c1 == '/')) { addText("<", lt); i = lt + 1; continue }
                val gt = tagEnd(lt)
                if (gt < 0) { addText(src.substring(lt), lt); break }
                val inner = src.substring(lt + 1, gt)
                i = gt + 1
                val closing = inner.startsWith("/")
                val body = if (closing) inner.substring(1) else inner
                val name = body.takeWhile { it.isLetterOrDigit() || it == ':' || it == '-' }.lowercase()
                if (name.isEmpty()) continue
                if (!closing && name in SKIP) {
                    i = skipElement(name, i)
                    continue
                }
                if (name == "pre" && !closing) {
                    i = readPre(i, lt)
                    continue
                }
                if (closing) close(name) else open(name, body, lt)
            }
            flush()
            if (tableDepth > 0) { tableDepth = 0; endTable() }
            return MdDoc(blocks, starts)
        }

        /** Posisi '>' penutup tag yang memperhatikan tanda kutip pada atribut. */
        fun tagEnd(from: Int): Int {
            var q = 0.toChar()
            var k = from + 1
            while (k < src.length) {
                val c = src[k]
                if (q != 0.toChar()) { if (c == q) q = 0.toChar() }
                else if (c == '"' || c == '\'') q = c
                else if (c == '>') return k
                k++
            }
            return src.indexOf('>', from)
        }

        fun skipElement(name: String, from: Int): Int {
            val m = Regex("(?i)</$name\\s*>").find(src, from)
            return if (m == null) src.length else m.range.last + 1
        }

        fun readPre(from: Int, tagPos: Int): Int {
            val m = Regex("(?i)</pre\\s*>").find(src, from)
            val end = m?.range?.first ?: src.length
            val raw = src.substring(from, end).replace(TAGS, "")
            var text = decode(raw).replace("\r\n", "\n")
            if (text.startsWith("\n")) text = text.substring(1)
            flush()
            if (tableDepth == 0) {
                emit(MdBlock.Code("", text.trimEnd('\n')), lineAt(tagPos))
            } else {
                addText(text, tagPos, preserve = true)
            }
            return if (m == null) src.length else m.range.last + 1
        }

        fun open(name: String, body: String, pos: Int) {
            when (name) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    flush(); kind = Kind.HEADING; headingLevel = name[1] - '0'
                }
                "ul", "ol" -> {
                    flush(); if (lists.size < MAX_LIST_DEPTH) lists.add(ListCtx(name == "ol"))
                }
                "li" -> {
                    flush()
                    kind = Kind.ITEM
                    val ctx = lists.lastOrNull()
                    itemDepth = maxOf(lists.size - 1, 0)
                    itemMarker = if (ctx != null && ctx.ordered) "${ctx.counter++}." else "\u2022"
                }
                "blockquote" -> { flush(); quoteDepth++ }
                "hr" -> { flush(); if (tableDepth == 0) emit(MdBlock.Rule, lineAt(pos)) }
                "br" -> { if (pending.isNotEmpty() || tableDepth > 0) { pending.add(MdSpan("\n", flags(), hrefs.lastOrNull())); endsWithSpace = true } }
                "table" -> {
                    if (tableDepth == 0) { flush(); tableLine = lineAt(pos) }
                    tableDepth++
                }
                "tr" -> if (tableDepth == 1) { endRow(); row = ArrayList() }
                "td", "th" -> if (tableDepth == 1) {
                    if (row == null) row = ArrayList()
                    endCellIfOpen()
                    cellOpen = true
                    if (name == "th") rowHasTh = true
                }
                "a" -> {
                    val href = HREF.find(body)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } } }
                    if (href != null) hrefs.add(decode(href.trim())) else hrefs.add("")
                }
                "strong", "b" -> bold++
                "em", "i", "cite", "dfn" -> italic++
                "code", "kbd", "samp", "tt" -> code++
                "del", "s", "strike" -> strike++
                "img" -> {
                    val alt = ALT.find(body)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } } }
                    val label = decode(alt ?: "").ifBlank { "gambar" }
                    if (pendingLine < 0) pendingLine = lineAt(pos)
                    pending.add(MdSpan("[gambar: $label]", flags() or MdFlag.ITALIC, hrefs.lastOrNull()))
                    endsWithSpace = false
                }
                else -> if (name in BLOCK) {
                    if (tableDepth > 0) { pending.add(MdSpan(" ", flags())); endsWithSpace = true } else { flush(); kind = Kind.PARA }
                }
            }
        }

        fun close(name: String) {
            when (name) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> { flush(); kind = Kind.PARA }
                "ul", "ol" -> { flush(); if (lists.isNotEmpty()) lists.removeAt(lists.size - 1); kind = Kind.PARA }
                "li" -> { flush(); kind = Kind.PARA }
                "blockquote" -> { flush(); if (quoteDepth > 0) quoteDepth--; kind = Kind.PARA }
                "table" -> if (tableDepth > 0) { tableDepth--; if (tableDepth == 0) endTable() }
                "tr" -> if (tableDepth == 1) endRow()
                "td", "th" -> if (tableDepth == 1) endCellIfOpen()
                "a" -> if (hrefs.isNotEmpty()) hrefs.removeAt(hrefs.size - 1)
                "strong", "b" -> if (bold > 0) bold--
                "em", "i", "cite", "dfn" -> if (italic > 0) italic--
                "code", "kbd", "samp", "tt" -> if (code > 0) code--
                "del", "s", "strike" -> if (strike > 0) strike--
                else -> if (name in BLOCK) {
                    if (tableDepth > 0) { pending.add(MdSpan(" ", flags())); endsWithSpace = true } else { flush(); kind = Kind.PARA }
                }
            }
        }
    }
}
