package com.molina.explorer

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.molina.core.files.NanoName
import com.molina.core.fileops.CompressNames
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
        is W7Sheet.Zip -> W7PasswordDialog(
            title = "Kompres ZIP berkata sandi", okLabel = "Kompres", confirmField = true,
            note = "Enkripsi AES-256. Simpan kata sandinya: ZIP tidak bisa dibuka tanpa itu.",
            onCancel = { onSheet(null) }
        ) { pw ->
            onSheet(null)
            val d = dir
            val items = s.files
            runOp(op = { ctl -> "ZIP terenkripsi dibuat: " + FileOps.zipAll(items, d, ctl, pw).name })
        }
        is W7Sheet.Password -> W7PasswordDialog(
            title = "Ekstrak ZIP", okLabel = "Ekstrak", confirmField = false,
            note = if (s.wrong) "Kata sandi salah. Coba lagi." else "ZIP ini dilindungi kata sandi.",
            onCancel = { onSheet(null) }
        ) { pw ->
            onSheet(null)
            val d = dir
            val z = s.zip
            runOp(
                op = { ctl -> "Diekstrak ke: " + FileOps.unzip(z, d, ctl, pw).name },
                onFail = { if (it is FileOps.WrongPassword) onSheet(W7Sheet.Password(z, true)) }
            )
        }
        is W7Sheet.ZipView -> W7ZipDialog(
            s.zip, onClose = { onSheet(null) }, onOpenFile = { smartOpen(it) },
            clipFiles = { e.clipFiles() }, // [ZIP-EDIT]
            onOpenTerminal = if (onOpenTerminal != null) ({ f: File ->
                if (isVisibleInDebian?.invoke(f) != false) { onOpenTerminal.invoke(f.parentFile ?: f); true } else false
            }) else null
        ) {
            onSheet(null)
            val z = s.zip
            val d = z.parentFile?.takeIf { it.isDirectory } ?: dir
            runOp(
                op = { ctl -> "Diekstrak ke: " + FileOps.unzip(z, d, ctl).name },
                onFail = { if (it is FileOps.NeedPassword) { say(""); onSheet(W7Sheet.Password(z, false)) } }
            )
        }
        is W7Sheet.Archive -> ArchiveExtractDialog( // [ARCHIVE]
            s.file, s.pw, env,
            onNeedPassword = { wrong -> onSheet(W7Sheet.ArchivePw(s.file, wrong)) },
            onClose = { onSheet(null) }
        ) { exTarget, exOnly ->
            onSheet(null)
            val a = s.file
            val apw = s.pw
            val d = a.parentFile?.takeIf { it.isDirectory && it.canWrite() } ?: dir
            runOp(
                op = { ctl -> ArchiveOps.extract(env, a, d, exTarget, ctl, apw, exOnly).describe() },
                onFail = {
                    if (it is FileOps.NeedPassword) { say(""); onSheet(W7Sheet.ArchivePw(a, false)) }
                    else if (it is FileOps.WrongPassword) { say(""); onSheet(W7Sheet.ArchivePw(a, true)) }
                }
            )
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
