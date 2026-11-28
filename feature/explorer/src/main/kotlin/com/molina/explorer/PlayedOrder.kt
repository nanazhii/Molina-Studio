package com.molina.explorer

/**
 * [FEATURE-EXPLORER] Aturan urutan "terakhir diputar dulu" dan normalisasi jalur untuk riwayat putar.
 * Murni JVM (tanpa Compose/Android) supaya bisa diuji. Riwayat: indeks 0 = terbaru.
 */
internal object PlayedOrder {
    /** Peringkat untuk berkas yang belum pernah diputar: di belakang semua yang sudah diputar. */
    const val UNPLAYED = 99

    /** /sdcard/ dan /storage/emulated/0/ menunjuk tempat yang sama; samakan sebelum dibandingkan. */
    fun norm(p: String): String =
        if (p.startsWith("/sdcard/")) "/storage/emulated/0/" + p.removePrefix("/sdcard/") else p

    /**
     * Urutan stabil: folder tetap di atas (urutan asal), lalu berkas menurut peringkat putar di [normRefs]
     * (jalur sudah dinormalisasi lewat [norm]; kemunculan pertama menang), lalu berkas belum diputar (urutan asal).
     */
    fun sort(entries: List<W7Entry>, normRefs: List<String>): List<W7Entry> {
        val rank = HashMap<String, Int>(normRefs.size * 2)
        normRefs.forEachIndexed { i, r -> rank.putIfAbsent(r, i) }
        val rankOf = entries.associateWith { if (it.isDir) 0 else (rank[norm(it.file.path)] ?: UNPLAYED) }
        return entries.sortedWith(compareBy<W7Entry>({ if (it.isDir) 0 else 1 }, { rankOf.getValue(it) }))
    }
}
