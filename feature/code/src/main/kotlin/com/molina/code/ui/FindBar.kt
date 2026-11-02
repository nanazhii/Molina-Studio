package com.molina.code.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import java.util.regex.PatternSyntaxException

/**
 * Find bar (F3c, blueprint bagian 15): tepat di atas toolbar. Baris 1 = field Cari, penghitung
 * (3/12), panah sebelumnya/berikutnya, OPSI, tutup. Baris 2 (bila mode Ganti aktif) = field Ganti
 * + GANTI + SEMUA. Penyorotan hasil digambar editor (MATCHED_TEXT_BACKGROUND, emas 35%).
 */
@Composable
internal fun FindBar(session: CodeSession, tab: EditorTab) {
    val find = session.find
    val ed = tab.editor
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var optionsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(find.focusTick) { runCatching { focus.requestFocus() } }
    LaunchedEffect(ed, find.query, find.matchCase, find.wholeWord, find.useRegex, find.wrapAround) {
        runSearch(ed, find)
    }
    DisposableEffect(ed) { onDispose { stopSearchKeepSelection(ed) } }

    val (counter, bad) = counterOf(ed, find, tab.findRevision)
    val canStep = ed != null && find.query.isNotEmpty() && !find.regexError

    fun step(next: Boolean) {
        val s = ed?.searcher ?: return
        if (!s.hasQuery()) return
        try {
            if (next) s.gotoNext() else s.gotoPrevious()
            ed?.flashCurrentLine(700L)
        } catch (e: IllegalStateException) {
            // pencarian dihentikan di tengah jalan; abaikan
        }
    }

    Column(Modifier.fillMaxWidth().background(CodeColors.Surface2)) {
        Box(Modifier.fillMaxWidth().height(CodeDimens.Hairline).background(CodeColors.Hairline))
        Row(
            Modifier.fillMaxWidth().height(CodeDimens.FindRowHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FindField(
                value = find.query,
                onChange = { find.query = it },
                hint = "Cari",
                underline = if (focused) CodeColors.Gold else CodeColors.Hairline,
                imeAction = ImeAction.Search,
                onAction = { step(true) },
                fieldModifier = Modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused }
            )
            if (counter.isNotEmpty()) {
                BasicText(
                    text = counter,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    style = TextStyle(
                        color = if (bad) CodeColors.Danger else CodeColors.SecondaryChampagne,
                        fontSize = 12.sp
                    )
                )
            }
            BarButton("\u25B2", enabled = canStep) { step(false) }
            BarButton("\u25BC", enabled = canStep) { step(true) }
            BarButton("OPSI", width = 48.dp) { optionsOpen = true }
            BarButton("\u2715", color = CodeColors.Danger) { find.open = false }
        }
        if (find.replaceMode) {
            val canReplace = canStep && !tab.readOnly
            Row(
                Modifier.fillMaxWidth().height(CodeDimens.FindRowHeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FindField(
                    value = find.replaceText,
                    onChange = { find.replaceText = it },
                    hint = "Ganti",
                    underline = CodeColors.Hairline,
                    imeAction = ImeAction.Done,
                    onAction = {},
                    fieldModifier = Modifier
                )
                BarButton("GANTI", width = 56.dp, color = CodeColors.Gold, enabled = canReplace) {
                    try {
                        ed?.searcher?.replaceCurrentMatch(find.replaceText)
                    } catch (e: IllegalStateException) {
                        // tidak ada hasil terpilih
                    }
                }
                BarButton("SEMUA", width = 56.dp, color = CodeColors.Gold, enabled = canReplace) {
                    try {
                        ed?.searcher?.replaceAll(find.replaceText)
                    } catch (e: IllegalStateException) {
                        // pencarian dihentikan
                    }
                }
            }
        }
    }

    if (optionsOpen) FindOptionsDialog(find) { optionsOpen = false }
}

private fun runSearch(ed: CodeEditor?, find: FindState) {
    val s = ed?.searcher ?: return
    if (find.query.isEmpty()) {
        if (s.hasQuery()) s.stopSearch()
        find.regexError = false
        return
    }
    try {
        s.setCyclicJumping(find.wrapAround)
        val type = when {
            find.useRegex -> EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION
            find.wholeWord -> EditorSearcher.SearchOptions.TYPE_WHOLE_WORD
            else -> EditorSearcher.SearchOptions.TYPE_NORMAL
        }
        s.search(find.query, EditorSearcher.SearchOptions(type, !find.matchCase))
        find.regexError = false
    } catch (e: PatternSyntaxException) {
        find.regexError = true
        if (s.hasQuery()) s.stopSearch()
    }
}

/**
 * Menutup find bar menghentikan pencarian, tetapi kata hasil terakhir tetap terblok (warna Claude):
 * rentang seleksi dicatat sebelum stopSearch lalu dipasang lagi sesudahnya.
 */
private fun stopSearchKeepSelection(ed: CodeEditor?) {
    if (ed == null) return
    val c = ed.cursor
    val selected = c.isSelected
    val ll = c.leftLine
    val lc = c.leftColumn
    val rl = c.rightLine
    val rc = c.rightColumn
    stopSearch(ed)
    if (selected) runCatching { ed.setSelectionRegion(ll, lc, rl, rc) }
}

private fun stopSearch(ed: CodeEditor?) {
    val s = ed?.searcher ?: return
    if (s.hasQuery()) s.stopSearch()
}

/** Teks penghitung dan penanda "buruk" (nol hasil atau regex salah). [rev] hanya memicu hitung ulang. */
private fun counterOf(ed: CodeEditor?, find: FindState, rev: Int): Pair<String, Boolean> {
    if (find.regexError) return "Regex salah" to true
    if (ed == null || find.query.isEmpty() || rev < 0) return "" to false
    return try {
        val s = ed.searcher
        val count = s.matchedPositionCount
        val index = s.currentMatchedPositionIndex
        val text = when {
            count == 0 -> "0/0"
            index >= 0 -> "${index + 1}/$count"
            else -> "$count"
        }
        text to (count == 0)
    } catch (e: IllegalStateException) {
        "" to false
    }
}

@Composable
private fun RowScope.FindField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    underline: Color,
    imeAction: ImeAction,
    onAction: () -> Unit,
    fieldModifier: Modifier
) {
    Box(
        Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = fieldModifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(color = CodeColors.TextPrimary, fontSize = CodeDimens.FindTextSize),
            cursorBrush = SolidColor(CodeColors.Gold),
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(onSearch = { onAction() }, onDone = { onAction() }),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        BasicText(
                            text = hint,
                            style = TextStyle(color = CodeColors.IconDim, fontSize = CodeDimens.FindTextSize)
                        )
                    }
                    inner()
                }
            }
        )
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(CodeDimens.FindUnderline)
                .background(underline)
        )
    }
}

@Composable
private fun BarButton(
    label: String,
    width: Dp = CodeDimens.FindButtonWidth,
    color: Color = CodeColors.IconDefault,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        Modifier.width(width).fillMaxHeight().clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            style = TextStyle(color = if (enabled) color else CodeColors.IconDisabled, fontSize = 13.sp)
        )
    }
}

/** Dialog "Opsi pencarian" (blueprint 15.2-15.3). Perubahan baru berlaku saat TERAPKAN. */
@Composable
private fun FindOptionsDialog(find: FindState, onDismiss: () -> Unit) {
    var matchCase by remember { mutableStateOf(find.matchCase) }
    var whole by remember { mutableStateOf(find.wholeWord) }
    var regex by remember { mutableStateOf(find.useRegex) }
    var wrap by remember { mutableStateOf(find.wrapAround) }
    var replace by remember { mutableStateOf(find.replaceMode) }
    val shape = RoundedCornerShape(CodeDimens.DialogCorner)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .win7DialogFrame(shape)
                .padding(20.dp)
        ) {
            BasicText(
                text = "Opsi pencarian",
                style = TextStyle(color = CodeColors.IconDefault, fontSize = CodeDimens.DialogTitleSize)
            )
            Spacer(Modifier.height(8.dp))
            OptionRow("Sensitif huruf besar/kecil", matchCase) { matchCase = it }
            OptionRow("Blok (kata utuh)", whole) { whole = it }
            OptionRow("Persamaan reguler", regex) { regex = it }
            OptionRow("Membungkus di sekitar", wrap) { wrap = it }
            OptionRow("Ganti teks", replace) { replace = it }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BasicText(
                    text = "BATAL",
                    modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp),
                    style = TextStyle(color = CodeColors.LabelChampagne, fontSize = 14.sp)
                )
                BasicText(
                    text = "TERAPKAN",
                    modifier = Modifier
                        .clickable {
                            find.matchCase = matchCase
                            find.wholeWord = whole
                            find.useRegex = regex
                            find.wrapAround = wrap
                            find.replaceMode = replace
                            onDismiss()
                        }
                        .padding(12.dp),
                    style = TextStyle(color = CodeColors.Gold, fontSize = 14.sp)
                )
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(CodeDimens.MenuRowHeight)
            .clickable { onChange(!on) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        CheckMark(on)
        Spacer(Modifier.width(12.dp))
        BasicText(
            text = label,
            style = TextStyle(color = CodeColors.IconDefault, fontSize = CodeDimens.MenuTextSize)
        )
    }
}

/** Dialog "Pergi ke baris": nomor baris 1..jumlah baris, lalu kursor pindah ke awal baris itu. */
@Composable
internal fun GotoLineDialog(tab: EditorTab, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val ed = tab.editor
    val total = ed?.text?.lineCount ?: 1
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val shape = RoundedCornerShape(CodeDimens.DialogCorner)

    fun go() {
        val parts = text.split(':', ',', '.', ' ').filter { it.isNotEmpty() }
        val n = parts.getOrNull(0)?.toIntOrNull()
        val col = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (ed == null || n == null || n < 1 || n > total) {
            Toast.makeText(ctx, "Masukkan baris 1-$total", Toast.LENGTH_SHORT).show()
        } else {
            ed.jumpToLineHighlighted(n - 1, col)
            onDismiss()
        }
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .win7DialogFrame(shape)
                .padding(20.dp)
        ) {
            BasicText(
                text = "Pergi ke baris",
                style = TextStyle(color = CodeColors.IconDefault, fontSize = CodeDimens.DialogTitleSize)
            )
            Spacer(Modifier.height(16.dp))
            BasicTextField(
                value = text,
                onValueChange = { v ->
                    text = v.filter { c -> c.isDigit() || c == ':' || c == ',' || c == '.' || c == ' ' }.take(18)
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                singleLine = true,
                textStyle = TextStyle(color = CodeColors.TextPrimary, fontSize = CodeDimens.FindTextSize),
                cursorBrush = SolidColor(CodeColors.Gold),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) {
                            BasicText(
                                text = "Baris[:kolom] 1-$total (kini ${(ed?.cursor?.leftLine ?: 0) + 1})",
                            maxLines = 1,
                                style = TextStyle(color = CodeColors.IconDim, fontSize = CodeDimens.FindTextSize)
                            )
                        }
                        inner()
                    }
                }
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(CodeDimens.FindUnderline)
                    .background(CodeColors.Gold)
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BasicText(
                    text = "BATAL",
                    modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp),
                    style = TextStyle(color = CodeColors.LabelChampagne, fontSize = 14.sp)
                )
                BasicText(
                    text = "PERGI",
                    modifier = Modifier.clickable { go() }.padding(12.dp),
                    style = TextStyle(color = CodeColors.Gold, fontSize = 14.sp)
                )
            }
        }
    }
}
