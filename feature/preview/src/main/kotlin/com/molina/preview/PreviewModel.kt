package com.molina.preview

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.molina.preview.engine.HtmlPreview
import com.molina.preview.engine.JsonPretty
import com.molina.preview.engine.JsonPreview
import com.molina.preview.engine.JsonTok
import com.molina.preview.engine.MdBlock
import com.molina.preview.engine.MdDoc
import com.molina.preview.engine.MdFlag
import com.molina.preview.engine.MdSpan
import com.molina.preview.engine.Markdown
import com.molina.preview.engine.PreviewKind

// [NANO-PREVIEW] Model + pembangun isi pratinjau (disalin dari editor Code, tanpa keterkaitan CodeSession).

/** Penanda anotasi tautan di AnnotatedString hasil render. */
internal const val LINK_TAG = "URL"

/** Blok Markdown yang sudah jadi AnnotatedString; dibangun di thread latar, hanya digambar di UI. */
internal sealed class MdUi {
    class Heading(val level: Int, val text: AnnotatedString) : MdUi()
    class Para(val text: AnnotatedString) : MdUi()
    class Code(val lang: String, val text: String) : MdUi()
    class Quote(val text: AnnotatedString) : MdUi()
    class Item(val depth: Int, val marker: String, val checked: Boolean?, val text: AnnotatedString) : MdUi()
    object Rule : MdUi()
    class Table(val header: List<AnnotatedString>, val rows: List<List<AnnotatedString>>) : MdUi()
}

internal sealed class PreviewContent {
    object Empty : PreviewContent()
    class Message(val text: String) : PreviewContent()
    class Markdown(val blocks: List<MdUi>, val startLines: List<Int>) : PreviewContent()

    /** HTML mentah untuk WebView (hanya bila opsi WebView di Settings menyala). */
    class Html(val source: String) : PreviewContent()
    class Json(val text: AnnotatedString, val lineCount: Int) : PreviewContent()
    class JsonError(val message: String, val line: Int, val column: Int, val snippet: String) : PreviewContent()
}

/** Pembangun isi pratinjau. Tidak menyentuh state Compose, aman di thread latar. */
internal object PreviewRender {
    fun build(kind: PreviewKind, text: String): PreviewContent = when (kind) {
        PreviewKind.MARKDOWN -> markdown(Markdown.parseWithLines(text))
        PreviewKind.HTML -> markdown(HtmlPreview.parse(text))
        PreviewKind.JSON -> json(text)
        else -> PreviewContent.Message("Tidak ada pratinjau untuk tipe berkas ini.")
    }

    private fun markdown(doc: MdDoc): PreviewContent {
        return PreviewContent.Markdown(doc.blocks.map { b -> mdUi(b) }, doc.startLines)
    }

    private fun mdUi(b: MdBlock): MdUi =
        when (b) {
            is MdBlock.Heading -> MdUi.Heading(b.level, styled(b.spans))
            is MdBlock.Paragraph -> MdUi.Para(styled(b.spans))
            is MdBlock.Code -> MdUi.Code(b.lang, b.text)
            is MdBlock.Quote -> MdUi.Quote(styled(b.spans))
            is MdBlock.Item -> MdUi.Item(b.depth, b.marker, b.checked, styled(b.spans))
            MdBlock.Rule -> MdUi.Rule
            is MdBlock.Table -> MdUi.Table(b.header.map { styled(it) }, b.rows.map { r -> r.map { styled(it) } })
        }

    private fun styled(spans: List<MdSpan>): AnnotatedString = buildAnnotatedString {
        for (s in spans) {
            val start = length
            append(s.text)
            val bold = s.flags and MdFlag.BOLD != 0
            val italic = s.flags and MdFlag.ITALIC != 0
            val code = s.flags and MdFlag.CODE != 0
            val strike = s.flags and MdFlag.STRIKE != 0
            val link = s.flags and MdFlag.LINK != 0
            val deco: TextDecoration? = when {
                strike && link -> TextDecoration.combine(listOf(TextDecoration.LineThrough, TextDecoration.Underline))
                strike -> TextDecoration.LineThrough
                link -> TextDecoration.Underline
                else -> null
            }
            val color = when {
                link -> PreviewColors.PreviewLink
                code -> PreviewColors.PreviewInlineCode
                else -> Color.Unspecified
            }
            addStyle(
                SpanStyle(
                    color = color,
                    fontWeight = if (bold) FontWeight.Bold else null,
                    fontStyle = if (italic) FontStyle.Italic else null,
                    fontFamily = if (code) FontFamily.Monospace else null,
                    background = if (code) PreviewColors.PreviewInlineCodeBg else Color.Unspecified,
                    textDecoration = deco
                ),
                start, length
            )
            val href = s.href
            if (href != null) addStringAnnotation(LINK_TAG, href, start, length)
        }
    }

    private fun json(text: String): PreviewContent = when (val r = JsonPreview.render(text)) {
        is JsonPretty.Ok -> {
            val a = buildAnnotatedString {
                append(r.text)
                for (run in r.runs) {
                    val c = when (run.kind) {
                        JsonTok.KEY -> PreviewColors.JsonKey
                        JsonTok.STRING -> PreviewColors.JsonString
                        JsonTok.NUMBER -> PreviewColors.JsonNumber
                        JsonTok.LITERAL -> PreviewColors.JsonLiteral
                        JsonTok.PUNCT -> PreviewColors.JsonPunct
                        JsonTok.SPACE -> null
                    }
                    if (c != null) addStyle(SpanStyle(color = c), run.start, run.end)
                }
            }
            PreviewContent.Json(a, r.lineCount)
        }
        is JsonPretty.Invalid -> PreviewContent.JsonError(r.message, r.line, r.column, r.snippet)
        is JsonPretty.TooLarge -> PreviewContent.Message(
            "JSON terlalu besar untuk pratinjau (${r.chars / 1000} rb karakter, batas ${r.limit / 1000} rb)."
        )
    }
}
