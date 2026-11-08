package com.molina.code.run

/**
 * [CODE-RUNTIMEOUT] Pilihan batas waktu skrip di panel Keluaran. Kotlin murni agar bisa diuji di JVM
 * (scripts/code-tests/run_runtimeout.sh). Nilai selalu salah satu dari [OPTIONS_MS]; nilai lain dibulatkan
 * ke yang terdekat, dan nilai rusak kembali ke [DEFAULT_MS]. Tidak ada "tanpa batas": skrip yang menggantung
 * tetap berhenti sendiri, paling lama 1 jam.
 */
object RunTimeout {
    const val DEFAULT_MS = 60_000L
    val OPTIONS_MS: List<Long> = listOf(60_000L, 300_000L, 900_000L, 1_800_000L, 3_600_000L)

    fun coerce(ms: Long): Long =
        if (ms <= 0L) DEFAULT_MS else OPTIONS_MS.minByOrNull { kotlin.math.abs(it - ms) } ?: DEFAULT_MS

    fun parse(raw: String?): Long {
        val v = raw?.trim()?.toLongOrNull() ?: return DEFAULT_MS
        return coerce(v)
    }

    /** Pilihan berikutnya, kembali ke yang terpendek setelah yang terpanjang. */
    fun next(ms: Long): Long {
        val i = OPTIONS_MS.indexOf(coerce(ms))
        return OPTIONS_MS[(i + 1) % OPTIONS_MS.size]
    }

    /** 60000 -> "60 dtk", 300000 -> "5 mnt", 3600000 -> "1 jam". */
    fun short(ms: Long): String = when {
        ms < 120_000L -> "${ms / 1000} dtk"
        ms % 3_600_000L == 0L -> "${ms / 3_600_000L} jam"
        else -> "${ms / 60_000L} mnt"
    }
}
