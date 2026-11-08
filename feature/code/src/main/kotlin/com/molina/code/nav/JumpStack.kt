package com.molina.code.nav

// [CODE-BACK] Tumpukan posisi untuk tombol "Kembali" setelah lompat (definisi, simbol, cari di berkas, tautan keluaran).
// Kotlin murni supaya teruji di JVM. Hanya di memori; tidak ikut disimpan ke draf.

/** Satu posisi di sebuah tab. [line] basis 0, [column] basis 1 (0 = tanpa kolom), sama dengan jumpToLineHighlighted. */
data class JumpPoint(val tabId: Int, val line: Int, val column: Int)

class JumpStack(private val max: Int = DEFAULT_MAX) {
    private val items = ArrayList<JumpPoint>()

    val size: Int get() = items.size

    /**
     * Catat [origin] sebelum melompat ke [dest]. Tidak dicatat bila [origin] null, bila lompatan tidak berpindah baris
     * (tab dan baris sama dengan [dest]), atau bila baris asal sama dengan puncak tumpukan. Yang tertua dibuang
     * saat melebihi batas. True bila benar-benar dicatat.
     */
    fun push(origin: JumpPoint?, dest: JumpPoint? = null): Boolean {
        if (origin == null) return false
        if (dest != null && dest.tabId == origin.tabId && dest.line == origin.line) return false
        val top = items.lastOrNull()
        if (top != null && top.tabId == origin.tabId && top.line == origin.line) return false
        items.add(origin)
        while (items.size > max) items.removeAt(0)
        return true
    }

    /** Ambil posisi terakhir yang tabnya masih hidup; entri milik tab yang sudah ditutup dibuang. Null bila kosong. */
    fun pop(isAlive: (Int) -> Boolean): JumpPoint? {
        while (items.isNotEmpty()) {
            val p = items.removeAt(items.size - 1)
            if (isAlive(p.tabId)) return p
        }
        return null
    }

    /** Posisi yang akan diambil [pop], tanpa mengambilnya; entri tab mati di puncak ikut dibuang. */
    fun peek(isAlive: (Int) -> Boolean): JumpPoint? {
        while (items.isNotEmpty()) {
            val p = items[items.size - 1]
            if (isAlive(p.tabId)) return p
            items.removeAt(items.size - 1)
        }
        return null
    }

    fun dropTab(tabId: Int) { items.removeAll { it.tabId == tabId } }

    fun clear() { items.clear() }

    companion object { const val DEFAULT_MAX = 50 }
}
