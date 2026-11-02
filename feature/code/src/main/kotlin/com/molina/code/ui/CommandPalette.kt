package com.molina.code.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [CODE-PALETTE] Satu hasil di Palet Perintah. [tag] = jenis (Perintah/Tab/Berkas), [hint] = info redup (folder). */
internal class PaletteItem(
    val label: String,
    val hint: String,
    val tag: String,
    val icon: MenuIcon,
    val score: Int,
    val action: () -> Unit
)

/** Pemindai berkas folder utama untuk "Pergi ke berkas": berbatas kedalaman dan jumlah, tanpa tautan simbolik. */
internal object PaletteScan {
    private val SKIP_DIRS = setOf(".git", "node_modules", "build", ".gradle", ".idea", "__pycache__", ".cache", "zipbak", "zipview")
    private val SKIP_EXT = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "mp3", "mp4", "mkv", "avi", "wav", "flac", "ogg",
        "zip", "7z", "rar", "gz", "xz", "apk", "so", "bin", "dex", "class", "jar", "pdf", "iso"
    )

    fun scan(root: File, maxFiles: Int = 4000, maxDepth: Int = 8): List<File> {
        val out = ArrayList<File>()
        fun walk(d: File, depth: Int) {
            if (out.size >= maxFiles || depth > maxDepth) return
            val kids = d.listFiles() ?: return
            kids.sortBy { it.name.lowercase() }
            for (k in kids) {
                if (out.size >= maxFiles) return
                if (runCatching { Files.isSymbolicLink(k.toPath()) }.getOrDefault(false)) continue
                if (k.isDirectory) {
                    if (k.name !in SKIP_DIRS) walk(k, depth + 1)
                } else if (k.extension.lowercase() !in SKIP_EXT) {
                    out.add(k)
                }
            }
        }
        walk(root, 0)
        return out
    }
}

/**
 * Palet Perintah (pola Search Everywhere di Android Studio): satu kotak cari untuk perintah menu titik tiga
 * yang sedang aktif, tab terbuka, dan berkas di folder utama. Enter menjalankan hasil teratas.
 */
@Composable
internal fun CommandPalette(session: CodeSession, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    val root = session.dir.root
    LaunchedEffect(root.path) { files = withContext(Dispatchers.IO) { PaletteScan.scan(root) } }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val q = query.trim()
    val pool = ArrayList<PaletteItem>()
    for (c in menuGroups(session, ctx, onDismiss).flatten()) {
        if (!c.enabled || c.label == "Palet perintah") continue
        val s = fuzzyScore(q, c.label)
        if (s >= 0) pool.add(PaletteItem(c.label, "", "Perintah", c.icon ?: MenuIcon.Actions, s + 5, c.onClick))
    }
    for (t in session.tabs) {
        val s = fuzzyScore(q, t.title)
        if (s >= 0) pool.add(PaletteItem(t.title, t.file?.parent.orEmpty(), "Tab", MenuIcon.File, s + 3) { session.select(t.id) })
    }
    val fileHits = remember(q, files) {
        if (q.isEmpty()) emptyList()
        else files.mapNotNull { f -> fuzzyScore(q, f.name).let { s -> if (s >= 0) Pair(f, s) else null } }
            .sortedByDescending { it.second }.take(40)
    }
    for ((f, s) in fileHits) {
        val rel = f.parentFile?.path.orEmpty().removePrefix(root.path).trimStart('/')
        pool.add(PaletteItem(f.name, rel, "Berkas", MenuIcon.File, s) { session.openFile(f) })
    }
    val shown = pool.sortedByDescending { it.score }.take(60)

    fun go(item: PaletteItem) {
        item.action()
        onDismiss()
    }

    val shape = RoundedCornerShape(CodeDimens.DialogCorner)
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).win7DialogFrame(shape)) {
            BasicTextField(
                value = query,
                onValueChange = { query = it.take(80) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).padding(horizontal = 16.dp, vertical = 14.dp),
                singleLine = true,
                textStyle = TextStyle(color = CodeColors.TextPrimary, fontSize = CodeDimens.FindTextSize),
                cursorBrush = SolidColor(CodeColors.Gold),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { shown.firstOrNull()?.let { go(it) } }),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            BasicText(
                                text = "Ketik perintah, tab, atau nama berkas",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(color = CodeColors.IconDefault.copy(alpha = 0.5f), fontSize = CodeDimens.FindTextSize)
                            )
                        }
                        inner()
                    }
                }
            )
            Box(Modifier.fillMaxWidth().height(CodeDimens.Hairline).background(CodeColors.Hairline))
            if (shown.isEmpty()) {
                BasicText(
                    text = if (q.isEmpty()) "Tidak ada perintah." else "Tidak ada hasil untuk \"$q\".",
                    modifier = Modifier.padding(16.dp),
                    style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 13.sp)
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxH)) {
                    items(shown) { item ->
                        Row(
                            Modifier.fillMaxWidth().height(46.dp).clickable { go(item) }.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Canvas(Modifier.size(CodeDimens.MenuIconSize)) { drawMenuIcon(item.icon, CodeColors.IconDefault) }
                            Spacer(Modifier.width(12.dp))
                            BasicText(
                                text = item.label,
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(color = CodeColors.IconDefault, fontSize = 15.sp)
                            )
                            if (item.hint.isNotEmpty()) {
                                Spacer(Modifier.width(8.dp))
                                BasicText(
                                    text = item.hint,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 12.sp)
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            Spacer(Modifier.width(8.dp))
                            BasicText(
                                text = item.tag,
                                maxLines = 1,
                                style = TextStyle(color = CodeColors.Gold.copy(alpha = 0.7f), fontSize = 10.sp)
                            )
                        }
                    }
                }
            }
        }
    }
}
