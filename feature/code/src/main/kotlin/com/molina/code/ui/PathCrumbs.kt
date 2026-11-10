package com.molina.code.ui

// [CODE-PATHBAR] Breadcrumb untuk kotak path di header editor, sama gayanya dengan bilah alamat Penjelajah
// ("Internal > Molina-Studio"). Kotlin murni: scripts/code-tests/run_pathcrumbs.sh.
internal object PathCrumbs {
    const val SEP = " \u203A "
    const val INTERNAL = "Internal"
    private val INTERNAL_ROOTS = listOf("/storage/emulated/0", "/sdcard")

    /** [truncated] = ada segmen di depan yang tidak ditampilkan (ditandai "\u2026"). */
    class Tail(val truncated: Boolean, val parts: List<String>)

    /**
     * [keep] segmen terakhir dari [path]. Awalan penyimpanan internal (/storage/emulated/0 atau /sdcard) diganti satu segmen "Internal".
     * Segmen kosong dibuang; jalur akar "/" menjadi satu bagian "/".
     */
    fun tail(path: String, keep: Int = 2): Tail {
        var rest = path
        var head: String? = null
        for (r in INTERNAL_ROOTS) {
            if (rest == r || rest.startsWith("$r/")) { head = INTERNAL; rest = rest.removePrefix(r); break }
        }
        val segs = ArrayList<String>()
        if (head != null) segs.add(head)
        segs.addAll(rest.split('/').filter { it.isNotEmpty() })
        if (segs.isEmpty()) return Tail(false, listOf("/"))
        val n = keep.coerceAtLeast(1)
        return if (segs.size > n) Tail(true, segs.takeLast(n)) else Tail(false, segs)
    }
}
