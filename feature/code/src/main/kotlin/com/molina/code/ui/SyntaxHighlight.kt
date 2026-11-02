package com.molina.code.ui

import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * Tema highlight bawaan "Obsidian Claude": latar obsidian (hitam), kata kunci terakota Claude,
 * string hijau sage, angka amber, fungsi biru baja, tipe persik, komentar abu hangat miring.
 * Token kustom dipetakan ke slot warna bawaan Rosemoe (string = ATTRIBUTE_VALUE, angka = LITERAL).
 */
internal object ObsidianClaude {
    private const val KEYWORD = 0xFF8FBF7A.toInt()
    private const val TYPE = 0xFF7FB5A8.toInt()
    private const val FUNC = 0xFF7FB4D6.toInt()
    private const val STRING = 0xFFE08A47.toInt()
    private const val NUMBER = 0xFFB7A0E8.toInt()
    private const val COMMENT = 0xFF7C8A78.toInt()
    private const val ANNOT = 0xFFD9A66B.toInt()
    private const val ATTR = 0xFFF2C94C.toInt()
    private const val OPERATOR = 0xFFC8C8C8.toInt()

    fun apply(s: EditorColorScheme) {
        s.setColor(EditorColorScheme.KEYWORD, KEYWORD)
        s.setColor(EditorColorScheme.IDENTIFIER_NAME, TYPE)
        s.setColor(EditorColorScheme.FUNCTION_NAME, FUNC)
        s.setColor(EditorColorScheme.ATTRIBUTE_VALUE, STRING)
        s.setColor(EditorColorScheme.LITERAL, NUMBER)
        s.setColor(EditorColorScheme.COMMENT, COMMENT)
        s.setColor(EditorColorScheme.ANNOTATION, ANNOT)
        s.setColor(EditorColorScheme.HTML_TAG, KEYWORD)
        s.setColor(EditorColorScheme.ATTRIBUTE_NAME, ATTR)
        s.setColor(EditorColorScheme.OPERATOR, OPERATOR)
    }

    private val plain = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)
    private val keyword = TextStyle.makeStyle(EditorColorScheme.KEYWORD, 0, true, false, false)
    private val type = TextStyle.makeStyle(EditorColorScheme.IDENTIFIER_NAME)
    private val func = TextStyle.makeStyle(EditorColorScheme.FUNCTION_NAME)
    private val str = TextStyle.makeStyle(EditorColorScheme.ATTRIBUTE_VALUE)
    private val num = TextStyle.makeStyle(EditorColorScheme.LITERAL)
    private val comment = TextStyle.makeStyle(EditorColorScheme.COMMENT, 0, false, true, false)
    private val annot = TextStyle.makeStyle(EditorColorScheme.ANNOTATION)
    private val tag = TextStyle.makeStyle(EditorColorScheme.HTML_TAG)
    private val attr = TextStyle.makeStyle(EditorColorScheme.ATTRIBUTE_NAME)

    fun styleOf(t: Tok): Long = when (t) {
        Tok.Plain -> plain
        Tok.Keyword -> keyword
        Tok.Type -> type
        Tok.Func -> func
        Tok.Str -> str
        Tok.Num -> num
        Tok.Comment -> comment
        Tok.Annot -> annot
        Tok.Tag -> tag
        Tok.Attr -> attr
        Tok.Heading -> keyword
    }
}

/** Bahasa editor berbasis [SyntaxSpec]: hanya highlight (tanpa auto-complete/format bawaan). */
internal class MolinaLanguage(spec: SyntaxSpec) : EmptyLanguage() {
    private val manager = MolinaAnalyzeManager(spec)
    override fun getAnalyzeManager(): AnalyzeManager = manager
}

private class MolinaAnalyzeManager(private val spec: SyntaxSpec) : SimpleAnalyzeManager<Void>() {
    override fun analyze(text: StringBuilder, delegate: Delegate<Void>): Styles {
        val builder = MappedSpans.Builder()
        val lastLine: Int
        if (text.length > SyntaxLexer.MAX_CHARS) {
            // berkas sangat besar: tanpa highlight agar ketikan tetap lancar
            lastLine = text.count { it == '\n' }
        } else {
            lastLine = SyntaxLexer.lex(text, spec, { delegate.isCancelled }) { line, col, tok ->
                builder.addIfNeeded(line, col, ObsidianClaude.styleOf(tok))
            }
        }
        builder.determine(lastLine)
        val styles = Styles(builder.build())
        if (text.length <= SyntaxLexer.MAX_CHARS) IndentBlocks.build(text, styles) { delegate.isCancelled } // [CODE-STICKY]
        styles.finishBuilding()
        return styles
    }
}
