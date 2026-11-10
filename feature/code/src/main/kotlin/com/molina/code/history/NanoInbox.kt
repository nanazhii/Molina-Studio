package com.molina.code.history

import java.io.File

// [NANO-INBOX] Inbox snapshot dari nano. Pembungkus nano di Debian (nano-wrapper.sh) menaruh salinan berkas ke
// <Molina>/.history-inbox/ sebelum dan sesudah nano: `<ms>_<jenis>_<pid>_<n>.snap` (isi) + `.path` (satu baris: jalur
// guest, ditulis terakhir lewat rename sebagai penanda lengkap). Riwayat Code ada di folder privat aplikasi yang tak
// terjangkau PRoot, jadi aplikasi mengimpor inbox ke HistoryStore. Kotlin murni: scripts/code-tests/run_nano_inbox.sh.
object NanoInbox {
    const val DIR_NAME = ".history-inbox"
    private const val MAX_SNAP_BYTES = HistoryStore.MAX_CHARS * 2L
    private const val STALE_MS = 3_600_000L

    /** Jalur guest (/sdcard/... atau /storage/emulated/0/...) -> berkas host di bawah [storageRoot]; null bila tak dikenal/tak aman. */
    fun hostFile(guestPath: String, storageRoot: File): File? {
        val root = storageRoot.path.trimEnd('/')
        val rel = when {
            guestPath.startsWith("/sdcard/") -> guestPath.removePrefix("/sdcard")
            guestPath.startsWith("/storage/emulated/0/") -> guestPath.removePrefix("/storage/emulated/0")
            guestPath.startsWith("$root/") -> guestPath.removePrefix(root)
            else -> return null
        }
        if (rel.split('/').any { it == ".." }) return null
        return File(root + rel)
    }

    /**
     * Impor semua pasangan lengkap di [inbox] ke [store] (urut waktu). Pasangan selalu dihapus sesudah diproses,
     * berhasil atau tidak (best-effort). Yatim .snap/.tmp lebih tua dari satu jam dibuang. Mengembalikan jumlah versi baru.
     * Memblokir: panggil dari thread IO.
     */
    fun import(inbox: File, store: HistoryStore, storageRoot: File, now: Long = System.currentTimeMillis()): Int {
        val metas = inbox.listFiles { f -> f.isFile && f.name.endsWith(".path") } ?: return 0
        var n = 0
        for (m in metas.sortedBy { it.name }) {
            val id = m.name.removeSuffix(".path")
            val snap = File(inbox, "$id.snap")
            try {
                val parts = id.split('_')
                val at = parts.getOrNull(0)?.toLongOrNull()
                val kind = when (parts.getOrNull(1)) {
                    "n" -> HistoryKind.NANO_BEFORE
                    "o" -> HistoryKind.NANO_SAVED
                    else -> null
                }
                val guest = m.readText(Charsets.UTF_8).lineSequence().firstOrNull().orEmpty()
                val host = hostFile(guest, storageRoot)
                if (kind != null && host != null && snap.isFile && snap.length() <= MAX_SNAP_BYTES) {
                    val raw = String(snap.readBytes(), Charsets.UTF_8)
                    if ('\uFFFD' !in raw && '\u0000' !in raw) { // bukan teks UTF-8 valid -> lewati
                        val text = if ('\r' !in raw) raw else raw.replace("\r\n", "\n").replace('\r', '\n')
                        if (store.record(host, text, kind, at) != null) n++
                    }
                }
            } catch (e: Exception) {
                // best-effort
            }
            m.delete()
            snap.delete()
        }
        inbox.listFiles()?.forEach { f ->
            if (f.isFile && (f.name.endsWith(".snap") || f.name.endsWith(".tmp")) && now - f.lastModified() > STALE_MS) f.delete()
        }
        return n
    }
}
