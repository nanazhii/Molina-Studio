package com.molina.studio.cinema

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [SPLIT-TOOLS] Menu Alat Penjelajah (popup drop-up): diangkat dari Win7OpenDialog.kt menjadi composable tersendiri.
 * State tetap milik Win7OpenDialog: nilai dikirim sebagai parameter, perubahan lewat callback (onClip, onSheet, dst.).
 */
@Composable
internal fun W7ToolsMenu(
    targets: List<File>, dir: File, browse: Boolean, clip: W7Clip?,
    playedFirst: Boolean, playedRefs: List<String>, shown: List<W7Entry>?,
    onPlay: (suspend (List<File>) -> Boolean)?, onOpenInCode: ((List<File>) -> Unit)?,
    onSendToScripts: ((File) -> Pair<Boolean, String>)?, onOpenWith: ((File) -> Boolean)?,
    onShare: ((List<File>) -> Boolean)?, onCopyPath: ((File) -> Unit)?,
    onDismiss: () -> Unit, onClip: (W7Clip?) -> Unit, onPlayedFirst: (Boolean) -> Unit,
    onSheet: (W7Sheet) -> Unit, onFavChanged: () -> Unit,
    onSay: (String, Boolean) -> Unit,
    onRunOp: ((FileOps.Ctl) -> String, () -> Unit, (Throwable) -> Unit) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    fun say(text: String, ok: Boolean = false) = onSay(text, ok)
    fun runOp(op: (FileOps.Ctl) -> String, onDone: () -> Unit = {}, onFail: (Throwable) -> Unit = {}) = onRunOp(op, onDone, onFail)
    val tg = targets
    val one = tg.singleOrNull()
    val favTarget = if (tg.isEmpty()) dir else one?.takeIf { it.isDirectory }
    val isFav = favTarget != null && ExplorerFavorites.contains(ctx, favTarget)
    val c = clip
    val playable = onPlay != null && tg.isNotEmpty() &&
        (one?.isDirectory == true || tg.all { it.isFile && it.extension.lowercase() in W7_MEDIA })
    Popup(
        alignment = Alignment.BottomEnd,
        offset = IntOffset(0, -with(density) { 44.dp.roundToPx() }),
        onDismissRequest = { onDismiss() },
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            Modifier.width(250.dp).heightIn(max = w7PopupMaxHeight(400.dp)).background(Win7.Input) // [POPUP-SCROLL]
                .border(1.dp, Win7.FrameLine).verticalScroll(rememberScrollState())
        ) {
            W7MenuItem(W7Ico.PLAY, if (one?.isDirectory == true) "Putar folder di mpv" else "Putar di mpv", playable) {
                onDismiss()
                val fn = onPlay
                if (fn != null && tg.isNotEmpty()) scope.launch {
                    val ok = fn(tg)
                    say(if (ok) "Memutar di mpv: " + w7Names(tg) else "Tidak ada media yang bisa diputar", ok)
                }
            }
            val qAll = (shown ?: emptyList()).filter { !it.isDir && it.file.extension.lowercase() in W7_MEDIA && it.file.extension.lowercase() !in W7_LISTS }.map { it.file }
            val qFrom = one?.takeIf { it.isFile }?.let { sel ->
                val i = qAll.indexOf(sel)
                if (i >= 0) qAll.drop(i) + qAll.take(i) else null
            }
            W7MenuItem(W7Ico.PLAY, "Putar semua di daftar ini (" + qAll.size + ")", onPlay != null && qAll.size > 1) {
                onDismiss()
                val fn = onPlay
                if (fn != null) scope.launch {
                    val ok = fn(qAll)
                    say(if (ok) "Memutar antrean " + qAll.size + " berkas di mpv" else "Tidak ada media yang bisa diputar", ok)
                }
            }
            W7MenuItem(W7Ico.PLAY, "Putar mulai dari item ini", onPlay != null && qFrom != null && qFrom.size > 1) {
                onDismiss()
                val fn = onPlay
                if (fn != null && qFrom != null) scope.launch {
                    val ok = fn(qFrom)
                    say(if (ok) "Memutar " + qFrom.size + " berkas mulai dari " + qFrom[0].name else "Tidak ada media yang bisa diputar", ok)
                }
            }
            W7MenuItem(W7Ico.PLAY, if (playedFirst) "Urutan: normal" else "Urutan: terakhir diputar dulu", playedRefs.isNotEmpty() || playedFirst) {
                onDismiss()
                onPlayedFirst(!playedFirst)
            }
            W7MenuItem(W7Ico.CODE, "Buka di Code", onOpenInCode != null && tg.any { it.isFile }) {
                onDismiss()
                val fn = onOpenInCode
                if (fn != null) {
                    val texts = tg.filter { ExplorerList.looksText(it) }.take(10)
                    if (texts.isEmpty()) say("Tidak ada berkas teks yang bisa dibuka di Code.") else fn(texts)
                }
            }
            W7MenuItem(W7Ico.SCRIPT, "Kirim ke Script execution", onSendToScripts != null && one != null && one.isFile) {
                onDismiss()
                val fn = onSendToScripts
                if (fn != null && one != null) scope.launch {
                    val r = withContext(Dispatchers.IO) { fn(one) }
                    say(r.second, r.first)
                }
            }
            W7MenuItem(W7Ico.OPEN_WITH, "Buka dengan...", onOpenWith != null && one?.isFile == true) {
                onDismiss()
                one?.let { onOpenWith?.invoke(it) }
            }
            W7MenuItem(W7Ico.SHARE, "Bagikan", onShare != null && tg.any { it.isFile }) {
                onDismiss()
                val fn = onShare
                if (fn != null) {
                    val fs = tg.filter { it.isFile }
                    if (fs.size < tg.size) say("Folder dilewati; hanya berkas yang dibagikan.")
                    fn(fs)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Win7.Hairline))
            W7MenuItem(W7Ico.COPY, "Salin", tg.isNotEmpty()) {
                onDismiss()
                if (tg.isNotEmpty()) { onClip(W7Clip(tg, false)); say("Disalin: " + w7Names(tg), true) }
            }
            W7MenuItem(W7Ico.CUT, "Pindah", tg.isNotEmpty()) {
                onDismiss()
                if (tg.isNotEmpty()) {
                    onClip(W7Clip(tg, true))
                    say("Siap dipindah: " + w7Names(tg) + ". Buka folder tujuan lalu Tempel.", true)
                }
            }
            W7MenuItem(W7Ico.PASTE, if (c != null) "Tempel: " + w7Names(c.files) else "Tempel", c != null) {
                onDismiss()
                if (c != null) {
                    val d = dir
                    runOp(
                        op = { ctl ->
                            val res = if (c.cut) FileOps.moveAll(c.files, d, ctl) else FileOps.copyAll(c.files, d, ctl)
                            val head = if (c.cut) "Dipindah: " else "Ditempel: "
                            head + w7Names(res)
                        },
                        onDone = { if (c.cut) onClip(null) }
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Win7.Hairline))
            W7MenuItem(W7Ico.RENAME, "Ubah nama", one != null) {
                onDismiss()
                if (one != null) onSheet(W7Sheet.Rename(one))
            }
            W7MenuItem(W7Ico.ZIP, "Kompres (ZIP)", tg.isNotEmpty()) {
                onDismiss()
                if (tg.isNotEmpty()) {
                    val d = dir
                    runOp(op = { ctl -> "ZIP dibuat: " + FileOps.zipAll(tg, d, ctl).name })
                }
            }
            W7MenuItem(W7Ico.ZIP, "Kompres ZIP + kata sandi\u2026", tg.isNotEmpty()) {
                onDismiss()
                if (tg.isNotEmpty()) onSheet(W7Sheet.Zip(tg))
            }
            W7MenuItem(W7Ico.ZIP, "Kompres\u2026 (7z, tar.gz, ...)", tg.isNotEmpty()) { // [ARCHIVE2]
                onDismiss()
                if (tg.isNotEmpty()) onSheet(W7Sheet.Compress(tg))
            }
            W7MenuItem(
                W7Ico.UNZIP, "Ekstrak arsip",
                one != null && one.isFile && ArchiveRoute.supports(one.name)
            ) { // [ARCHIVE] ZIP tetap lewat jalur lama; format lain lewat ArchiveEngine
                onDismiss()
                if (one != null) {
                    val d = dir
                    if (one.extension.equals("zip", ignoreCase = true)) {
                        runOp(
                            op = { ctl -> "Diekstrak ke: " + FileOps.unzip(one, d, ctl).name },
                            onFail = { if (it is FileOps.NeedPassword) { say(""); onSheet(W7Sheet.Password(one, false)) } }
                        )
                    } else {
                        runOp(
                            op = { ctl -> ArchiveOps.extract(ctx, one, d, ExtractTarget.NAMED_FOLDER, ctl).describe() },
                            onFail = { if (it is FileOps.NeedPassword) { say(""); onSheet(W7Sheet.ArchivePw(one, false)) } }
                        )
                    }
                }
            }
            W7MenuItem(
                W7Ico.UNZIP, "Ekstrak pilihan\u2026",
                one != null && one.isFile && ArchiveRoute.supports(one.name)
            ) { // [ARCHIVE] pilih tujuan dan entri
                onDismiss()
                if (one != null) onSheet(W7Sheet.Archive(one, null))
            }
            W7MenuItem(W7Ico.DELETE, "Hapus", tg.isNotEmpty()) {
                onDismiss()
                if (tg.isNotEmpty()) onSheet(W7Sheet.Delete(tg))
            }
            W7MenuItem(W7Ico.DELETE, "Hapus permanen\u2026", tg.isNotEmpty()) {
                onDismiss()
                if (tg.isNotEmpty()) onSheet(W7Sheet.Delete(tg, true))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Win7.Hairline))
            W7MenuItem(W7Ico.NEWDIR, "Folder baru", true) {
                onDismiss()
                onSheet(W7Sheet.NewDir)
            }
            W7MenuItem(
                if (isFav) W7Ico.STAR else W7Ico.STAR_OUT,
                if (isFav) "Hapus dari favorit" else "Pindah ke favorit",
                favTarget != null
            ) {
                onDismiss()
                if (favTarget != null) {
                    val nm = favTarget.name.ifEmpty { favTarget.path }
                    if (isFav) {
                        ExplorerFavorites.remove(ctx, favTarget)
                        say("Dihapus dari favorit: " + nm, true)
                    } else if (w7Favorites(browse).any { it.dir.path == favTarget.path }) {
                        say("Folder ini sudah ada di favorit bawaan.")
                    } else {
                        ExplorerFavorites.add(ctx, favTarget)
                        say("Ditambahkan ke favorit: " + nm, true)
                    }
                    onFavChanged()
                }
            }
            W7MenuItem(W7Ico.COPY, "Salin path", onCopyPath != null && (one != null || tg.isEmpty())) {
                onDismiss()
                onCopyPath?.invoke(one ?: dir)
            }
            W7MenuItem(W7Ico.INFO, "Properti", true) {
                onDismiss()
                onSheet(W7Sheet.Info(if (tg.isEmpty()) listOf(dir) else tg))
            }
        }
    }
}
