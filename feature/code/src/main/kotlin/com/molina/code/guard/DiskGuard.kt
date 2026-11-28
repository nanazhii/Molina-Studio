package com.molina.code.guard

/** [CODE-GUARD] Hasil membandingkan isi berkas di disk dengan baseline tab. */
enum class DiskVerdict {
    /** Disk sama dengan baseline (atau sama dengan yang akan ditulis). */
    SAME,

    /** Disk berbeda dari baseline: berkas diubah dari luar editor (skrip, Terminal, aplikasi lain). */
    CHANGED,

    /** Berkas tidak ada lagi. Menyimpan hanya membuatnya kembali, jadi tidak dianggap bentrok. */
    MISSING,

    /** Tidak terbaca sebagai teks (biner, terlalu besar, galat IO). Pengecekan dilewati. */
    UNKNOWN
}

/**
 * Aturan murni (tanpa Android) untuk mendeteksi tab basi: berkas di disk berubah sejak tab dibuka atau
 * terakhir disimpan. Pembanding adalah sidik jari isi (bukan waktu ubah), jadi tahan terhadap jam yang
 * bergeser dan terhadap draf yang dipulihkan. Diuji di JVM (scripts/code-tests/GuardTests.kt).
 */
object DiskGuard {
    /**
     * [baseFp] sidik jari baseline tab, [diskFp] sidik jari isi disk (null bila tak terbaca), [editorFp] sidik
     * jari isi yang akan ditulis bila ada: disk yang sudah sama dengan itu bukan bentrokan, mis. setelah
     * "Jalankan di Terminal" menulis isi editor ke berkas yang sama.
     */
    fun verdict(exists: Boolean, readable: Boolean, baseFp: Long, diskFp: Long?, editorFp: Long? = null): DiskVerdict {
        if (!exists) return DiskVerdict.MISSING
        if (!readable || diskFp == null) return DiskVerdict.UNKNOWN
        if (diskFp == baseFp) return DiskVerdict.SAME
        if (editorFp != null && diskFp == editorFp) return DiskVerdict.SAME
        return DiskVerdict.CHANGED
    }

    /** Tab bersih yang berkasnya berubah dari luar aman dimuat ulang otomatis (tidak ada yang hilang). */
    fun autoReload(v: DiskVerdict, dirty: Boolean): Boolean = v == DiskVerdict.CHANGED && !dirty

    /** Tab dengan perubahan sendiri tidak boleh ditimpa diam-diam: tandai dan minta pilihan saat simpan. */
    fun flagStale(v: DiskVerdict, dirty: Boolean): Boolean = v == DiskVerdict.CHANGED && dirty

    /** Simpan ke berkas yang sama harus berhenti dan bertanya bila disk berubah dari luar. */
    fun blocksSave(v: DiskVerdict): Boolean = v == DiskVerdict.CHANGED
}
