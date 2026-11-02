package com.molina.code.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.molina.code.preview.ErrorLinks
import com.molina.code.preview.OutLine

internal const val LOC_TAG = "loc"

/** [CODE-PALETTE] Keluaran skrip jadi AnnotatedString; stderr merah, lokasi berkas:baris bergaris bawah emas dan bisa diketuk. */
internal fun outputWithLinks(lines: List<OutLine>): AnnotatedString = buildAnnotatedString {
    for (l in lines) {
        val start = length
        append(l.text)
        if (l.error) addStyle(SpanStyle(color = CodeColors.OutputError), start, length)
        if (l.text.indexOf(':') >= 0) {
            for (loc in ErrorLinks.find(l.text)) {
                val s = start + loc.start
                val e = start + loc.end
                addStyle(SpanStyle(color = CodeColors.Gold, textDecoration = TextDecoration.Underline), s, e)
                addStringAnnotation(LOC_TAG, loc.path + "\u0000" + loc.line + "\u0000" + loc.col, s, e)
            }
        }
        append('\n')
    }
}
