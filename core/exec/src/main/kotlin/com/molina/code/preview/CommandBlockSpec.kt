package com.molina.code.preview

/**
 * [CODE-CMDBLOCK] Spesifikasi tunggal blok perintah/skrip Molina (monospace, tepi 1dp, sudut 5dp). Murni angka
 * dan logika potong teks, tanpa Compose: dipakai dialog Code (CodeColors) dan layar :app (W7D) dengan palet
 * masing-masing, sehingga ukuran dan perilaku pratinjau perintah seragam di semua aksi Terminal.
 */
object CommandBlockSpec {
    const val FONT_SP = 11.5f
    const val LINE_HEIGHT_SP = 16f
    const val CORNER_DP = 5f
    const val PADDING_DP = 10f
    const val BORDER_DP = 1f
    const val PREVIEW_MAX_LINES = 4
    const val PREVIEW_MAX_CHARS = 120

    /**
     * Potong [text] untuk pratinjau: maksimal [maxLines] baris, baris lebih dari [maxChars] karakter dipotong
     * dengan "...". Bila baris tersisa, ditutup "..." (atau "... (+N baris lagi)" bila [withCount]).
     * Pratinjau tidak boleh menjadi jaminan isi aman: [withCount] membuat sisa yang tidak tampil terlihat.
     */
    fun clip(text: String, maxLines: Int = PREVIEW_MAX_LINES, maxChars: Int = PREVIEW_MAX_CHARS, withCount: Boolean = false): String {
        val lines = text.trimEnd('\n', '\r').lines()
        val shown = lines.take(maxLines).map { if (it.length > maxChars) it.take(maxChars) + "..." else it }
        if (lines.size <= maxLines) return shown.joinToString("\n")
        val rest = lines.size - maxLines
        return shown.joinToString("\n") + "\n..." + (if (withCount) " (+$rest baris lagi)" else "")
    }
}
