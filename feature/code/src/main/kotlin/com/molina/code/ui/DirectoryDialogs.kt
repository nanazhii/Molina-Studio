package com.molina.code.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import java.io.File

internal enum class PickMode { Folder, File }

/** Permintaan pemilih berkas/folder. [onPick] dipanggil dengan folder terpilih (Folder) atau berkas yang diketuk (File). */
internal class PickerRequest(
    val mode: PickMode,
    val start: File,
    val title: String,
    val onPick: (File) -> Unit
)

internal class ActionItem(
    val label: String,
    val danger: Boolean = false,
    val separatorBefore: Boolean = false,
    val icon: MenuIcon? = null, // [CODE-MENUICON]
    val onClick: () -> Unit
)

/** Semua dialog F4 dalam satu tempat: menu aksi berkas, ganti nama, hapus, baru, konfirmasi muat ulang, pemilih. */
@Composable
internal fun FileDialogs(session: CodeSession) {
    val ctx = LocalContext.current
    val dir = session.dir
    when (val d = session.fileDialog) {
        null -> Unit
        is FileDialog.Menu -> {
            // Dari titik tiga baris tree: dropdown dirender di TreeRow. Dari "Aksi file": popup kanan atas.
            if (!d.fromTree) HeaderActionMenu(session, d.file)
        }
        is FileDialog.Rename -> NameDialog(
            title = "Ganti nama",
            initial = d.file.name,
            confirmLabel = "GANTI",
            onConfirm = { name ->
                session.fileDialog = null
                dir.rename(d.file, name)
            },
            onCancel = { session.fileDialog = null }
        )
        is FileDialog.NewFile -> NameDialog(
            title = "File baru",
            initial = "",
            confirmLabel = "BUAT",
            hint = "nama.ext",
            onConfirm = { name ->
                session.fileDialog = null
                dir.createFile(d.parent, name)
            },
            onCancel = { session.fileDialog = null }
        )
        is FileDialog.NewFolder -> NameDialog(
            title = "Folder baru",
            initial = "",
            confirmLabel = "BUAT",
            hint = "nama folder",
            onConfirm = { name ->
                session.fileDialog = null
                dir.createFolder(d.parent, name)
            },
            onCancel = { session.fileDialog = null }
        )
        is FileDialog.Delete -> {
            val isDir = d.file.isDirectory
            val open = session.hasTabUnder(d.file)
            val extra = if (open) "\nTab yang membukanya tetap terbuka sebagai belum tersimpan." else ""
            ConfirmDialog(
                title = if (isDir) "Hapus folder?" else "Hapus berkas?",
                message = (if (isDir) "\"${d.file.name}\" beserta seluruh isinya akan dihapus permanen." else "\"${d.file.name}\" akan dihapus permanen.") + extra,
                confirmLabel = "HAPUS",
                danger = true,
                onConfirm = {
                    session.fileDialog = null
                    dir.delete(d.file)
                },
                onCancel = { session.fileDialog = null }
            )
        }
    }

    session.reloadConfirm?.let { tab ->
        ConfirmDialog(
            title = "Muat ulang?",
            message = "\"${tab.title}\" punya perubahan yang belum disimpan. Muat ulang dari disk akan membuangnya.",
            confirmLabel = "BUANG",
            danger = true,
            onConfirm = { session.doReload(tab) },
            onCancel = { session.cancelReload() }
        )
    }

    session.picker?.let { req ->
        PickerDialog(
            mode = req.mode,
            start = req.start,
            title = req.title,
            onPick = {
                session.picker = null
                req.onPick(it)
            },
            onDismiss = { session.picker = null }
        )
    }
}

private fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("path", text))
}

@Composable
private fun DialogCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(CodeDimens.DialogCorner)
    Column(
        modifier
            .win7DialogFrame(shape)
            .padding(20.dp),
        content = content
    )
}

@Composable
private fun DialogAction(text: String, color: Color, onClick: () -> Unit) {
    BasicText(
        text = text,
        modifier = Modifier.clickable(onClick = onClick).padding(12.dp),
        style = TextStyle(color = color, fontSize = 14.sp)
    )
}

/** Konfirmasi dua tombol: BATAL dan aksi (emas, atau merah bila [danger]). */
@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    danger: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = onCancel) {
        DialogCard(Modifier.widthIn(max = 320.dp)) {
            BasicText(title, style = TextStyle(color = CodeColors.Gold, fontSize = CodeDimens.DialogTitleSize))
            Spacer(Modifier.height(12.dp))
            BasicText(message, style = TextStyle(color = CodeColors.TextPrimary, fontSize = 14.sp))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DialogAction("BATAL", CodeColors.LabelChampagne, onCancel)
                DialogAction(confirmLabel, if (danger) CodeColors.Danger else CodeColors.Gold, onConfirm)
            }
        }
    }
}

internal fun buildFileActions(session: CodeSession, ctx: Context, f: File): List<ActionItem> {
    val dir = session.dir
    val isDir = f.isDirectory
    return buildList {
        if (isDir) {
            add(ActionItem("Jadikan folder utama", icon = MenuIcon.FolderUp) {
                session.fileDialog = null
                dir.changeRoot(f)
            })
        } else {
            add(ActionItem("Buka", icon = MenuIcon.Open) {
                session.fileDialog = null
                session.openFile(f)
            })
        }
        val target = if (isDir) f else (f.parentFile ?: f)
        add(ActionItem("File baru di sini", icon = MenuIcon.New) { session.fileDialog = FileDialog.NewFile(target) })
        add(ActionItem("Folder baru di sini", icon = MenuIcon.NewFolder) { session.fileDialog = FileDialog.NewFolder(target) })
        add(ActionItem("Ganti nama", icon = MenuIcon.Rename) { session.fileDialog = FileDialog.Rename(f) })
        add(ActionItem("Salin nama", icon = MenuIcon.Copy) {
            copyToClipboard(ctx, f.name)
            session.toast("Nama disalin")
            session.fileDialog = null
        })
        add(ActionItem("Salin path", icon = MenuIcon.Copy) {
            copyToClipboard(ctx, f.path)
            session.toast("Path disalin")
            session.fileDialog = null
        })
        if (!dir.open) {
            add(ActionItem("Tampilkan di panel", icon = MenuIcon.Reveal) {
                session.fileDialog = null
                dir.reveal(f)
            })
        }
        add(ActionItem("Hapus", danger = true, separatorBefore = true, icon = MenuIcon.Delete) { session.fileDialog = FileDialog.Delete(f) })
    }
}

/** Menempatkan dropdown tepat di bawah jangkar (rata kanan), membalik ke atas bila tak muat, dan dijepit ke layar. */
private class AnchoredMenuPositionProvider(private val gapPx: Int, private val marginPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val maxX = maxOf(marginPx, windowSize.width - popupContentSize.width - marginPx)
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(marginPx, maxX)
        val below = anchorBounds.bottom + gapPx
        val fitsBelow = below + popupContentSize.height + marginPx <= windowSize.height
        val y = if (fitsBelow) below
        else (anchorBounds.top - gapPx - popupContentSize.height).coerceAtLeast(marginPx)
        return IntOffset(x, y)
    }
}

/** Dropdown ringkas yang menempel di titik tiga baris tree. Dipanggil di dalam kotak titik tiga. */
@Composable
internal fun TreeActionMenu(session: CodeSession, file: File) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val provider = remember(density) {
        AnchoredMenuPositionProvider(with(density) { 2.dp.roundToPx() }, with(density) { 8.dp.roundToPx() })
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = { session.fileDialog = null },
        properties = PopupProperties(focusable = true)
    ) {
        CompactActionMenu(file.name, buildFileActions(session, ctx, file))
    }
}

/** Dropdown ringkas kanan atas untuk "Aksi file" dari menu titik tiga header. */
@Composable
private fun HeaderActionMenu(session: CodeSession, file: File) {
    val ctx = LocalContext.current
    val offsetY = with(LocalDensity.current) { CodeDimens.HeaderHeight.roundToPx() }
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, offsetY),
        onDismissRequest = { session.fileDialog = null },
        properties = PopupProperties(focusable = true)
    ) {
        Box(Modifier.padding(8.dp)) {
            CompactActionMenu(file.name, buildFileActions(session, ctx, file))
        }
    }
}

/** Isi dropdown: nama berkas kecil di atas, baris 40dp, "Hapus" dipisah hairline, tinggi maks 72% layar. */
@Composable
private fun CompactActionMenu(title: String, items: List<ActionItem>) {
    val shape = RoundedCornerShape(CodeDimens.MenuCorner)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * CodeDimens.MenuMaxHeightFraction).dp
    Column(
        Modifier
            .shadow(CodeDimens.MenuElevation, shape)
            .clip(shape)
            .background(CodeColors.SurfaceMenu)
            .border(CodeDimens.Hairline, CodeColors.Hairline, shape)
            .widthIn(min = 180.dp, max = CodeDimens.MenuMaxWidth)
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
    ) {
        BasicText(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
            style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 12.sp)
        )
        items.forEach { item ->
            if (item.separatorBefore) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .height(CodeDimens.Hairline).background(CodeColors.Hairline)
                )
            }
            Row(
                Modifier.fillMaxWidth().height(40.dp)
                    .clickable(onClick = item.onClick)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val ic = item.icon // [CODE-MENUICON]
                val tone = if (item.danger) CodeColors.Danger else CodeColors.IconDefault
                if (ic != null) Canvas(Modifier.size(CodeDimens.MenuIconSize)) { drawMenuIcon(ic, tone) }
                else Spacer(Modifier.width(CodeDimens.MenuIconSize))
                Spacer(Modifier.width(12.dp))
                BasicText(
                    text = item.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = tone, fontSize = 14.sp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** Dialog satu field nama (berkas/folder baru, ganti nama) dengan validasi [FileStore.validate]. */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
    hint: String = "nama"
) {
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

    fun submit() {
        val err = FileStore.validate(name, "")
        if (err != null) error = err else onConfirm(name.trim())
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DialogCard(Modifier.fillMaxWidth(0.92f).widthIn(max = 360.dp)) {
            BasicText(title, style = TextStyle(color = CodeColors.Gold, fontSize = CodeDimens.DialogTitleSize))
            Spacer(Modifier.height(16.dp))
            Label("Nama:")
            UnderlineField(
                value = name,
                onChange = {
                    name = it.replace("\n", "")
                    error = null
                },
                hint = hint,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                imeAction = ImeAction.Done,
                onAction = { submit() },
                onFocus = {}
            )
            val shown = error
            if (shown != null) {
                Spacer(Modifier.height(4.dp))
                BasicText(shown, style = TextStyle(color = CodeColors.Danger, fontSize = 13.sp))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DialogAction("BATAL", CodeColors.LabelChampagne, onCancel)
                DialogAction(confirmLabel, CodeColors.Gold) { submit() }
            }
        }
    }
}

/**
 * Pemilih folder atau berkas tanpa SAF (path langsung /storage/emulated/0). Mode Folder hanya
 * menampilkan folder dan tombol PILIH untuk folder yang sedang dibuka; mode File juga menampilkan
 * berkas dan mengetuknya memilih berkas itu. Daftar dibaca sinkron: satu folder, dibatasi 2000 entri.
 */
@Composable
internal fun PickerDialog(
    mode: PickMode,
    start: File,
    title: String,
    onPick: (File) -> Unit,
    onDismiss: () -> Unit
) {
    var cur by remember {
        mutableStateOf(
            when {
                start.isDirectory -> start
                start.parentFile?.isDirectory == true -> start.parentFile!!
                else -> File(FileStore.STORAGE_ROOT)
            }
        )
    }
    val entries = remember(cur) {
        FileStore.listDir(cur)?.filter { mode == PickMode.File || it.isDir }
    }
    val canUp = cur.path != FileStore.STORAGE_ROOT && cur.parentFile != null
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DialogCard(Modifier.fillMaxWidth(0.94f).widthIn(max = 380.dp).heightIn(max = maxHeight)) {
            BasicText(title, style = TextStyle(color = CodeColors.Gold, fontSize = CodeDimens.DialogTitleSize))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DirIconButton(DirIcon.Up, enabled = canUp) { cur = cur.parentFile ?: cur }
                BasicText(
                    text = cur.path,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = CodeColors.TextSecondary, fontSize = 12.sp)
                )
            }
            Box(Modifier.fillMaxWidth().height(CodeDimens.Hairline).background(CodeColors.Hairline))
            when {
                entries == null -> BasicText(
                    text = "Folder tidak bisa dibaca (cek izin penyimpanan).",
                    modifier = Modifier.padding(vertical = 16.dp),
                    style = TextStyle(color = CodeColors.Danger, fontSize = 13.sp)
                )
                entries.isEmpty() -> BasicText(
                    text = if (mode == PickMode.Folder) "Tidak ada subfolder." else "Folder kosong.",
                    modifier = Modifier.padding(vertical = 16.dp),
                    style = TextStyle(color = CodeColors.TextSecondary, fontSize = 13.sp)
                )
                else -> LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(entries, key = { it.file.path }) { e ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(CodeDimens.PickerRowHeight)
                                .clickable { if (e.isDir) cur = e.file else onPick(e.file) }
                                .alpha(if (e.hidden) CodeColors.HiddenFileAlpha else 1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Canvas(Modifier.size(CodeDimens.TreeFolderIcon)) {
                                if (e.isDir) drawFolderIcon(false, CodeColors.FolderYellow) else drawFileIcon(extColor(e.file.name))
                            }
                            Spacer(Modifier.width(10.dp))
                            BasicText(
                                text = e.file.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(color = CodeColors.TextPrimary, fontSize = 14.sp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DialogAction("BATAL", CodeColors.LabelChampagne, onDismiss)
                if (mode == PickMode.Folder) DialogAction("PILIH FOLDER INI", CodeColors.Gold) { onPick(cur) }
            }
        }
    }
}
