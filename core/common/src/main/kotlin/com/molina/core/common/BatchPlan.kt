package com.molina.core.common

/**
 * Rencana kirim banyak URL ke tab Unduhan: hanya http(s), tanpa duplikat, opsional melewati video yang sudah
 * diunduh, dibatasi [DEFAULT_MAX]. Murni JVM (dapat diuji). [DEFAULT_MAX] sama dengan DownloadCommand.MAX_URLS;
 * pemanggil di :app meneruskan DownloadCommand.MAX_URLS secara eksplisit.
 */
object BatchPlan {
    const val DEFAULT_MAX = 50

    class Result(val urls: List<String>, val skippedDone: Int, val overLimit: Int) {
        val empty: Boolean get() = urls.isEmpty()
    }

    fun plan(
        urls: List<String>,
        isDone: (String) -> Boolean = { false },
        skipDone: Boolean = false,
        max: Int = DEFAULT_MAX,
    ): Result {
        val valid = urls.map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
        val fresh = if (skipDone) valid.filter { !isDone(it) } else valid
        val kept = fresh.take(max.coerceAtLeast(0))
        return Result(kept, valid.size - fresh.size, fresh.size - kept.size)
    }

    /** Teks toast singkat untuk hasil [plan]. */
    fun message(r: Result, max: Int = DEFAULT_MAX): String {
        if (r.empty) {
            return if (r.skippedDone > 0) "Semua sudah diunduh (${r.skippedDone} dilewati)." else "Tidak ada tautan yang bisa diunduh."
        }
        val base = if (r.urls.size == 1) "Dikirim ke Unduhan" else "${r.urls.size} tautan dikirim ke Unduhan"
        return base +
            (if (r.overLimit > 0) " (maks $max)" else "") +
            (if (r.skippedDone > 0) " \u00B7 ${r.skippedDone} dilewati (sudah diunduh)" else "")
    }
}
