package com.molina.core.stream

/**
 * [PART-LABEL] Label bagian untuk playlist halaman (video multi-bagian, mis. bilibili ?p=N):
 * "P2" atau "P2 · judul". Murni (tanpa Android) supaya bisa diuji di JVM.
 */
object PartLabel {
    /** Penanda bagian; [index] berbasis nol. */
    fun tag(index: Int): String = "P" + (index + 1)

    /**
     * "P2" bila judul kosong, "P2 · judul" bila ada. Judul yang sudah diawali penanda itu
     * (mis. "P2 Bab Dua") dipakai apa adanya; "P20 ..." tidak dianggap penanda P2.
     */
    fun rowText(index: Int, title: String?): String {
        val t = title?.trim().orEmpty()
        val tag = tag(index)
        if (t.isEmpty()) return tag
        if (t.startsWith(tag, ignoreCase = true) && (t.length == tag.length || !t[tag.length].isDigit())) return t
        return "$tag \u00B7 $t"
    }
}
