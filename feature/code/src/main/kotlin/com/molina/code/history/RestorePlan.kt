package com.molina.code.history

// [EXT-DIFF3] Logika murni (tanpa Android): rencana pemulihan satu berkas dari butir feed "dari luar".
// M = kembali ke versi sebelum "Sesudah diubah luar" butir itu; D = dibuat ulang dari versi terbaru. Uji: scripts/code-tests/run_restoreplan.sh.
object RestorePlan {
    /** Toleransi antara waktu butir feed dan waktu versi "Sesudah diubah luar" yang direkam sesaat sebelumnya. */
    const val TIME_SLACK_MS = 5_000L

    class Plan(val target: HistoryVersion?, val error: String?)

    /** [EXT-DIFF6] Keterangan pratinjau pemulihan menurut jenis perubahan (M = kembali ke versi sebelumnya, D = dibuat ulang). */
    fun previewCaption(kind: Char): String = when (kind) {
        'M' -> "Merah dihapus, hijau ditambah: isi sekarang menjadi versi sebelum perubahan luar."
        'D' -> "Berkas dibuat ulang dari versi terakhir yang diketahui; semua baris ditambah."
        else -> ""
    }

    /** Tombol Pulihkan hanya untuk butir "dari luar": M yang punya hitungan +/- dan D yang punya isi lama. */
    fun canOffer(external: Boolean, kind: Char, added: Int, removed: Int): Boolean =
        external && ((kind == 'M' && added + removed > 0) || (kind == 'D' && removed > 0))

    /**
     * [list] = versi berkas (terbaru dulu), [diskText] = isi disk sekarang (LF) atau null bila berkas tidak ada,
     * [read] = pembaca isi versi. Mengembalikan versi tujuan, atau pesan galat bila tidak aman memulihkan.
     */
    fun forChange(list: List<HistoryVersion>, kind: Char, entryTime: Long, diskText: String?, read: (HistoryVersion) -> String?): Plan =
        when (kind) {
            'M' -> forModified(list, entryTime, diskText, read)
            'D' -> forDeleted(list, diskText)
            else -> Plan(null, "Jenis perubahan ini tidak bisa dipulihkan")
        }

    private fun forModified(list: List<HistoryVersion>, entryTime: Long, diskText: String?, read: (HistoryVersion) -> String?): Plan {
        val after = list.firstOrNull { it.kind == HistoryKind.EXTERNAL_AFTER && it.time <= entryTime + TIME_SLACK_MS }
            ?: return Plan(null, "Tidak ada versi sebelumnya untuk dipulihkan")
        val before = VersionCompare.previous(list, after) ?: return Plan(null, "Tidak ada versi sebelumnya untuk dipulihkan")
        if (diskText == null) return Plan(null, "Berkas sudah dihapus")
        val afterText = read(after)
        if (afterText == null || afterText != diskText) return Plan(null, "Berkas sudah berubah lagi sejak itu. Pulihkan lewat Riwayat.")
        return Plan(before, null)
    }

    private fun forDeleted(list: List<HistoryVersion>, diskText: String?): Plan {
        if (diskText != null) return Plan(null, "Berkas sudah ada lagi")
        val latest = list.firstOrNull() ?: return Plan(null, "Tidak ada riwayat berkas ini")
        return Plan(latest, null)
    }
}
