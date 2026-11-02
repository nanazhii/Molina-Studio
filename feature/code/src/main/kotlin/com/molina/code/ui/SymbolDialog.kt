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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * [CODE-SYMBOL] Pergi ke simbol (pola Go to Symbol): cari kelas/fungsi di seluruh folder utama dengan fuzzy.
 * Indeks dibangun di thread latar dan di-cache per berkas (CodeSession.symbolIndex); teks tab yang terbuka
 * (termasuk yang belum disimpan) dipakai sebagai ganti isi disk. Ketuk hasil = buka berkas dan lompat ke barisnya.
 */
@Composable
internal fun SymbolDialog(session: CodeSession, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var onlyThisFile by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SymbolIndex.Result?>(null) }
    var indexing by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    val root = session.dir.root
    val activeFile = session.active?.file

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(root.path) {
        indexing = true
        // Teks tab diambil di thread utama (editor Sora bukan thread-safe); pemindaian dan parsing di thread latar.
        val overrides = HashMap<String, String>()
        val openFiles = ArrayList<java.io.File>()
        for (t in session.tabs) {
            val f = t.file ?: continue
            if (!Outline.supportsCode(f.name)) continue
            openFiles.add(f)
            t.currentTextOrNull(SymbolIndex.MAX_BYTES.toInt())?.let { overrides[f.path] = it }
        }
        result = withContext(Dispatchers.IO) {
            val files = (PaletteScan.scan(root) + openFiles).distinctBy { it.path }
            session.symbolIndex.build(files, overrides) { isActive }
        }
        indexing = false
    }

    val shown = remember(result, query, onlyThisFile, activeFile) {
        val r = result ?: return@remember emptyList<SymbolEntry>()
        val pool = if (onlyThisFile && activeFile != null) r.entries.filter { it.file.path == activeFile.path } else r.entries
        SymbolSearch.rank(pool, query, 80, activeFile)
    }
    val r = result
    val q = query.trim()
    val status = when {
        indexing && r == null -> "Mengindeks\u2026"
        r == null -> ""
        q.isEmpty() -> "Ketik nama kelas atau fungsi. " + r.entries.size + " simbol di " + r.files + " berkas" +
            (if (r.capped) " (dibatasi " + SymbolIndex.MAX_ENTRIES + ")" else "") + "."
        else -> shown.size.toString() + " cocok dari " + r.entries.size + " simbol"
    }

    fun go(e: SymbolEntry) {
        session.openLocation(e.file.path, e.line + 1, 1)
        onDismiss()
    }

    val shape = RoundedCornerShape(CodeDimens.DialogCorner)
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).win7DialogFrame(shape)) {
            BasicText(
                text = "Pergi ke simbol",
                modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                style = TextStyle(color = CodeColors.IconDefault, fontSize = CodeDimens.DialogTitleSize)
            )
            BasicTextField(
                value = query,
                onValueChange = { query = it.take(80) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).padding(horizontal = 16.dp, vertical = 12.dp),
                singleLine = true,
                textStyle = TextStyle(color = CodeColors.TextPrimary, fontSize = CodeDimens.FindTextSize),
                cursorBrush = SolidColor(CodeColors.Gold),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { shown.firstOrNull()?.let { go(it) } }),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            BasicText(
                                text = "Nama kelas atau fungsi",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(color = CodeColors.IconDefault.copy(alpha = 0.5f), fontSize = CodeDimens.FindTextSize)
                            )
                        }
                        inner()
                    }
                }
            )
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                FifToggle("Berkas ini", onlyThisFile) { if (activeFile != null) onlyThisFile = !onlyThisFile }
                Spacer(Modifier.width(12.dp))
                BasicText(
                    text = status,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 12.sp)
                )
            }
            Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(CodeDimens.Hairline).background(CodeColors.Hairline))
            if (shown.isEmpty()) {
                BasicText(
                    text = when {
                        r == null -> "Mengindeks folder\u2026"
                        q.isEmpty() -> "Tidak ada simbol di berkas aktif. Ketik untuk mencari di seluruh folder."
                        else -> "Tidak ada simbol untuk \"$q\". Didukung: kt, kts, java, py, js/ts, sh."
                    },
                    modifier = Modifier.padding(16.dp),
                    style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 13.sp)
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxH)) {
                    items(shown) { e ->
                        val rel = e.file.path.removePrefix(root.path).trimStart('/')
                        Column(Modifier.fillMaxWidth().clickable { go(e) }.padding(horizontal = 16.dp, vertical = 7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BasicText(
                                    text = e.kind,
                                    modifier = Modifier.width(44.dp),
                                    maxLines = 1,
                                    style = TextStyle(color = CodeColors.Gold.copy(alpha = 0.75f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                )
                                BasicText(
                                    text = e.name,
                                    modifier = Modifier.weight(1f, fill = false),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(color = CodeColors.IconDefault, fontSize = 14.sp)
                                )
                                if (e.container.isNotEmpty()) {
                                    Spacer(Modifier.width(8.dp))
                                    BasicText(
                                        text = "di " + e.container,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 11.sp)
                                    )
                                }
                            }
                            BasicText(
                                text = rel + ":" + (e.line + 1),
                                modifier = Modifier.padding(start = 44.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                }
            }
        }
    }
}
