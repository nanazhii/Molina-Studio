package com.molina.code.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.molina.code.preview.HtmlPreview
import com.molina.code.preview.JsonPretty
import com.molina.code.preview.JsonPreview
import com.molina.code.preview.JsonTok
import com.molina.code.preview.MdBlock
import com.molina.code.preview.MdDoc
import com.molina.code.preview.MdFlag
import com.molina.code.preview.MdSpan
import com.molina.code.preview.Markdown
import com.molina.code.preview.OutLine
import com.molina.code.preview.PreviewKind
import com.molina.code.preview.PreviewKinds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Isi panel bawah: pratinjau tab aktif, atau keluaran skrip. */
internal enum class PaneMode { Preview, Output }

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

/** Status panel pratinjau/keluaran milik satu sesi Code. */
internal class PreviewController {
    var open by mutableStateOf(false)
    var mode by mutableStateOf(PaneMode.Preview)
    var content by mutableStateOf<PreviewContent>(PreviewContent.Empty)
        private set
    var label by mutableStateOf("")
        private set

    /** Opsi Settings: HTML dirender WebView, bukan renderer native. Diisi :app. */
    var htmlWebView by mutableStateOf(false)

    /** Baris kursor editor saat Tinjauan dibuka (basis 0); dipakai sekali untuk menggulir ke bagian yang sama. -1 = tidak ada. */
    var anchorLine = -1

    /** Ambil lalu hapus [anchorLine], supaya pembaruan langsung berikutnya tidak menggulir lagi. */
    fun takeAnchor(): Int {
        val a = anchorLine
        anchorLine = -1
        return a
    }

    /** Id tab yang isinya sedang tampil; -1 bila belum ada. Bukan state (tidak memicu recomposition). */
    var shownTabId = -1
        private set

    /** Kosongkan isi supaya pratinjau lama tidak berkedip saat dibuka ulang. */
    fun invalidate() {
        content = PreviewContent.Empty
        shownTabId = -1
    }

    /**
     * Bangun ulang pratinjau dari isi [tab] sekarang. Teks dibaca di thread pemanggil (main),
     * parsing dan pembuatan AnnotatedString jalan di Dispatchers.Default.
     */
    suspend fun refresh(tab: EditorTab) {
        shownTabId = tab.id
        val kind = tab.previewKind()
        label = when (kind) {
            PreviewKind.MARKDOWN -> "Markdown"
            PreviewKind.JSON -> "JSON"
            PreviewKind.HTML -> "HTML"
            else -> ""
        }
        if (kind != PreviewKind.MARKDOWN && kind != PreviewKind.JSON && kind != PreviewKind.HTML) {
            content = PreviewContent.Message("Tidak ada pratinjau untuk tipe berkas ini.")
            return
        }
        val text = tab.currentTextOrNull(MAX_PREVIEW_CHARS)
        if (text == null) {
            content = PreviewContent.Message("Berkas terlalu besar untuk pratinjau.")
            return
        }
        if (kind == PreviewKind.HTML && htmlWebView) {
            content = PreviewContent.Html(text)
            return
        }
        content = withContext(Dispatchers.Default) { PreviewRender.build(kind, text) }
    }

    companion object {
        const val MAX_PREVIEW_CHARS = 500_000
        const val DEBOUNCE_MS = 300L
    }
}

/** Jenis keluaran tombol Play untuk tab ini. Nama berkas + baris pertama (shebang). */
internal fun EditorTab.previewKind(): PreviewKind = PreviewKinds.of(file?.name ?: title, firstLineOrNull()) // [CODE-TEMPLATE] tab scratch memakai ekstensi judul

internal fun EditorTab.firstLineOrNull(): String? {
    val ed = editor
    val src: CharSequence = if (ed != null) ed.text else (pendingText ?: return null)
    val n = minOf(src.length, 200)
    return src.subSequence(0, n).toString().substringBefore('\n').trimEnd('\r')
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
                link -> CodeColors.PreviewLink
                code -> CodeColors.PreviewInlineCode
                else -> Color.Unspecified
            }
            addStyle(
                SpanStyle(
                    color = color,
                    fontWeight = if (bold) FontWeight.Bold else null,
                    fontStyle = if (italic) FontStyle.Italic else null,
                    fontFamily = if (code) FontFamily.Monospace else null,
                    background = if (code) CodeColors.PreviewInlineCodeBg else Color.Unspecified,
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
                        JsonTok.KEY -> CodeColors.JsonKey
                        JsonTok.STRING -> CodeColors.JsonString
                        JsonTok.NUMBER -> CodeColors.JsonNumber
                        JsonTok.LITERAL -> CodeColors.JsonLiteral
                        JsonTok.PUNCT -> CodeColors.JsonPunct
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

    /** Keluaran skrip jadi satu AnnotatedString; baris stderr berwarna merah. */
    fun output(lines: List<OutLine>): AnnotatedString = buildAnnotatedString {
        for (l in lines) {
            val start = length
            append(l.text)
            if (l.error) addStyle(SpanStyle(color = CodeColors.OutputError), start, length)
            append('\n')
        }
    }
}
