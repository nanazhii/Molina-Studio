package com.molina.core.common

/**
 * Keputusan menyalin skrip bawaan (assets) ke folder Scripts pengguna. Murni, tanpa I/O.
 * Hash berupa SHA-256 heksa. Prinsip: berkas yang diedit pengguna TIDAK pernah ditimpa.
 */
object BundledScriptPolicy {
    enum class Action {
        /** Berkas belum ada: salin lalu catat hash bawaan. */
        INSTALL,
        /** Berkas masih salinan bawaan lama yang tidak diedit: timpa dengan versi baru lalu catat. */
        UPDATE,
        /** Berkas sudah sama dengan bawaan: cukup pastikan hash tercatat. */
        RECORD,
        /** Berkas diedit atau asalnya tidak diketahui: tulis versi baru berdampingan (nama.new), jangan timpa. */
        STAGE_NEW,
        /** Tidak ada yang perlu dilakukan. */
        KEEP,
    }

    /**
     * @param targetHash hash berkas di Scripts, null bila belum ada
     * @param recordedHash hash bawaan terakhir yang kita salin ke sana, null bila tidak pernah tercatat
     * @param stagedHash hash bawaan yang sudah pernah ditulis sebagai nama.new (supaya tidak dibuat ulang tiap buka aplikasi)
     * @param bundledHash hash skrip di assets saat ini
     */
    fun decide(targetHash: String?, recordedHash: String?, stagedHash: String?, bundledHash: String): Action = when {
        targetHash == null -> Action.INSTALL
        targetHash == bundledHash -> if (recordedHash == bundledHash) Action.KEEP else Action.RECORD
        recordedHash != null && targetHash == recordedHash -> Action.UPDATE
        stagedHash == bundledHash -> Action.KEEP
        else -> Action.STAGE_NEW
    }
}
