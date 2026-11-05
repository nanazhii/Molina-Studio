package com.molina.code.history

// [CODE-HISTORY4] Logika murni (tanpa Android): versi pembanding untuk tampilan "Perubahan versi ini".

object VersionCompare {
    /**
     * Versi sebelum [v] (satu langkah lebih tua) pada [list] yang urut terbaru-dulu, seperti hasil [HistoryStore.list].
     * Null bila [v] adalah versi tertua atau tidak ada di [list] (pemanggil lalu membandingkan dengan isi sekarang).
     */
    fun previous(list: List<HistoryVersion>, v: HistoryVersion): HistoryVersion? {
        val i = list.indexOfFirst { it.name == v.name }
        return if (i < 0) null else list.getOrNull(i + 1)
    }
}
