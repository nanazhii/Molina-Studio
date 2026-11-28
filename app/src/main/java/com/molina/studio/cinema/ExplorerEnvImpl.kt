package com.molina.studio.cinema

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.molina.core.api.MolinaServices
import com.molina.core.fileops.CompressOptions
import com.molina.core.fileops.ExtractResult
import com.molina.core.fileops.ExtractTarget
import com.molina.core.fileops.FileOps
import com.molina.explorer.DebianArchiveRunner
import com.molina.explorer.ExplorerEnv
import com.molina.explorer.ExplorerTheme
import com.molina.explorer.PlayedHistory
import com.molina.explorer.PreviewLauncher
import java.io.File

/** [FEATURE-EXPLORER] Adapter :app -> kontrak [ExplorerEnv]. Tanpa state; aman dibuat per pemakaian. */
internal fun appExplorerEnv(ctx: Context, theme: ExplorerTheme = ExplorerTheme.Terminal): ExplorerEnv {
    val app = ctx.applicationContext
    return ExplorerEnv(
        debianArchive = object : DebianArchiveRunner {
            override fun list(file: File, password: String?): FileOps.ZipListing = DebianArchive(app).list(file, password)
            override fun extract(
                file: File, destDir: File, target: ExtractTarget, ctl: FileOps.Ctl,
                password: String?, only: Set<String>?
            ): ExtractResult = DebianArchive(app).extract(file, destDir, target, ctl, password, only)
            override fun create(files: List<File>, destDir: File, opt: CompressOptions, ctl: FileOps.Ctl): File =
                DebianArchive(app).create(files, destDir, opt, ctl)
        },
        preview = object : PreviewLauncher {
            override fun canPreview(file: File): Boolean = com.molina.studio.preview.ExplorerPreview.canPreview(file)
            override fun open(file: File) { com.molina.studio.preview.PreviewNav.pending = file }
        },
        played = object : PlayedHistory {
            override val version: Int get() = CinemaRecent.version
            override fun refs(): List<String> = CinemaRecent.load(app).map { it.ref }
        },
        rootfsDir = { MolinaServices.terminal.rootfsDir() },
        theme = theme,
    )
}

/** [FEATURE-EXPLORER] ExplorerEnv untuk pemanggil Compose di :app. */
@Composable
internal fun rememberAppExplorerEnv(theme: ExplorerTheme = ExplorerTheme.Terminal): ExplorerEnv {
    val ctx = LocalContext.current
    return remember(ctx, theme) { appExplorerEnv(ctx, theme) }
}
