package com.molina.studio.code

import com.molina.code.ui.CodeSession
import com.molina.studio.ShortcutNav

/**
 * Jembatan Terminal -> Code. Teks disisipkan di posisi kursor tab Code aktif, lalu layar pindah ke tab Code.
 * Mengembalikan pesan galat, atau null bila berhasil.
 */
internal object CodeInbox {
    private const val MAX_CHARS = 200_000

    /** Dipasang MainActivity selama sesi Code hidup. */
    var session: CodeSession? = null

    fun deliver(text: String): String? {
        val s = session ?: return "Editor Code belum siap"
        if (text.isBlank()) return "Tidak ada keluaran Terminal untuk dikirim"
        if (text.length > MAX_CHARS) return "Keluaran terlalu panjang, pilih 200 baris terakhir"
        val err = s.insertAtCursor(text.trimEnd() + "\n")
        if (err != null) return err
        ShortcutNav.tab = ShortcutNav.TAB_CODE
        return null
    }
}
