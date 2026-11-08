package com.molina.vault.crypto

// MOLINA-H1
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Penilaian umur patch keamanan Android (Build.VERSION.SECURITY_PATCH, format YYYY-MM-DD). Murni JVM. */
object SecurityPatch {
    const val STALE_DAYS = 365L

    /** Umur patch dalam hari; null bila kosong, tidak valid, atau tanggalnya di masa depan (mencurigakan). */
    fun ageDays(patch: String?, today: LocalDate): Long? {
        if (patch.isNullOrBlank()) return null
        val d = try { LocalDate.parse(patch.trim()) } catch (e: Exception) { return null }
        val age = ChronoUnit.DAYS.between(d, today)
        return if (age < -1L) null else maxOf(age, 0L)
    }

    fun isStale(ageDays: Long?): Boolean = ageDays != null && ageDays > STALE_DAYS
}
