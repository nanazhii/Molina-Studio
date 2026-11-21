package com.molina.core.fileops

/** [CORE-FILEOPS] Penanda kegagalan yang memang diharapkan (mis. sandi diminta atau salah): jadi pesan ke pengguna, bukan galat yang perlu dicatat di log. */
interface ExpectedFailure

/**
 * [CORE-FILEOPS] Aturan hasil operasi berkas yang dipakai bersama (teks gagal, perlu-dicatat-atau-tidak, jejak singkat).
 * Murni JVM supaya bisa diuji tanpa Android.
 */
object FileOpOutcome {
    /** Pesan IOException yang dilempar saat pengguna menekan Hentikan. */
    const val CANCELLED = "Dibatalkan"
    const val NO_WRITE_PERMISSION = "Izin tulis penyimpanan belum diberikan. Aktifkan izin Penyimpanan di Setelan Android."

    fun failureText(t: Throwable): String = t.message ?: ("Operasi gagal (" + t.javaClass.simpleName + ")")

    /** Dicatat ke log hanya bila galat tak terduga: bukan [ExpectedFailure] dan bukan pembatalan oleh pengguna. */
    fun shouldLog(t: Throwable): Boolean = t !is ExpectedFailure && t.message != CANCELLED

    /** Jenis error + 8 baris tumpukan pertama, untuk Settings > Log (membantu mendiagnosis kegagalan di perangkat). */
    fun trace(t: Throwable): String =
        t.javaClass.name + ": " + (t.message ?: "-") + "\n" + t.stackTrace.take(8).joinToString("\n") { "  at " + it }
}
