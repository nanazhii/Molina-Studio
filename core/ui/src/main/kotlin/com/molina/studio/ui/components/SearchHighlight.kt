package com.molina.studio.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.molina.studio.ui.MolinaTheme

/**
 * [INFO-HILITE] Sorot potongan yang cocok dengan kata cari. Aturan cocok sama dengan String.looseContains di settings:
 * cocok biasa tanpa peduli huruf besar-kecil; kalau tidak ada, cocok setelah tanda baca dan spasi dibuang
 * (sorotan menutup rentang asli, termasuk tanda baca di dalamnya).
 */
fun highlightMatch(text: String, q: String, color: Color = MolinaTheme.InfoAccent): AnnotatedString {
    val query = q.trim()
    if (query.isEmpty()) return AnnotatedString(text)
    val ranges = ArrayList<IntRange>()
    var from = 0
    while (true) {
        val i = text.indexOf(query, from, ignoreCase = true)
        if (i < 0) break
        ranges.add(i until i + query.length)
        from = i + query.length
    }
    if (ranges.isEmpty()) {
        val cq = query.filter { it.isLetterOrDigit() }.lowercase()
        if (cq.isNotEmpty()) {
            val pos = ArrayList<Int>()
            val sb = StringBuilder()
            text.forEachIndexed { i, c -> if (c.isLetterOrDigit()) { pos.add(i); sb.append(c.lowercaseChar()) } }
            val compact = sb.toString()
            var s = 0
            while (true) {
                val k = compact.indexOf(cq, s)
                if (k < 0) break
                ranges.add(pos[k]..pos[k + cq.length - 1])
                s = k + cq.length
            }
        }
    }
    if (ranges.isEmpty()) return AnnotatedString(text)
    val style = SpanStyle(color = color, fontWeight = FontWeight.Bold)
    return buildAnnotatedString {
        append(text)
        ranges.forEach { addStyle(style, it.first, it.last + 1) }
    }
}
