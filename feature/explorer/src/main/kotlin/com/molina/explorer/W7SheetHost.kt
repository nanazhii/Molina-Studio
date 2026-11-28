package com.molina.explorer

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.molina.core.files.NanoName
import com.molina.core.fileops.CompressNames
import com.molina.core.fileops.ExtractResult
import com.molina.core.fileops.ArchiveRoute
import com.molina.core.fileops.FileNames
import com.molina.core.fileops.FileOps
import java.io.File

/**
 * [FEATURE-EXPLORER] Semua yang dibutuhkan [W7SheetHost] dari Penjelajah. Nilai (dir, selected) adalah potret saat komposisi;
 * [clipFiles] dan [openFile] dibaca lambat karena isinya state yang bisa berubah. Callback opsional null = fitur tidak tersedia.
 */
internal class W7SheetEnv(
    val ctx: Context,
    val dir: File,
    val selected: W7Entry?,
    val env: ExplorerEnv,
    val actions: ExplorerActions,
    val clipFiles: () -> List<File>,
    val openFile: (File) -> Unit,
    val isVisibleInDebian: ((File) -> Boolean)?,
    val onNewTextInTerminal: ((File, String) -> Boolean)?,
    val onOpenInCode: ((List<File>) -> Unit)?,
    val onEditInTerminal: ((File) -> Unit)?,
    val onOpenWith: ((File) -> Boolean)?,
    val onOpenTerminal: ((File) -> Unit)?,
    val onShowFolder: (File) -> Unit, // [ARCHIVE-DEST] "Tampilkan folder" di dialog hasil ekstrak
)

/**
 * [FEATURE-EXPLORER] Penampil dialog (sheet) Penjelajah: memetakan [W7Sheet] aktif ke dialog yang sesuai dan menjalankan
 * operasinya lewat [ExplorerActions]. Dipisah dari Win7OpenDialog; [onSheet] mengganti atau menutup (null) sheet aktif.
 */
@Composable
internal fun W7SheetHost(sheet: W7Sheet?, onSheet: (W7Sheet?) -> Unit, e: W7SheetEnv) {
    val ctx = e.ctx
    val dir = e.dir
    val selected = e.selected
    val env = e.env
    val actions = e.actions
    val isVisibleInDebian = e.isVisibleInDebian
    val onNewTextInTerminal = e.onNewTextInTerminal
    val onOpenInCode = e.onOpenInCode
    val onEditInTerminal = e.onEditInTerminal
    val onOpenWith = e.onOpenWith
    val onOpenTerminal = e.onOpenTerminal
    fun say(text: String, ok: Boolean = false) = actions.say(text, ok)
    fun runOp(op: (FileOps.Ctl) -> String, onDone: () -> Unit = {}, onFail: (Throwable) -> Unit = {}) = actions.runOp(op, onDone, onFail)
    fun smartOpen(f: File) = e.openFile(f)

    CompositionLocalProvider(LocalExplorerTheme provides env.theme) { // [EXPLORER-THEME]
    when (val s = sheet) {
        is W7Sheet.Rename -> W7NameDialog(
            title = if (s.file.isDirectory) "Ubah nama folder" else "Ubah nama berkas",
            initial = s.file.name, selectEnd = FileNames.stemEnd(s.file.name, s.file.isDirectory), okLabel = "OK",
            onCancel = { onSheet(null) }
        ) { name ->
            onSheet(null)
            runOp(op = { "Nama diubah: " + FileOps.rename(s.file, name).name })
        }
        W7Sheet.NewDir -> W7NameDialog(
            title = "Folder baru", initial = "Folder baru", selectEnd = "Folder baru".length, okLabel = "Buat",
            onCancel = { onSheet(null) }
        ) { name ->
            onSheet(null)
            val d = dir
            runOp(op = { "Folder dibuat: " + FileOps.mkdir(d, name).name })
        }
        W7Sheet.NewText -> {
        val nanoInit = remember { (selected?.file?.takeIf { it.isFile }?.name ?: ExplorerRecent.lastFileName(ctx)) ?: "catatan.txt" } // [NANO-EXT]
        W7NameDialog( // [NANO-NEW]
            title = "Berkas teks baru (nano)", initial = nanoInit, selectEnd = NanoName.split(nanoInit).first.length, okLabel = "Buka", // [NANO-EXT]
            hint = "Folder: " + dir.path,
            onCancel = { onSheet(null) }
        ) { name ->
            onSheet(null)
            val d = dir
            onNewTextInTerminal?.invoke(d, name)
        }
        }
        is W7Sheet.OpenWith -> W7OpenWithDialog( // [NANO-OPENWITH]
            file = s.file,
            canCode = onOpenInCode != null,
            canNano = onEditInTerminal != null,
            canOther = onOpenWith != null,
            onCancel = { onSheet(null) },
            onPick = { which ->
                onSheet(null)
                when (which) {
                    W7OpenWith.CODE -> onOpenInCode?.invoke(listOf(s.file))
                    W7OpenWith.NANO -> onEditInTerminal?.invoke(s.file)
                    W7OpenWith.OTHER -> onOpenWith?.invoke(s.file)
                }
            }
        )
        is W7Sheet.Delete -> W7ConfirmDialog(s.files, s.permanent, onCancel = { onSheet(null) }) {
            onSheet(null)
            actions.delete(s.files, s.permanent)
        }
        is W7Sheet.Info -> W7InfoDialog(s.files, if (s.files.size == 1) isVisibleInDebian?.invoke(s.files[0]) else null) { onSheet(null) }
        is W7Sheet.Compress -> ArchiveCompressDialog( // [ARCHIVE2]
            s.files, dir.name, onClose = { onSheet(null) }
        ) { opt ->
            onSheet(null)
            val d = dir
            val items = s.files
            runOp(op = { ctl ->
                val made = ArchiveOps.compress(env, items, d, opt, ctl) // [ARCHIVE6] 7z bersandi lewat Debian
                "Arsip dibuat: " + made.name + " (" + CompressNames.sizeText(made.length()) + (if (!opt.password.isNullOrEmpty()) ", bersandi" else "") + ")"
            })
        }
        is W7Sheet.Zip -> ArchiveCompressDialog( // [ARCHIVE-UNI] "Kompres ZIP + kata sandi" memakai lembar yang sama, sandi aktif sejak awal
            s.files, dir.name, onClose = { onSheet(null) }, startWithPassword = true
        ) { opt ->
            onSheet(null)
            val d = dir
            val items = s.files
            runOp(op = { ctl ->
                val made = ArchiveOps.compress(env, items, d, opt, ctl)
                "Arsip dibuat: " + made.name + " (" + CompressNames.sizeText(made.length()) + (if (!opt.password.isNullOrEmpty()) ", bersandi" else "") + ")"
            })
        }
        is W7Sheet.Password -> W7PasswordDialog(
            title = "Ekstrak ZIP", okLabel = "Ekstrak", confirmField = false,
            note = if (s.wrong) "Kata sandi salah. Coba lagi." else "ZIP ini dilindungi kata sandi.",
            onCancel = { onSheet(null) }
        ) { pw ->
            onSheet(null)
            val d = dir
            val z = s.zip
            runExtractWithResult( // [ARCHIVE-DEST-2]
                run = { op, done, fail -> runOp(op, done, fail) }, onSheet = onSheet, say = { t, ok -> say(t, ok) },
                onPassword = { wrong -> onSheet(W7Sheet.Password(z, wrong)) },
                extract = { ctl -> ExtractResult(FileOps.unzip(z, d, ctl, pw), -1, 0) }
            )
        }
        is W7Sheet.ZipView -> W7ZipDialog(
            s.zip, onClose = { onSheet(null) }, onOpenFile = { smartOpen(it) },
            clipFiles = { e.clipFiles() }, // [ZIP-EDIT]
            onOpenTerminal = if (onOpenTerminal != null) ({ f: File ->
                if (isVisibleInDebian?.invoke(f) != false) { onOpenTerminal.invoke(f.parentFile ?: f); true } else false
            }) else null
        ) {
            onSheet(W7Sheet.ExtractTo(s.zip, null, null, s)) // [ARCHIVE-DEST] pilih tujuan dulu
        }
        is W7Sheet.Archive -> ArchiveExtractDialog( // [ARCHIVE]
            s.file, s.pw, env,
            onNeedPassword = { wrong -> onSheet(W7Sheet.ArchivePw(s.file, wrong)) },
            onClose = { onSheet(null) },
            initialKeys = s.keep
        ) { exOnly ->
            onSheet(W7Sheet.ExtractTo(s.file, s.pw, exOnly, W7Sheet.Archive(s.file, s.pw, exOnly ?: emptySet()))) // [ARCHIVE-DEST]
        }
        is W7Sheet.ExtractTo -> ArchiveExtractDestSheet( // [ARCHIVE-DEST]
            file = s.file, folderName = ArchiveRoute.folderName(s.file.name),
            hereDir = ExtractDest.baseDir(s.file, dir), selectedCount = s.only?.size ?: 0, env = env,
            onBack = { onSheet(s.back) }
        ) { base, target ->
            onSheet(null)
            val a = s.file
            val apw = s.pw
            val only = s.only
            runExtractWithResult(
                run = { op, done, fail -> runOp(op, done, fail) }, onSheet = onSheet, say = { t, ok -> say(t, ok) },
                onPassword = { wrong -> onSheet(W7Sheet.ArchivePw(a, wrong)) },
                extract = { ctl -> ArchiveOps.extract(env, a, base, target, ctl, apw, only) }
            )
        }
        is W7Sheet.ExtractDone -> {
            val outDir = s.result.dir
            val term = onOpenTerminal
            val canTerm = term != null && isVisibleInDebian?.invoke(outDir) != false
            ArchiveExtractDoneDialog(
                s.result, onClose = { onSheet(null) },
                onTerminal = if (canTerm) ({ onSheet(null); term?.invoke(outDir) }) else null
            ) {
                onSheet(null)
                e.onShowFolder(outDir)
            }
        }
        is W7Sheet.ArchivePw -> W7PasswordDialog( // [ARCHIVE]
            title = "Buka arsip", okLabel = "Buka", confirmField = false,
            note = if (s.wrong) "Kata sandi salah. Coba lagi." else "Arsip ini dilindungi kata sandi.",
            onCancel = { onSheet(null) }
        ) { pw ->
            onSheet(W7Sheet.Archive(s.file, pw))
        }
        null -> Unit
    }
    }
}
