package com.molina.studio.cinema

import android.content.Context
import java.io.File

/**
 * [ARCHIVE5] Penghubung UI -> mesin arsip. Memilih [ArchiveEngine] atau [DebianArchive] lewat [ArchiveRoute];
 * bentuk hasil dan galat identik sehingga dialog dan runOp tidak perlu tahu mesin mana yang bekerja.
 * Blocking: panggil dari Dispatchers.IO.
 */
internal object ArchiveOps {
    fun list(ctx: Context, file: File, password: String? = null): FileOps.ZipListing =
        if (ArchiveRoute.viaDebian(file.name)) DebianArchive(ctx).list(file, password)
        else ArchiveEngine.list(file, password)

    fun extract(
        ctx: Context, file: File, destDir: File, target: ExtractTarget, ctl: FileOps.Ctl,
        password: String? = null, only: Set<String>? = null,
    ): ExtractResult =
        if (ArchiveRoute.viaDebian(file.name)) DebianArchive(ctx).extract(file, destDir, target, ctl, password, only)
        else ArchiveEngine.extract(file, destDir, target, ctl, password, only)

    /**
     * [ARCHIVE6] Kompres: 7z bersandi lewat [DebianArchive.create]; semua format lain lewat [ArchiveEngine.compress]
     * (tidak berubah). Keputusan ada di [ArchiveRoute.compressViaDebian].
     */
    fun compress(ctx: Context, files: List<File>, destDir: File, opt: CompressOptions, ctl: FileOps.Ctl): File =
        if (ArchiveRoute.compressViaDebian(opt)) DebianArchive(ctx).create(files, destDir, opt, ctl)
        else ArchiveEngine.compress(files, destDir, opt, ctl)
}
