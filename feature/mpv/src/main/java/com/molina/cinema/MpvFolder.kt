package com.molina.cinema

import java.io.File

/**
 * Pintu publik untuk :app: daftar media dalam folder. Memakai ekstensi dan pengurutan yang sama
 * dengan Utils.folderPlaylist, sehingga hasilnya konsisten dengan playlist folder di MPVActivity.
 */
object MpvFolder {
    private val SKIP = setOf("cue", "m3u", "m3u8", "pls", "strm", "vlc")
    private const val MAX_ENTRIES = 1000 // sama dengan batas playlist memory:// di MPVActivity
    private const val MAX_DEPTH = 6

    private fun isMedia(f: File): Boolean =
        f.isFile && !f.name.startsWith(".") &&
            f.extension.lowercase().let { it in Utils.MEDIA_EXTENSIONS && it !in SKIP }

    /** Path berkas media pertama di [dir] (tidak rekursif), atau null bila tidak ada. */
    fun firstMediaIn(dir: File): String? {
        if (!dir.isDirectory) return null
        val files = dir.listFiles { f -> isMedia(f) } ?: return null
        return files.sortedWith { a, b -> Utils.naturalCompare(a.name, b.name) }.firstOrNull()?.path
    }

    /**
     * Semua media di [dir] termasuk subfolder: berkas folder itu dulu, lalu tiap subfolder berurutan alami.
     * Dibatasi 1000 berkas dan kedalaman 6; folder tersembunyi dan putaran symlink dilewati. Panggil di thread IO.
     */
    fun mediaTree(dir: File): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        fun walk(d: File, depth: Int) {
            if (out.size >= MAX_ENTRIES || depth > MAX_DEPTH) return
            val canon = runCatching { d.canonicalPath }.getOrNull() ?: return
            if (!seen.add(canon)) return
            val children = d.listFiles() ?: return
            val sorted = children.filter { !it.name.startsWith(".") }
                .sortedWith { a, b -> Utils.naturalCompare(a.name, b.name) }
            for (f in sorted) {
                if (out.size >= MAX_ENTRIES) return
                if (isMedia(f)) out.add(f.path)
            }
            for (f in sorted) {
                if (out.size >= MAX_ENTRIES) return
                if (f.isDirectory) walk(f, depth + 1)
            }
        }
        walk(dir, 0)
        return out
    }

    /** URI playlist memory:// untuk [paths]; null bila kosong. Nama berkas bermasalah (baris baru) dilewati. */
    fun memoryPlaylist(paths: List<String>): String? {
        val ok = paths.filter { p -> p.none { c -> c == '\n' || c == '\r' || c == '\u0000' } }
        if (ok.isEmpty()) return null
        return runCatching { Utils.makeMemoryPlaylist(ok) }.getOrNull()
    }
}
