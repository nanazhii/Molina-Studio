package com.molina.explorer

import com.molina.core.fileops.CompressOptions
import com.molina.core.fileops.ExtractResult
import com.molina.core.fileops.ExtractTarget
import com.molina.core.fileops.FileOps
import java.io.File

/** [FEATURE-EXPLORER] Mesin arsip Debian (PRoot). Diimplementasi :app; Penjelajah hanya mengenal kontraknya. */
interface DebianArchiveRunner {
    fun list(file: File, password: String?): FileOps.ZipListing
    fun extract(
        file: File, destDir: File, target: ExtractTarget, ctl: FileOps.Ctl,
        password: String?, only: Set<String>?
    ): ExtractResult
    fun create(files: List<File>, destDir: File, opt: CompressOptions, ctl: FileOps.Ctl): File
}

/** [FEATURE-EXPLORER] Pratinjau berkas (MD/HTML/JSON). Diimplementasi :app lewat :feature:preview. */
interface PreviewLauncher {
    fun canPreview(file: File): Boolean
    fun open(file: File)
}

/** Semua hal yang harus disuntik ke Penjelajah dari luar modul. Sengaja kecil: tambah hanya jika ada kebutuhan nyata. */
class ExplorerEnv(val debianArchive: DebianArchiveRunner, val preview: PreviewLauncher)
