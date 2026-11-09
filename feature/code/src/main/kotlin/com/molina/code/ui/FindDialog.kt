package com.molina.code.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * [FIND-DIALOG] Layar isian Cari: latar hitam pekat, bingkai putih polos. Berisi Cari teks, Ganti dengan,
 * opsi, dan tombol GANTI SEMUA / GANTI / CARI. Setelah aksi, dialog menutup dan find bar (penghitung,
 * panah sebelumnya/berikutnya) tetap dipakai untuk menelusuri hasil. Blok hasil digambar merah oleh editor.
 */
private enum class FindAction { FIND, REPLACE_ONE, REPLACE_ALL }

@Composable
internal fun FindDialog(session: CodeSession, tab: EditorTab) {
    val find = session.find
    val ed = tab.editor
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var query by remember { mutableStateOf(find.query) }
    var replace by remember { mutableStateOf(find.replaceText) }
    var matchCase by remember { mutableStateOf(find.matchCase) }
    var whole by remember { mutableStateOf(find.wholeWord) }
    var regex by remember { mutableStateOf(find.useRegex) }
    var wrap by remember { mutableStateOf(find.wrapAround) }
    var keep by remember { mutableStateOf(find.keepHighlight) } // [FIND-PIN]
    var queryFocused by remember { mutableStateOf(false) }
    var replaceFocused by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    val canSearch = query.isNotEmpty() && !busy && ed != null
    val canReplace = canSearch && !tab.readOnly

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun close() {
        // [FIND-PIN] centang dilepas saat sorotan sedang ditahan: sorotan dihapus sekarang
        find.keepHighlight = keep
        if (!keep && find.pinned && !find.open) {
            stopSearchKeepSelection(ed)
            find.pinned = false
        }
        find.dialogOpen = false
    }

    fun applyFind(action: FindAction) {
        val s = ed?.searcher
        // GANTI pada hasil yang sudah aktif dan tidak berubah: ganti hasil terpilih saja.
        val sameSearch = s != null && s.hasQuery() && query == find.query && matchCase == find.matchCase &&
            whole == find.wholeWord && regex == find.useRegex
        find.query = query
        find.replaceText = replace
        find.matchCase = matchCase
        find.wholeWord = whole
        find.useRegex = regex
        find.wrapAround = wrap
        find.replaceMode = replace.isNotEmpty()
        find.open = true
        find.keepHighlight = keep // [FIND-PIN]
        find.pinned = false
        val replaceCurrent = action == FindAction.REPLACE_ONE && sameSearch
        if (!replaceCurrent) runSearch(ed, find)
        if (find.regexError) {
            Toast.makeText(ctx, "Persamaan reguler salah", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        scope.launch {
            val n = if (replaceCurrent) 1 else awaitMatches(ed)
            if (n == 0) {
                Toast.makeText(ctx, "Tidak ditemukan", Toast.LENGTH_SHORT).show()
                busy = false
                return@launch
            }
            try {
                when (action) {
                    FindAction.FIND -> { ed?.searcher?.gotoNext(); ed?.flashCurrentLine(700L) }
                    FindAction.REPLACE_ONE ->
                        if (replaceCurrent) ed?.searcher?.replaceCurrentMatch(replace)
                        else { ed?.searcher?.gotoNext(); ed?.flashCurrentLine(700L) }
                    FindAction.REPLACE_ALL -> {
                        ed?.searcher?.replaceAll(replace)
                        Toast.makeText(ctx, "$n diganti", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: IllegalStateException) {
                // pencarian dihentikan di tengah jalan; abaikan
            }
            close()
        }
    }

    Dialog(onDismissRequest = { close() }) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(Color.Black, shape)
                .border(1.dp, Color.White, shape)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            BasicText("Cari", style = TextStyle(color = Color.White, fontSize = CodeDimens.DialogTitleSize))
            Spacer(Modifier.height(14.dp))
            FieldLabel("Cari teks:")
            DialogField(
                value = query, onChange = { query = it },
                underline = if (queryFocused) CodeColors.Brand else CodeColors.IconDim,
                imeAction = ImeAction.Search,
                onAction = { if (canSearch) applyFind(FindAction.FIND) },
                fieldModifier = Modifier.focusRequester(focus).onFocusChanged { queryFocused = it.isFocused }
            )
            Spacer(Modifier.height(14.dp))
            FieldLabel("Ganti dengan:")
            DialogField(
                value = replace, onChange = { replace = it },
                underline = if (replaceFocused) CodeColors.Brand else CodeColors.IconDim,
                imeAction = ImeAction.Done,
                onAction = {},
                fieldModifier = Modifier.onFocusChanged { replaceFocused = it.isFocused }
            )
            Spacer(Modifier.height(8.dp))
            DialogCheck("Kasus sensitif", matchCase) { matchCase = it }
            DialogCheck("Kata utuh", whole) { whole = it }
            DialogCheck("Persamaan reguler", regex) { regex = it }
            DialogCheck("Membungkus di sekitar", wrap) { wrap = it }
            DialogCheck("Tetap sorot setelah ditutup", keep) { keep = it } // [FIND-PIN]
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Win7DialogButton(
                    "GANTI SEMUA", { if (canReplace) applyFind(FindAction.REPLACE_ALL) },
                    accent = if (canReplace) null else CodeColors.IconDisabled
                )
                Spacer(Modifier.weight(1f))
                Win7DialogButton(
                    "GANTI", { if (canReplace) applyFind(FindAction.REPLACE_ONE) },
                    accent = if (canReplace) null else CodeColors.IconDisabled
                )
                Win7DialogButton(
                    "CARI", { if (canSearch) applyFind(FindAction.FIND) }, isDefault = true,
                    accent = if (canSearch) null else CodeColors.IconDisabled
                )
            }
        }
    }
}

/** Menunggu hasil pencarian async editor siap (maks ~1,8 dtk). Mengembalikan jumlah hasil, 0 bila tidak ada. */
private suspend fun awaitMatches(ed: CodeEditor?): Int {
    val s = ed?.searcher ?: return 0
    repeat(30) {
        delay(60)
        val n = try { if (s.hasQuery()) s.matchedPositionCount else 0 } catch (e: IllegalStateException) { 0 }
        if (n > 0) return n
    }
    return 0
}

@Composable
private fun FieldLabel(text: String) {
    BasicText(text, style = TextStyle(color = Color.White, fontSize = 14.sp))
}

@Composable
private fun DialogField(
    value: String,
    onChange: (String) -> Unit,
    underline: Color,
    imeAction: ImeAction,
    onAction: () -> Unit,
    fieldModifier: Modifier
) {
    Column(Modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = fieldModifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(CodeColors.Brand),
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(onSearch = { onAction() }, onDone = { onAction() })
        )
        Box(Modifier.fillMaxWidth().height(CodeDimens.FindUnderline).background(underline))
    }
}

@Composable
private fun DialogCheck(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(CodeDimens.MenuRowHeight).clickable { onChange(!on) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        CheckMark(on)
        Spacer(Modifier.width(12.dp))
        BasicText(label, style = TextStyle(color = Color.White, fontSize = CodeDimens.MenuTextSize))
    }
}
