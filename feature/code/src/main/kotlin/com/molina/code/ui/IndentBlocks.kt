package com.molina.code.ui

import io.github.rosemoe.sora.lang.styling.Styles

/**
 * [CODE-STICKY] Blok kode berbasis indentasi untuk sticky scroll Sora. Tidak bergantung bahasa:
 * baris dengan indentasi lebih dalam setelahnya menjadi isi blok; blok berakhir di baris
 * tak-kosong terakhir sebelum indentasi kembali. Tab dihitung 4 kolom. Satu lintasan, O(baris).
 */
internal object IndentBlocks {
    private const val TAB = 4

    private class Open(val line: Int, val indent: Int, val col: Int)

    fun build(text: CharSequence, styles: Styles, cancelled: () -> Boolean) {
        val stack = ArrayList<Open>()
        var lastNonBlank = -1
        var lastNonBlankLen = 0
        var line = 0
        var i = 0
        val n = text.length

        fun emit(o: Open, endLine: Int, endCol: Int) {
            if (endLine <= o.line) return
            val b = styles.obtainNewBlock()
            b.startLine = o.line
            b.startColumn = o.col
            b.endLine = endLine
            b.endColumn = endCol
            b.toBottomOfEndLine = true
            styles.addCodeBlock(b)
        }

        while (i <= n) {
            if ((line and 0xFF) == 0 && cancelled()) return
            var j = i
            var indent = 0
            var col = 0
            var counting = true
            while (j < n && text[j] != '\n') {
                val c = text[j]
                if (counting && c != '\r') {
                    if (c == ' ') indent++ else if (c == '\t') indent += TAB else counting = false
                    if (counting) col++
                }
                j++
            }
            val lineLen = j - i
            val blank = counting || text.subSequence(i, j).isBlank()
            if (!blank) {
                while (stack.isNotEmpty() && stack.last().indent >= indent) {
                    emit(stack.removeAt(stack.size - 1), lastNonBlank, lastNonBlankLen)
                }
                stack.add(Open(line, indent, col))
                lastNonBlank = line
                lastNonBlankLen = lineLen
            }
            line++
            i = j + 1
        }
        while (stack.isNotEmpty()) emit(stack.removeAt(stack.size - 1), lastNonBlank, lastNonBlankLen)
    }
}
