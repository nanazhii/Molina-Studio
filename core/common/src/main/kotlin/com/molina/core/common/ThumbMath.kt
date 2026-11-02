package com.molina.core.common

/** Hitungan murni untuk thumbnail (tanpa Android) supaya bisa diuji di JVM. */
object ThumbMath {
    private const val MAX_SAMPLE = 4096

    /**
     * Faktor `inSampleSize` (pangkat dua) terbesar yang hasil dekodenya masih >= [maxW] x [maxH] di kedua sisi,
     * sehingga gambar besar tidak pernah didekode penuh ke memori. 1 bila gambar sudah kecil atau masukan tidak sah.
     */
    fun sampleSize(w: Int, h: Int, maxW: Int, maxH: Int): Int {
        if (w <= 0 || h <= 0 || maxW <= 0 || maxH <= 0) return 1
        var s = 1
        while (s < MAX_SAMPLE && w / (s * 2) >= maxW && h / (s * 2) >= maxH) s *= 2
        return s
    }
}
