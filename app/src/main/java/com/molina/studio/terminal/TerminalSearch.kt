package com.molina.studio.terminal

/**
 * Pencarian teks per baris layar Terminal (riwayat + layar). JVM murni agar bisa diuji di scripts/app-tests.
 * Baris = baris layar, bukan baris logis, agar indeksnya sama dengan TerminalView.mTopRow dan lompatan tepat sasaran.
 * Pencocokan literal, tanpa membedakan huruf besar-kecil, terbaru di atas, satu hasil per baris.
 */
internal object TerminalSearch {
    const val MAX_QUERY = 200
    const val MAX_HITS = 200
    private const val LEFT = 80
    private const val RIGHT = 160

    /**
     * [row] = baris layar eksternal (negatif = riwayat, 0 = baris layar teratas), sama dengan indeks baris TerminalView.
     * [snippet] = cuplikan; [start]/[end] = rentang kecocokan di cuplikan (indeks char);
     * [col1]/[col2] = rentang kolom layar [col1, col2) untuk penyorotan, memperhitungkan karakter lebar.
     */
    data class Hit(val row: Int, val snippet: String, val start: Int, val end: Int, val col1: Int, val col2: Int)

    /** rows[i] adalah teks baris layar (firstRow + i). Hasil dimulai dari baris paling bawah (terbaru). */
    fun findRows(rows: List<String>, firstRow: Int, query: String, max: Int = MAX_HITS): List<Hit> {
        val q = query.trim().take(MAX_QUERY)
        if (q.isEmpty() || rows.isEmpty() || max <= 0) return emptyList()
        val out = ArrayList<Hit>()
        for (i in rows.indices.reversed()) {
            val raw = rows[i].trimEnd()
            val at = raw.indexOf(q, ignoreCase = true)
            if (at < 0) continue
            val end = at + q.length
            val c1 = displayWidth(raw.substring(0, at))
            val c2 = c1 + displayWidth(raw.substring(at, end))
            out.add(window(firstRow + i, raw, at, end, c1, c2))
            if (out.size >= max) break
        }
        return out
    }

    /** Lebar tampilan (kolom) sebuah teks; karakter lebar dihitung 2, kombinasi 0. Pendekatan, tidak sepenuhnya wcwidth. */
    fun displayWidth(s: String): Int {
        var w = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            w += cpWidth(cp)
        }
        return w
    }

    private fun cpWidth(cp: Int): Int = when {
        cp in 0x0300..0x036F || cp in 0x200B..0x200F || cp in 0xFE00..0xFE0F -> 0
        isWide(cp) -> 2
        else -> 1
    }

    private fun isWide(cp: Int): Boolean =
        cp in 0x1100..0x115F || cp in 0x2E80..0x303E || cp in 0x3041..0x33FF ||
            cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xA000..0xA4CF ||
            cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE4F ||
            cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 || cp in 0x1F300..0x1F64F ||
            cp in 0x1F900..0x1F9FF || cp in 0x20000..0x3FFFD

    private fun window(row: Int, raw: String, s: Int, e: Int, c1: Int, c2: Int): Hit {
        if (raw.length <= LEFT + RIGHT) return Hit(row, raw, s, e, c1, c2)
        val from = maxOf(0, s - LEFT)
        val to = minOf(raw.length, e + RIGHT)
        val prefix = if (from > 0) "\u2026" else ""
        val suffix = if (to < raw.length) "\u2026" else ""
        val shift = prefix.length - from
        return Hit(row, prefix + raw.substring(from, to) + suffix, s + shift, e + shift, c1, c2)
    }
}
