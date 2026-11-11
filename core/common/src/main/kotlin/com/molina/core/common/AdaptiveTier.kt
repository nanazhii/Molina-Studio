package com.molina.core.common

/** [AUTO-NET-2] Tingkat penurunan kualitas per video (hanya mode AUTO), dipicu macet berulang. Kedaluwarsa 10 menit; video lain mulai dari 0. */
object AdaptiveTier {
    const val MAX = 3
    private const val TTL_MS = 600_000L
    private var page: String = ""
    private var offset: Int = 0
    private var at: Long = 0L

    @Synchronized
    fun offsetFor(pageUrl: String): Int =
        if (pageUrl == page && System.currentTimeMillis() - at < TTL_MS) offset else 0

    /** Turunkan satu tier untuk [pageUrl]; false bila sudah di dasar. */
    @Synchronized
    fun downshift(pageUrl: String): Boolean {
        val now = System.currentTimeMillis()
        if (pageUrl != page || now - at >= TTL_MS) { page = pageUrl; offset = 0 }
        if (offset >= MAX) return false
        offset++
        at = now
        return true
    }
}
