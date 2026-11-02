package com.molina.code.ui

import com.molina.code.check.CheckFix
import com.molina.code.check.CheckRange
import com.molina.code.check.Checkers
import com.molina.code.check.Diagnostic
import com.molina.code.check.ExternalCheck
import com.molina.code.check.Severity
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer

private const val MAX_CHECK_CHARS = 300_000

/**
 * [CODE-CHECK] Hasil pemeriksaan terakhir untuk satu tab (potret: tidak ikut berubah saat teks diedit).
 * [text] = teks yang diperiksa, hanya disimpan bila ada diagnostik dengan perbaikan ([CODE-CHECK-FIX]) agar
 * perbaikan bisa menolak diri sendiri bila teks editor sudah berubah.
 */
internal class ProblemList(val tabId: Int, val title: String, val label: String, val items: List<Diagnostic>, val text: String? = null)

/**
 * [CODE-CHECK] Periksa isi tab aktif sekali jalan (py: pyflakes, sh: shellcheck, json/xml: bawaan). Hasil jadi garis
 * bawah di editor (Sora DiagnosticsContainer; ketuk garis bawah untuk pesan) dan daftar masalah. Teks tidak pernah diubah.
 */
internal fun CodeSession.checkActive() {
    val tab = active ?: return
    checkTab(tab, quiet = false, openList = true)
}

/**
 * [CODE-CHECK-SAVE] Dipanggil setelah berkas tersimpan bila saklar "Periksa saat simpan" aktif. Senyap: tanpa toast
 * "memeriksa", tanpa daftar yang menutupi editor; hanya garis bawah dan satu toast ringkas bila ada masalah.
 * Tidak pernah menahan atau mengubah simpan.
 */
internal fun CodeSession.checkAfterSave(tab: EditorTab) {
    if (tab.readOnly || tab !in tabs) return
    checkTab(tab, quiet = true, openList = false)
}

/**
 * Inti periksa. [quiet] = bungkam semua toast kecuali ringkasan temuan dan (sekali per sesi) kegagalan pemeriksa.
 * [openList] = buka dialog Daftar masalah bila ada temuan.
 */
internal fun CodeSession.checkTab(tab: EditorTab, quiet: Boolean, openList: Boolean) {
    fun say(msg: String) {
        if (!quiet) toast(msg)
    }
    if (tab.loading) return say("Editor sedang memuat")
    if (tab.editor == null) return say("Editor belum siap")
    if (checker.busy) return say("Masih memeriksa\u2026")
    val text = tab.currentTextOrNull(MAX_CHECK_CHARS) ?: return say("Berkas terlalu besar untuk diperiksa")
    if (text.isBlank()) {
        clearProblems(tab)
        return say("Tidak ada teks untuk diperiksa")
    }
    val name = tab.file?.name ?: tab.title
    val plan = Checkers.of(name, tab.firstLineOrNull())
        ?: return say("Belum ada pemeriksa untuk jenis ini (${Checkers.SUPPORTED_SUMMARY})")
    if (plan is ExternalCheck) say("Memeriksa dengan ${plan.label}\u2026")
    checker.start(plan, name, text, tab.file?.parentFile?.path) { outcome ->
        when (outcome) {
            is CheckOutcome.Cancelled -> Unit
            is CheckOutcome.Failed -> {
                // Saat simpan, kegagalan (mis. shellcheck belum terpasang) cukup diberitahukan sekali per sesi.
                if (!quiet) toast(outcome.message)
                else if (!checkFailNoted) {
                    checkFailNoted = true
                    toast(outcome.message)
                }
            }
            is CheckOutcome.Ok -> applyProblems(tab, text, plan.label, outcome.items, quiet, openList)
        }
    }
}

/** Hapus garis bawah dan daftar masalah milik [tab]. */
internal fun CodeSession.clearProblems(tab: EditorTab) {
    try {
        tab.editor?.diagnostics = null
    } catch (e: Exception) {
        // editor sudah dilepas
    }
    if (problems?.tabId == tab.id) {
        problems = null
        problemsOpen = false
    }
}

/**
 * [CODE-CHECK-FIX] Terapkan perbaikan [d] dari [list] ke editor sebagai SATU langkah undo (Urungkan membatalkan).
 * Tidak menyimpan ke disk. Ditolak bila teks editor sudah berbeda dari teks yang diperiksa, atau bila perbaikan
 * tidak aman (lihat [CheckFix.edits]). Sesudahnya garis bawah lama dibuang dan pemeriksaan diulang senyap; bila
 * masih ada temuan, Daftar masalah dibuka lagi.
 */
internal fun CodeSession.applyQuickFix(list: ProblemList, d: Diagnostic) {
    val tab = tabs.firstOrNull { it.id == list.tabId } ?: return toast("Tab sudah ditutup")
    val ed = tab.editor
    if (ed == null || tab.loading) return toast("Editor belum siap")
    if (tab.readOnly) return toast("Tab ini hanya-baca")
    val base = list.text ?: return toast("Perbaikan tidak tersedia, jalankan Periksa kode lagi")
    val content = ed.text
    if (content.length != base.length || content.toString() != base) {
        return toast("Teks berubah sejak diperiksa. Jalankan Periksa kode lagi")
    }
    val fixed = CheckFix.fixed(base, d) ?: return toast("Perbaikan tidak bisa diterapkan dengan aman")
    try {
        val patch = CheckFix.minimalPatch(base, fixed)
        val line = ed.cursor.leftLine
        val col = ed.cursor.leftColumn
        content.replace(patch.start, patch.end, patch.text)
        val l = line.coerceIn(0, ed.text.lineCount - 1)
        ed.setSelection(l, col.coerceAtMost(ed.text.getColumnCount(l)))
    } catch (e: Exception) {
        return toast("Gagal menerapkan perbaikan")
    }
    toast("Diperbaiki" + (d.code?.let { ": $it" } ?: "") + ". Simpan untuk menerapkan, Urungkan untuk membatalkan")
    clearProblems(tab)
    checkTab(tab, quiet = true, openList = true)
}

private fun CodeSession.applyProblems(
    tab: EditorTab, sent: String, label: String, items: List<Diagnostic>, quiet: Boolean, openList: Boolean,
) {
    val ed = tab.editor
    if (ed == null || tab !in tabs) {
        if (!quiet) toast("Tab sudah ditutup, hasil periksa dibuang")
        return
    }
    val content = ed.text
    // Teks diedit saat memeriksa: posisi tidak lagi cocok, jangan menandai tempat yang salah.
    if (content.length != sent.length || content.toString() != sent) {
        if (!quiet) toast("Teks berubah saat memeriksa, hasil dibuang")
        return
    }
    if (items.isEmpty()) {
        clearProblems(tab)
        if (!quiet) toast("Tidak ada masalah ($label)")
        return
    }
    try {
        val container = diagnosticsContainer(sent, items)
        ed.diagnostics = container
    } catch (e: Exception) {
        toast("Gagal menandai masalah di editor")
    }
    problems = ProblemList(tab.id, tab.file?.name ?: tab.title, label, items, if (items.any { it.fix.isNotEmpty() }) sent else null)
    val errors = items.count { it.severity == Severity.ERROR }
    val warns = items.count { it.severity == Severity.WARNING }
    val fixable = items.count { it.fix.isNotEmpty() }
    toast("${items.size} masalah ($label): $errors galat, $warns peringatan" + if (fixable > 0) ", $fixable bisa diperbaiki" else "")
    problemsOpen = openList
}

/** [CODE-LSP3] Garis bawah Sora dari [items] terhadap teks [sent]; dipakai Periksa kode dan diagnostik langsung. */
internal fun diagnosticsContainer(sent: String, items: List<Diagnostic>): DiagnosticsContainer {
    val container = DiagnosticsContainer()
    var id = 0L
    val regions = CheckRange.spans(sent, items).map { sp ->
        val d = sp.d
        val sev: Short = when (d.severity) {
            Severity.ERROR -> DiagnosticRegion.SEVERITY_ERROR
            Severity.WARNING -> DiagnosticRegion.SEVERITY_WARNING
            Severity.INFO -> DiagnosticRegion.SEVERITY_TYPO
        }
        DiagnosticRegion(sp.start, sp.end, sev, id++, DiagnosticDetail(d.message, (d.code?.let { "$it \u00B7 " } ?: "") + d.source))
    }
    container.addDiagnostics(regions)
    return container
}
