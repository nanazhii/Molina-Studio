package com.molina.code.lsp

// [CODE-LSP4] Completion dan definition (pylsp + Jedi). Kotlin murni. Semua posisi di berkas ini memakai
// konvensi LSP/Sora: baris dan kolom basis 0, kolom dalam UTF-16 (sama dengan String Kotlin).

/** Penggantian teks yang diminta server; rentang basis 0, akhir eksklusif. */
data class LspTextEdit(val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int, val newText: String)

data class LspCompletionItem(
    val label: String,
    /** Teks yang disisipkan (textEdit.newText > insertText > label). Selalu teks polos: snippet tidak diumumkan ke server. */
    val insertText: String,
    val kind: Int?,
    val detail: String?,
    val sortKey: String,
    val edit: LspTextEdit?,
)

/** Lokasi hasil definition (basis 0). [uri] milik guest Debian; terjemahkan lewat LspPathMap. */
data class LspLocation(val uri: String, val line: Int, val column: Int)

object LspCompletions {
    const val MAX_ITEMS = 200
    private const val MAX_TEXT = 300

    /** Terima `CompletionItem[]`, `CompletionList`, atau null. Item tanpa label dibuang; hasil diurutkan stabil menurut sortText. */
    fun convert(result: Any?): List<LspCompletionItem> {
        val raw: List<*> = when (result) {
            is List<*> -> result
            is Map<*, *> -> result["items"] as? List<*> ?: return emptyList()
            else -> return emptyList()
        }
        val out = ArrayList<LspCompletionItem>(minOf(raw.size, MAX_ITEMS))
        for (o in raw) {
            val m = o as? Map<*, *> ?: continue
            val label = (m["label"] as? String)?.take(MAX_TEXT)?.ifBlank { null } ?: continue
            val edit = edit(m["textEdit"])
            val insert = (edit?.newText ?: m["insertText"] as? String ?: label).take(MAX_TEXT * 4)
            out.add(
                LspCompletionItem(
                    label = label,
                    insertText = insert,
                    kind = (m["kind"] as? Double)?.toInt(),
                    detail = (m["detail"] as? String)?.take(MAX_TEXT)?.ifBlank { null },
                    sortKey = (m["sortText"] as? String) ?: label,
                    edit = edit,
                ),
            )
        }
        out.sortBy { it.sortKey } // sortBy stabil
        return if (out.size > MAX_ITEMS) ArrayList(out.subList(0, MAX_ITEMS)) else out
    }

    /** `TextEdit` atau `InsertReplaceEdit` (dipakai bagian `replace`). */
    private fun edit(o: Any?): LspTextEdit? {
        val m = o as? Map<*, *> ?: return null
        val text = m["newText"] as? String ?: return null
        val range = (m["range"] ?: m["replace"]) as? Map<*, *> ?: return null
        val s = pos(range["start"]) ?: return null
        val e = pos(range["end"]) ?: return null
        return LspTextEdit(s.first, s.second, e.first, e.second, text.take(MAX_TEXT * 4))
    }

    internal fun pos(o: Any?): Pair<Int, Int>? {
        val m = o as? Map<*, *> ?: return null
        val l = (m["line"] as? Double)?.toInt() ?: return null
        val c = (m["character"] as? Double)?.toInt() ?: return null
        return Pair(l.coerceAtLeast(0), c.coerceAtLeast(0))
    }
}

object LspDefinitions {
    const val MAX_ITEMS = 50

    /** Terima `Location`, `Location[]`, `LocationLink[]`, atau null. Duplikat (uri+posisi sama) dibuang. */
    fun convert(result: Any?): List<LspLocation> {
        val raw: List<*> = when (result) {
            is List<*> -> result
            is Map<*, *> -> listOf(result)
            else -> return emptyList()
        }
        val out = LinkedHashSet<LspLocation>()
        for (o in raw) {
            if (out.size >= MAX_ITEMS) break
            val m = o as? Map<*, *> ?: continue
            val uri = (m["uri"] ?: m["targetUri"]) as? String ?: continue
            val range = (m["range"] ?: m["targetSelectionRange"] ?: m["targetRange"]) as? Map<*, *> ?: continue
            val (l, c) = LspCompletions.pos(range["start"]) ?: continue
            out.add(LspLocation(uri, l, c))
        }
        return out.toList()
    }
}

/** [CODE-LSP5] Aturan murni untuk popup completion (tanpa Android, supaya teruji di JVM). */
object LspCompletionUi {
    /**
     * Banyak karakter sebelum kursor yang digantikan saat item dipilih. Memakai textEdit server bila rentangnya satu baris
     * dengan kursor dan berakhir tepat di kursor; selain itu memakai panjang awalan pengenal ([identifierPrefix]).
     */
    fun prefixLength(item: LspCompletionItem, line: Int, column: Int, identifierPrefix: Int): Int {
        val e = item.edit
        val fallback = identifierPrefix.coerceIn(0, column.coerceAtLeast(0))
        if (e == null || e.startLine != line || e.endLine != line || e.endColumn != column || e.startColumn > column) return fallback
        return (column - e.startColumn).coerceIn(0, column.coerceAtLeast(0))
    }

    private val KIND_NAMES = arrayOf(
        "Text", "Method", "Function", "Constructor", "Field", "Variable", "Class", "Interface", "Module", "Property",
        "Unit", "Value", "Enum", "Keyword", "Snippet", "Color", "File", "Reference", "Folder", "EnumMember",
        "Constant", "Struct", "Event", "Operator", "TypeParameter",
    )

    /** Nama CompletionItemKind Sora untuk kode kind LSP (1..25); null bila tidak dikenal. */
    fun kindName(kind: Int?): String? = if (kind == null || kind < 1 || kind > KIND_NAMES.size) null else KIND_NAMES[kind - 1]

    /** Awalan pengenal Python/Java: huruf, angka, garis bawah. */
    fun isIdentifierChar(ch: Char): Boolean = ch == '_' || Character.isLetterOrDigit(ch)

    /** Apakah posisi kursor layak memicu completion: ada awalan pengenal, atau karakter tepat sebelum awalan adalah titik. */
    fun shouldTrigger(lineText: String, column: Int): Boolean {
        val col = column.coerceIn(0, lineText.length)
        var b = col
        while (b > 0 && isIdentifierChar(lineText[b - 1])) b--
        return b < col || (b > 0 && lineText[b - 1] == '.')
    }
}
