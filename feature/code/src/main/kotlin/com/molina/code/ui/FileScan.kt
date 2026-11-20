package com.molina.code.ui

import java.io.File
import java.nio.file.Files

/** Pemindai berkas folder utama untuk Cari di berkas dan Pergi ke simbol: berbatas kedalaman dan jumlah, tanpa tautan simbolik. */
internal object FileScan {
    private val SKIP_DIRS = setOf(".git", "node_modules", "build", ".gradle", ".idea", "__pycache__", ".cache", "zipbak", "zipview")
    private val SKIP_EXT = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "mp3", "mp4", "mkv", "avi", "wav", "flac", "ogg",
        "zip", "7z", "rar", "gz", "xz", "apk", "so", "bin", "dex", "class", "jar", "pdf", "iso"
    )

    fun scan(root: File, maxFiles: Int = 4000, maxDepth: Int = 8): List<File> {
        val out = ArrayList<File>()
        fun walk(d: File, depth: Int) {
            if (out.size >= maxFiles || depth > maxDepth) return
            val kids = d.listFiles() ?: return
            kids.sortBy { it.name.lowercase() }
            for (k in kids) {
                if (out.size >= maxFiles) return
                if (runCatching { Files.isSymbolicLink(k.toPath()) }.getOrDefault(false)) continue
                if (k.isDirectory) {
                    if (k.name !in SKIP_DIRS) walk(k, depth + 1)
                } else if (k.extension.lowercase() !in SKIP_EXT) {
                    out.add(k)
                }
            }
        }
        walk(root, 0)
        return out
    }
}
