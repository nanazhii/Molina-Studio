package com.molina.core.common

/**
 * [MEM-MPV] Perkiraan RAM komponen yang berjalan DI DALAM proses aplikasi (mis. libmpv lewat JNI), yang
 * tidak punya PID sendiri. VmRSS proses mencakup seluruh aplikasi, jadi nilainya dikurangi RSS dasar yang
 * terakhir terlihat saat komponen tidak aktif. Ini perkiraan, bukan pengukuran: alokator bisa menahan memori
 * setelah pemutaran berhenti, sehingga dasar yang tinggi membuat selisih berikutnya lebih kecil dari aslinya.
 */
class RssDelta {
    private var baselineKb: Long = -1L

    /** 0 saat tidak aktif; selisih (tidak negatif) saat aktif; -1 bila belum ada dasar atau [rssKb] tak terbaca. */
    @Synchronized
    fun estimate(active: Boolean, rssKb: Long): Long {
        if (rssKb < 0L) return -1L
        if (!active) {
            baselineKb = rssKb
            return 0L
        }
        return if (baselineKb < 0L) -1L else (rssKb - baselineKb).coerceAtLeast(0L)
    }

    companion object {
        /** Jumlah dua perkiraan; nilai -1 (tidak diketahui) diperlakukan 0, hasil -1 hanya bila keduanya tidak diketahui. */
        fun combine(a: Long, b: Long): Long =
            if (a < 0L && b < 0L) -1L else a.coerceAtLeast(0L) + b.coerceAtLeast(0L)
    }
}
