package com.molina.explorer

import com.molina.core.fileops.ExtractResult
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
    class Archive(val file: File, val pw: String?, val keep: Set<String> = emptySet()) : W7Sheet() // [ARCHIVE] 7z/tar/gz/bz2/xz (dan ZIP lewat "Ekstrak pilihan")
    class ArchivePw(val file: File, val wrong: Boolean) : W7Sheet() // [ARCHIVE]
    // [ARCHIVE-DEST] Pilih tujuan ekstrak (di sini / tempat lain); [only] null = semua; [back] = lembar asal untuk panah kembali
    class ExtractTo(val file: File, val pw: String?, val only: Set<String>?, val back: W7Sheet?) : W7Sheet()
    class ExtractDone(val result: ExtractResult) : W7Sheet() // [ARCHIVE-DEST] keterangan sukses + lokasi hasil
    class Info(val files: List<File>) : W7Sheet()
    class Delete(val files: List<File>, val permanent: Boolean = false) : W7Sheet()
    object NewDir : W7Sheet()
    object NewText : W7Sheet() // [NANO-NEW]
    class OpenWith(val file: File) : W7Sheet() // [NANO-OPENWITH]
}
