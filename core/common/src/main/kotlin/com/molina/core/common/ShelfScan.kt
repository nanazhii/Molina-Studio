package com.molina.core.common

import java.io.File

/**
 * Pemindai folder Unduhan untuk tab "Unduhan" di rak film: video terbaru lebih dulu.
 * Kedalaman dibatasi (playlist yt-dlp membuat subfolder) dan jumlah entri yang ditelusuri dibatasi
 * supaya folder besar tidak menahan thread. Murni JVM; tidak melempar.
 */
object ShelfScan {
    val VIDEO_EXTS = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "ts", "m2ts", "mts", "flv", "wmv", "mpg", "mpeg", "ogv", "vob", "rmvb"
    )
    const val MAX_DEPTH = 2
    const val MAX_VISITED = 800
    const val DEFAULT_MAX = 12

    fun scan(root: File, max: Int = DEFAULT_MAX): List<ShelfCard.Entry> {
        if (max <= 0) return emptyList()
        val found = ArrayList<File>()
        var visited = 0
        fun walk(dir: File, depth: Int) {
            val kids = try { dir.listFiles() } catch (e: SecurityException) { null } ?: return
            for (k in kids) {
                if (++visited > MAX_VISITED) return
                if (k.name.startsWith(".")) continue
                if (k.isDirectory) {
                    if (depth < MAX_DEPTH) walk(k, depth + 1)
                } else if (k.isFile && k.extension.lowercase() in VIDEO_EXTS) {
                    found.add(k)
                }
            }
        }
        walk(root, 1)
        return found.sortedByDescending { it.lastModified() }.take(max)
            .map { ShelfCard.Entry(it.path, it.nameWithoutExtension, true) }
    }
}
