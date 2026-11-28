package com.molina.code.ui

import com.molina.code.format.ExternalPlan
import com.molina.code.format.FragmentIndent
import com.molina.code.format.Formatters
import io.github.rosemoe.sora.widget.CodeEditor

private const val MAX_FORMAT_CHARS = 500_000
private const val SAVE_TIMEOUT_MS = 15_000L

/** Bagian teks yang diformat: [start, end) di teks penuh. [send] = yang dikirim ke formatter (sudah tanpa jorokan bersama). */
private class FormatTarget(val start: Int, val end: Int, val original: String, val indent: String, val send: String)

/**
 * [CODE-FORMAT] Format seluruh isi tab aktif. Pemformat dipilih dari nama berkas (atau shebang):
 * JSON dan XML bawaan; Python, shell, JS/TS/CSS/HTML/Markdown/YAML lewat formatter di Debian.
 * Hasil dipasang sebagai SATU langkah undo. Bila gagal, teks tidak berubah dan pesan galat tampil di toast.
 */
internal fun CodeSession.formatActive() {
    val tab = active ?: return
    runFormat(tab, selectionOnly = false, forSave = false, onEnd = null)
}

/** [CODE-FORMAT] Format hanya baris-baris yang tersentuh seleksi (diperluas ke baris utuh; jorokan bersama dijaga). */
internal fun CodeSession.formatSelection() {
    val tab = active ?: return
    runFormat(tab, selectionOnly = true, forSave = false, onEnd = null)
}

/**
 * [CODE-FORMAT] Format sebelum simpan. TIDAK PERNAH menahan simpan: [onEnd] selalu dipanggil tepat sekali di
 * main thread, dengan catatan galat (null bila sukses atau tak ada pemformat untuk jenis ini). Tanpa toast
 * sukses; pesan tunggal "Tersimpan" yang ditampilkan penyimpan.
 */
internal fun CodeSession.formatForSave(tab: EditorTab, onEnd: (String?) -> Unit) {
    runFormat(tab, selectionOnly = false, forSave = true, onEnd = onEnd)
}

private fun CodeSession.runFormat(tab: EditorTab, selectionOnly: Boolean, forSave: Boolean, onEnd: ((String?) -> Unit)?) {
    fun skip(msg: String) {
        if (!forSave) toast(msg)
        onEnd?.invoke(null)
    }
    val ed = tab.editor
    if (tab.loading) return skip("Editor sedang memuat")
    if (tab.readOnly) return skip("Tab ini hanya-baca")
    if (ed == null) return skip("Editor belum siap")
    if (format.busy) return skip("Masih memformat\u2026")
    val all = tab.currentTextOrNull(MAX_FORMAT_CHARS) ?: return skip("Berkas terlalu besar untuk diformat")
    if (all.isBlank()) return skip("Tidak ada teks untuk diformat")
    val name = tab.file?.name ?: tab.title
    val plan = Formatters.of(name, tab.firstLineOrNull())
        ?: return skip("Belum ada pemformat untuk jenis ini (${Formatters.SUPPORTED_SUMMARY})")
    val target = if (selectionOnly) {
        selectionTarget(ed, all) ?: return skip("Pilih teks yang mau diformat dulu")
    } else {
        FormatTarget(0, all.length, all, "", all)
    }
    if (plan is ExternalPlan) toast(if (forSave) "Memformat sebelum menyimpan\u2026" else "Memformat dengan ${plan.label}\u2026")
    val cursorLine = ed.cursor.leftLine
    val cursorCol = ed.cursor.leftColumn
    format.start(
        plan, name, target.send, tab.file?.parentFile?.path,
        if (forSave) SAVE_TIMEOUT_MS else FormatController.TIMEOUT_MS,
    ) { outcome ->
        when (outcome) {
            is FormatOutcome.Cancelled -> onEnd?.invoke(null)
            is FormatOutcome.Failed -> {
                if (!forSave) toast(outcome.message)
                onEnd?.invoke(if (forSave) outcome.message else null)
            }
            is FormatOutcome.Ok -> {
                val note = applyFormat(tab, all, target, selectionOnly, plan.label, outcome.text, cursorLine, cursorCol, forSave)
                onEnd?.invoke(note)
            }
        }
    }
}

/** Mengembalikan catatan galat (hanya relevan untuk simpan) atau null. Toast sukses hanya untuk format manual. */
private fun CodeSession.applyFormat(
    tab: EditorTab, all: String, target: FormatTarget, selectionOnly: Boolean, label: String,
    formatted: String, cursorLine: Int, cursorCol: Int, forSave: Boolean,
): String? {
    fun note(msg: String): String? {
        if (!forSave) toast(msg)
        return if (forSave) msg else null
    }
    val ed = tab.editor
    if (ed == null || tab !in tabs) return note("Tab sudah ditutup, format dibatalkan")
    val content = ed.text
    // Teks diedit saat formatter jalan: jangan menimpa ketikan pengguna.
    if (content.length != all.length || content.toString() != all) return note("Teks berubah saat memformat, dibatalkan")
    val newText = if (selectionOnly) {
        FragmentIndent.reindent(formatted.trimEnd('\n'), target.indent)
    } else {
        formatted
    }
    if (newText == target.original) {
        if (!forSave) toast("Sudah rapi ($label)")
        return null
    }
    try {
        content.replace(target.start, target.end, newText)
        if (selectionOnly) {
            val a = ed.text.indexer.getCharPosition(target.start)
            val b = ed.text.indexer.getCharPosition(target.start + newText.length)
            ed.setSelectionRegion(a.line, a.column, b.line, b.column)
        } else {
            val l = cursorLine.coerceIn(0, ed.text.lineCount - 1)
            ed.setSelection(l, cursorCol.coerceAtMost(ed.text.getColumnCount(l)))
        }
        if (!forSave) toast(if (selectionOnly) "Seleksi diformat dengan $label" else "Diformat dengan $label")
        return null
    } catch (e: Exception) {
        return note("Gagal memasang hasil format")
    }
}

/** Seleksi diperluas ke baris utuh; seleksi yang berakhir tepat di awal baris tidak menyeret baris itu. Null bila tak ada seleksi/kosong. */
private fun selectionTarget(ed: CodeEditor, all: String): FormatTarget? {
    val c = ed.cursor
    if (!c.isSelected) return null
    val left = c.left
    val right = c.right
    if (left < 0 || right > all.length || left >= right) return null
    val start = all.lastIndexOf('\n', left - 1) + 1
    val from = if (all[right - 1] == '\n') right - 1 else right
    val end = all.indexOf('\n', from).let { if (it < 0) all.length else it }
    if (end <= start) return null
    val frag = all.substring(start, end)
    if (frag.isBlank()) return null
    val indent = FragmentIndent.common(frag)
    return FormatTarget(start, end, frag, indent, FragmentIndent.dedent(frag, indent) + "\n")
}
