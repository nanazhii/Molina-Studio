package com.molina.core.files

import java.io.File
import java.util.Locale

/** [FILE-CONTRACT] Urutan isi folder: folder dulu, lalu berkas, nama tanpa peka huruf. */
object DirOrder {
    fun <T> foldersFirst(isDir: (T) -> Boolean, name: (T) -> String): Comparator<T> =
        compareBy<T>({ !isDir(it) }, { name(it).lowercase(Locale.ROOT) })

    /** Isi [dir] terurut; kosong bila tidak terbaca. isDirectory dibaca sekali per berkas. Blocking: panggil dari IO. */
    fun list(dir: File, showHidden: Boolean = true): List<File> {
        val kids = dir.listFiles() ?: return emptyList()
        return kids.filter { showHidden || !FileTypes.isHidden(it.name) }
            .map { it to it.isDirectory }
            .sortedWith(foldersFirst<Pair<File, Boolean>>({ it.second }, { it.first.name }))
            .map { it.first }
    }
}
