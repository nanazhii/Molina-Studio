package com.molina.explorer

import com.molina.core.fileops.ArchiveEngine
import com.molina.core.fileops.ArchiveRoute
import com.molina.core.fileops.CompressOptions
import com.molina.core.fileops.ExtractResult
import com.molina.core.fileops.ExtractTarget
import com.molina.core.fileops.FileOps
import com.molina.core.fileops.PasswordPolicy
import java.io.File

/**
 * [ARCHIVE5] Penghubung UI -> mesin arsip. Memilih [ArchiveEngine] atau mesin Debian ([ExplorerEnv.debianArchive])
 * lewat [ArchiveRoute]; bentuk hasil dan galat identik sehingga dialog dan runOp tidak perlu tahu mesin mana yang bekerja.
 * Blocking: panggil dari Dispatchers.IO.
 */
object ArchiveOps {
    fun list(env: ExplorerEnv, file: File, password: String? = null): FileOps.ZipListing =
        if (ArchiveRoute.viaDebian(file.name)) env.debianArchive.list(file, password)
        else ArchiveEngine.list(file, password)

    fun extract(
        env: ExplorerEnv, file: File, destDir: File, target: ExtractTarget, ctl: FileOps.Ctl,
        password: String? = null, only: Set<String>? = null,
    ): ExtractResult =
        if (ArchiveRoute.viaDebian(file.name)) env.debianArchive.extract(file, destDir, target, ctl, password, only)
        else ArchiveEngine.extract(file, destDir, target, ctl, password, only)

    /**
     * [ARCHIVE6] Kompres: 7z bersandi lewat mesin Debian; semua format lain lewat [ArchiveEngine.compress]
     * (tidak berubah). Keputusan ada di [ArchiveRoute.compressViaDebian].
     */
    fun compress(env: ExplorerEnv, files: List<File>, destDir: File, opt: CompressOptions, ctl: FileOps.Ctl): File {
        opt.password?.takeIf { it.isNotEmpty() }?.let { // [ENC-POLICY] pagar terakhir, di luar UI
            val v = PasswordPolicy.evaluate(it)
            if (!v.acceptable) throw java.io.IOException(v.message)
        }
        return if (ArchiveRoute.compressViaDebian(opt)) env.debianArchive.create(files, destDir, opt, ctl)
        else ArchiveEngine.compress(files, destDir, opt, ctl)
    }
}
