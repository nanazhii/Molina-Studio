package com.molina.explorer

import java.io.File

// [FEATURE-EXPLORER] Model dialog Penjelajah (entri, papan klip, lembar aktif). Dipindah apa adanya dari Win7OpenDialog.kt.

class W7Entry(val file: File, val isDir: Boolean, val size: Long, val modified: Long, val rel: String? = null) {
    val name: String get() = file.name
}

class W7Clip(val files: List<File>, val cut: Boolean)
sealed class W7Sheet {
    class Rename(val file: File) : W7Sheet()
    class Zip(val files: List<File>) : W7Sheet()
    class Compress(val files: List<File>) : W7Sheet() // [ARCHIVE2] dialog Kompres (format, level, nama, sandi ZIP)
    class Password(val zip: File, val wrong: Boolean) : W7Sheet()
    class ZipView(val zip: File) : W7Sheet() // [ZIP-VIEW]
    class Archive(val file: File, val pw: String?) : W7Sheet() // [ARCHIVE] 7z/tar/gz/bz2/xz (dan ZIP lewat "Ekstrak pilihan")
    class ArchivePw(val file: File, val wrong: Boolean) : W7Sheet() // [ARCHIVE]
    class Info(val files: List<File>) : W7Sheet()
    class Delete(val files: List<File>, val permanent: Boolean = false) : W7Sheet()
    object NewDir : W7Sheet()
    object NewText : W7Sheet() // [NANO-NEW]
    class OpenWith(val file: File) : W7Sheet() // [NANO-OPENWITH]
}
