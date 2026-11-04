package com.molina.studio.log

/**
 * [DIAG-LOGNAV] Permintaan satu kali agar layar Log terbuka dengan filter sumber tertentu (kunci chip, mis. "cinema").
 * Diisi pemanggil sebelum membuka halaman Log, dibaca dan dihapus oleh LogScreen.
 */
object LogNav {
    @Volatile private var pending: String? = null

    fun request(source: String) { pending = source }

    fun take(): String? = pending.also { pending = null }
}
