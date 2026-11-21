package com.molina.explorer

/**
 * [FEATURE-EXPLORER] Riwayat Mundur/Maju antar folder Penjelajah (maks. [cap] entri per arah).
 * Murni JVM, tanpa Compose: pemanggil yang memberi tahu UI bila riwayat berubah.
 * Jalur disimpan sebagai String; [isDir] dipakai untuk melewati folder yang sudah hilang.
 */
internal class NavHistory(private val cap: Int = 50) {
    private val backStack = ArrayList<String>()
    private val fwdStack = ArrayList<String>()

    val canBack: Boolean get() = backStack.isNotEmpty()
    val canForward: Boolean get() = fwdStack.isNotEmpty()

    /** Pindah normal dari [from] ke [to]: [from] masuk riwayat Mundur dan riwayat Maju dikosongkan. Folder sama = tidak mencatat. */
    fun visit(from: String, to: String) {
        if (to == from) return
        backStack.add(from)
        if (backStack.size > cap) backStack.removeAt(0)
        fwdStack.clear()
    }

    /** Folder tujuan Mundur (dan [current] masuk riwayat Maju), atau null bila tidak ada folder yang masih ada. */
    fun back(current: String, isDir: (String) -> Boolean): String? = step(backStack, fwdStack, current, isDir)

    /** Folder tujuan Maju (dan [current] masuk riwayat Mundur), atau null bila tidak ada folder yang masih ada. */
    fun forward(current: String, isDir: (String) -> Boolean): String? = step(fwdStack, backStack, current, isDir)

    private fun step(from: ArrayList<String>, to: ArrayList<String>, current: String, isDir: (String) -> Boolean): String? {
        while (from.isNotEmpty()) {
            val p = from.removeAt(from.size - 1)
            if (isDir(p)) { to.add(current); return p }
        }
        return null
    }
}
