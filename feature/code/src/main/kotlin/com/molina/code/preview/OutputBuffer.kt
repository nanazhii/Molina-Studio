package com.molina.code.preview

/** Satu baris keluaran skrip. [error] true = berasal dari stderr. */
class OutLine(val text: String, val error: Boolean)

/**
 * Penampung keluaran skrip berbatas: maksimal [maxLines] baris (yang tertua dibuang) dan
 * [maxLineChars] karakter per baris. Menangani CR (bilah progres menimpa baris), CRLF, tab,
 * dan membuang urutan ANSI/OSC serta karakter kontrol. Tidak thread-safe: kunci dari pemanggil.
 */
class OutputBuffer(private val maxLines: Int = 2000, private val maxLineChars: Int = 2000) {
    private val lines = ArrayDeque<OutLine>()
    private val cur = StringBuilder()
    private var curError = false
    private var curOpen = false
    private var pendingCr = false
    private var clipped = false

    // 0 normal, 1 setelah ESC, 2 di dalam CSI, 3 di dalam OSC, 4 OSC setelah ESC, 5 lewati satu karakter
    private var esc = 0

    /** Jumlah baris tertua yang sudah dibuang karena melebihi batas. */
    var droppedLines = 0L
        private set

    fun append(chunk: String, error: Boolean) {
        if (curOpen && curError != error) finishLine()
        for (ch in chunk) {
            if (esc != 0) {
                consumeEscape(ch)
                continue
            }
            if (pendingCr) {
                if (ch == '\n') {
                    pendingCr = false
                    finishLine()
                    continue
                }
                pendingCr = false
                cur.setLength(0)
                clipped = false
            }
            when {
                ch == '\n' -> finishLine()
                ch == '\r' -> pendingCr = true
                ch == '\t' -> put("    ", error)
                ch == '\u001B' -> esc = 1
                ch < ' ' || ch == '\u007F' -> Unit
                else -> put(ch.toString(), error)
            }
        }
    }

    private fun consumeEscape(ch: Char) {
        when (esc) {
            1 -> esc = when (ch) {
                '[' -> 2
                ']' -> 3
                '(', ')', '*', '+' -> 5
                else -> 0
            }
            2 -> if (ch in '@'..'~') esc = 0
            3 -> esc = when (ch) {
                '\u0007' -> 0
                '\u001B' -> 4
                else -> 3
            }
            4 -> esc = if (ch == '\\') 0 else 3
            else -> esc = 0
        }
    }

    private fun put(s: String, error: Boolean) {
        curOpen = true
        curError = error
        if (cur.length + s.length > maxLineChars) {
            if (!clipped) {
                clipped = true
                cur.append('\u2026')
            }
            return
        }
        cur.append(s)
    }

    private fun finishLine() {
        lines.addLast(OutLine(cur.toString(), curError))
        while (lines.size > maxLines) {
            lines.removeFirst()
            droppedLines++
        }
        cur.setLength(0)
        curOpen = false
        clipped = false
    }

    /** Salinan baris sekarang, termasuk baris yang belum selesai (tanpa baris baru). */
    fun snapshot(): List<OutLine> {
        val out = ArrayList<OutLine>(lines.size + 1)
        out.addAll(lines)
        if (curOpen) out.add(OutLine(cur.toString(), curError))
        return out
    }

    fun clear() {
        lines.clear()
        cur.setLength(0)
        curOpen = false
        pendingCr = false
        clipped = false
        esc = 0
        droppedLines = 0
    }
}
