package com.molina.workspace

import java.io.File

/**
 * Aturan murni (tanpa Android) untuk folder kerja: memilih folder awal, naik satu level, uji
 * "berada di dalam", dan riwayat folder terakhir. Dipisah dari [WorkspaceRoot] agar bisa diuji JVM.
 */
object WorkspaceRootPolicy {
    const val STORAGE_ROOT = "/storage/emulated/0"
    const val MAX_RECENT = 5

    /** [fellBack] true bila folder tersimpan hilang/tak terbaca sehingga [dir] adalah cadangan. */
    class Resolved(val dir: File, val fellBack: Boolean)

    /** Folder tersimpan bila masih ada dan terbaca; kosong -> cadangan tanpa peringatan; rusak -> cadangan + peringatan. */
    fun resolve(saved: String?, fallback: File): Resolved {
        val s = saved?.trim().orEmpty()
        if (s.isEmpty()) return Resolved(fallback, false)
        val d = File(s)
        return if (d.isDirectory && d.canRead()) Resolved(d, false) else Resolved(fallback, true)
    }

    /** "/sdcard" adalah alias "/storage/emulated/0"; samakan agar perbandingan path konsisten. */
    fun normalize(f: File): File {
        val p = f.path
        return when {
            p == "/sdcard" -> File(STORAGE_ROOT)
            p.startsWith("/sdcard/") -> File(STORAGE_ROOT + p.substring("/sdcard".length))
            else -> f
        }
    }

    /** Folder induk untuk tombol naik; null bila sudah di /storage/emulated/0 atau berada di luarnya. */
    fun parent(root: File): File? {
        val r = normalize(root)
        if (!r.path.startsWith("$STORAGE_ROOT/")) return null
        return r.parentFile
    }

    /** True bila [file] sama dengan [root] atau berada di bawahnya. */
    fun contains(root: File, file: File): Boolean {
        val r = normalize(root).path
        val p = normalize(file).path
        if (r == "/") return true
        return p == r || p.startsWith("$r/")
    }

    /** Tambah [dir] ke depan riwayat; duplikat (termasuk alias /sdcard) dibuang; maks [max]. */
    fun pushRecent(list: List<String>, dir: String, max: Int = MAX_RECENT): List<String> {
        val d = normalize(File(dir)).path
        val out = ArrayList<String>()
        out.add(d)
        for (e in list) {
            val n = normalize(File(e)).path
            if (n != d && n !in out) out.add(n)
        }
        return out.take(max)
    }

    /** Riwayat disimpan sebagai satu path per baris; path kosong atau berisi baris baru dibuang. */
    fun encode(list: List<String>): String =
        list.filter { it.isNotBlank() && '\n' !in it }.joinToString("\n")

    fun decode(raw: String?): List<String> =
        raw?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
}
