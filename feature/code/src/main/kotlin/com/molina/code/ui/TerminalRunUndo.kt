package com.molina.code.ui

import java.io.File
import java.io.IOException

/**
 * [CODE-PYRUN] Cadangan satu kali untuk "Jalankan di Terminal" mode berkas (hanya di memori).
 * [previous] null = berkas belum ada sebelum ditulis (Urungkan menghapusnya); [written] = isi yang kita tulis,
 * dipakai agar Urungkan tidak menimpa perubahan yang dibuat pengguna sesudahnya.
 */
internal class TerminalRunUndo(val file: File, val previous: String?, val written: String)

internal object TerminalRunEngine {
    const val MAX_BACKUP_BYTES = 1_000_000L

    /** Ambil isi lama [target] sebelum ditimpa. Lempar IOException bila tidak aman dicadangkan. Panggil dari thread IO. */
    fun begin(target: File, written: String): TerminalRunUndo {
        if (!target.exists()) return TerminalRunUndo(target, null, written)
        if (!target.isFile) throw IOException(target.name + " bukan berkas")
        if (target.length() > MAX_BACKUP_BYTES) throw IOException("Berkas lama terlalu besar untuk dicadangkan (maks. 1 MB)")
        return TerminalRunUndo(target, target.readText(Charsets.UTF_8), written)
    }

    /** Pulihkan berkas ke keadaan sebelum dijalankan. Mengembalikan pesan untuk Toast. Panggil dari thread IO. */
    fun undo(u: TerminalRunUndo): String {
        val f = u.file
        return try {
            if (!f.exists()) {
                "Berkas " + f.name + " sudah tidak ada"
            } else if (f.length() > MAX_BACKUP_BYTES * 2 || f.readText(Charsets.UTF_8) != u.written) {
                "Dilewati: " + f.name + " sudah berubah setelah dijalankan"
            } else if (u.previous == null) {
                if (f.delete()) "Dihapus: " + f.name + " (dibuat oleh Jalankan di Terminal)" else "Gagal menghapus " + f.name
            } else {
                FileStore.writeUtf8Atomic(f, u.previous)
                "Dipulihkan: " + f.name
            }
        } catch (e: Exception) {
            "Gagal mengurungkan: " + (e.message ?: f.name)
        }
    }
}

/** Menu "Urungkan jalankan Terminal": pulihkan berkas yang ditulis Jalankan di Terminal terakhir. Editor tidak diubah. */
internal fun CodeSession.undoLastTerminalRun() {
    val u = lastTerminalRun ?: return
    lastTerminalRun = null
    runIo {
        val msg = TerminalRunEngine.undo(u)
        mainHandler.post {
            tabFor(u.file)?.let { if (!it.dirty && !it.loading) doReload(it) }
            dir.refresh()
            toast(msg)
        }
    }
}
