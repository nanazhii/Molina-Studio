package com.molina.core.files

import java.util.Locale

/** [FOLDER-KIND] Jenis folder menurut nama (Download, Movies, Music, ...). Dipakai UI untuk memilih varian warna ikon. */
enum class FolderKind { DEFAULT, DOWNLOAD, VIDEO, MUSIC, PICTURES, DOCS }

object FolderKinds {
    private val MAP: Map<String, FolderKind> = HashMap<String, FolderKind>().also { m ->
        fun reg(kind: FolderKind, names: String) { for (n in names.split(' ')) m[n] = kind }
        reg(FolderKind.DOWNLOAD, "download downloads unduhan unduh")
        reg(FolderKind.VIDEO, "movies movie film films video videos tv cinema")
        reg(FolderKind.MUSIC, "music musik audio podcasts podcast")
        reg(FolderKind.PICTURES, "pictures dcim images gambar foto photos screenshots camera")
        reg(FolderKind.DOCS, "documents document dokumen docs")
    }

    /** Pencocokan nama persis (tanpa peka huruf); nama lain = [FolderKind.DEFAULT]. */
    fun of(name: String): FolderKind = MAP[name.lowercase(Locale.ROOT)] ?: FolderKind.DEFAULT
}
