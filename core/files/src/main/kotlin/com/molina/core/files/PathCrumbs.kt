package com.molina.core.files

/**
 * [DRAWER-CRUMBS] Breadcrumb jalur untuk kotak path (header editor Code dan drawer Terminal). Satu sumber.
 * Awalan penyimpanan internal (/storage/emulated/0 atau /sdcard) diganti satu segmen "Internal".
 * Kotlin murni: scripts/core-tests/run_path_crumbs.sh.
 */
object PathCrumbs {
    const val SEP = " \u203A "
    const val INTERNAL = "Internal"
    private val INTERNAL_ROOTS = listOf("/storage/emulated/0", "/sdcard")

    /** [label] teks segmen; [path] jalur mutlak folder sampai segmen itu (untuk navigasi). */
    class Crumb(val label: String, val path: String)

    /** [truncated] = ada segmen di depan yang tidak ditampilkan (ditandai "\u2026"). */
    class Tail(val truncated: Boolean, val crumbs: List<Crumb>) {
        val parts: List<String> get() = crumbs.map { it.label }
    }

    /**
     * [keep] segmen terakhir dari [path]. Segmen kosong dibuang; jalur akar "/" menjadi satu segmen "/".
     */
    fun tail(path: String, keep: Int = 2): Tail {
        var rest = path
        var base = ""
        val all = ArrayList<Crumb>()
        for (r in INTERNAL_ROOTS) {
            if (rest == r || rest.startsWith("$r/")) { all.add(Crumb(INTERNAL, r)); base = r; rest = rest.removePrefix(r); break }
        }
        for (seg in rest.split('/').filter { it.isNotEmpty() }) {
            base = "$base/$seg"
            all.add(Crumb(seg, base))
        }
        if (all.isEmpty()) return Tail(false, listOf(Crumb("/", "/")))
        val n = keep.coerceAtLeast(1)
        return if (all.size > n) Tail(true, all.takeLast(n)) else Tail(false, all)
    }
}
