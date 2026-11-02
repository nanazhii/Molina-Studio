package com.molina.code.ui

import android.os.Bundle
import com.molina.code.lsp.LspCompletionItem
import com.molina.code.lsp.LspCompletionUi
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.QuickQuoteHandler
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException
import io.github.rosemoe.sora.lang.completion.CompletionHelper
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference

/** [CODE-LSP5] Pintu ke server (pylsp). Dipanggil dari thread latar Sora dan boleh memblokir sampai batas waktu. */
internal fun interface LspCompletionBackend {
    fun complete(text: String, line: Int, column: Int): List<LspCompletionItem>?
}

/** Jeda sebelum permintaan dikirim; ketikan berikutnya membatalkan thread ini, jadi hanya ketikan terakhir yang sampai ke server. */
internal const val COMPLETION_DEBOUNCE_MS = 150L

/** Batas tunggu balasan server. Lewat waktu = jatuh ke completion bawaan bahasa. */
internal const val COMPLETION_TIMEOUT_MS = 1500L

/**
 * [CODE-LSP5] Membungkus bahasa highlight (TextMate atau lexer Molina) dan hanya mengganti completion: saran dari pylsp bila
 * tersedia, selain itu completion bawaan bahasa dasar. Semua anggota lain didelegasikan apa adanya.
 */
internal class LspCompletionLanguage(
    private val base: Language,
    private val backend: () -> LspCompletionBackend?,
) : Language by base {

    @Throws(CompletionCancelledException::class)
    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle,
    ) {
        val b = backend()
        val lineText = if (b != null) content.getLine(position.line) else ""
        if (b == null || !LspCompletionUi.shouldTrigger(lineText, position.column)) {
            base.requireAutoComplete(content, position, publisher, extraArguments)
            return
        }
        try {
            Thread.sleep(COMPLETION_DEBOUNCE_MS)
        } catch (e: InterruptedException) {
            throw CompletionCancelledException()
        }
        publisher.checkCancelled()
        val col = position.column.coerceIn(0, lineText.length)
        var begin = col
        while (begin > 0 && LspCompletionUi.isIdentifierChar(lineText[begin - 1])) begin--
        val identifierPrefix = col - begin
        val items = runCatching { b.complete(content.toString(), position.line, position.column) }.getOrNull()
        publisher.checkCancelled()
        if (items.isNullOrEmpty()) {
            base.requireAutoComplete(content, position, publisher, extraArguments)
            return
        }
        val out = ArrayList<CompletionItem>(items.size)
        for (it in items) {
            val plen = LspCompletionUi.prefixLength(it, position.line, position.column, identifierPrefix)
            val kind = LspCompletionUi.kindName(it.kind)?.let { n -> runCatching { CompletionItemKind.valueOf(n) }.getOrNull() }
            val item = SimpleCompletionItem(it.label, it.detail, plen, it.insertText)
            if (kind != null) item.kind(kind)
            out.add(item)
        }
        publisher.addItems(out)
    }

    // Anggota default Java: ditulis eksplisit supaya pasti diteruskan ke bahasa dasar (TextMate mengganti getIndentAdvance 5 parameter).
    override fun getIndentAdvance(
        content: ContentReference, line: Int, column: Int, spaceCountOnLine: Int, tabCountOnLine: Int,
    ): Int = base.getIndentAdvance(content, line, column, spaceCountOnLine, tabCountOnLine)

    override fun getQuickQuoteHandler(): QuickQuoteHandler? = base.quickQuoteHandler
}

/** Bungkus [lang] dengan completion LSP bila ada [backend]; bahasa null (tanpa highlight) tetap null. */
internal fun withLspCompletion(lang: Language?, backend: (() -> LspCompletionBackend?)?): Language? =
    if (lang == null || backend == null) lang else LspCompletionLanguage(lang, backend)
