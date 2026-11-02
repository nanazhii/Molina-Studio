package com.molina.code.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.molina.code.preview.CommandBlockSpec
import com.molina.code.preview.PythonTerminalRun
import com.molina.code.preview.TerminalRunLang

private enum class PyRunMode { HEREDOC, FILE }

private const val MAX_HEREDOC_CHARS = 20_000

/**
 * [CODE-PYRUN2] Dialog "Jalankan di Terminal" untuk .py dan .sh. Opsi 1: bungkus isi dengan heredoc (tanpa berkas).
 * Opsi 2: interpreter + nama berkas; pengguna cukup mengisi nama, ekstensi dideteksi/ditambah otomatis.
 * Pratinjau perintah (CommandBlockSpec, seragam dengan layar :app) selalu tampil; pilihan terakhir diingat dan bisa dijadikan "langsung" tanpa dialog.
 */
@Composable
internal fun PythonRunDialog(session: CodeSession, tab: EditorTab, onDismiss: () -> Unit) {
    val lang = remember { session.runLang() ?: TerminalRunLang.PY }
    var mode by remember { mutableStateOf(if (session.runSavedFile()) PyRunMode.FILE else PyRunMode.HEREDOC) }
    var name by remember { mutableStateOf(PythonTerminalRun.suggestStem(tab.file?.name ?: tab.title, lang)) }
    var dontAsk by remember { mutableStateOf(!session.runAsk()) }
    val focus = remember { FocusRequester() }
    val shape = RoundedCornerShape(CodeDimens.DialogCorner)
    val hasSelection = remember { session.scriptHasSelection() }
    val source = remember { session.scriptSourceText() }

    val parsed = PythonTerminalRun.normalizeName(name, lang)
    val heredoc = PythonTerminalRun.heredoc(source, lang)
    val target = session.scriptTarget(tab, parsed.name)
    val overwrite = mode == PyRunMode.FILE && target != null && target.exists() && target != tab.file

    val tooLong = mode == PyRunMode.HEREDOC && heredoc != null && heredoc.length > MAX_HEREDOC_CHARS
    val canRun = when (mode) {
        PyRunMode.HEREDOC -> heredoc != null && !tooLong
        PyRunMode.FILE -> parsed.name != null
    }
    val preview = when (mode) {
        PyRunMode.HEREDOC -> heredoc?.let { PythonTerminalRun.preview(it) } ?: "(isi kosong)"
        PyRunMode.FILE -> lang.interpreter + " " + (parsed.name ?: ("nama" + lang.ext))
    }

    fun run() {
        if (!canRun) return
        session.saveRunChoice(mode == PyRunMode.FILE, !dontAsk)
        if (mode == PyRunMode.HEREDOC) session.runScriptHeredoc(lang) else session.runScriptFile(name, lang)
        onDismiss()
    }

    LaunchedEffect(mode) { if (mode == PyRunMode.FILE) runCatching { focus.requestFocus() } }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .win7DialogFrame(shape)
                .padding(20.dp)
        ) {
            BasicText("Jalankan ${lang.label} di Terminal", style = TextStyle(color = CodeColors.Gold, fontSize = CodeDimens.DialogTitleSize))
            Spacer(Modifier.height(8.dp))

            PyOption(
                selected = mode == PyRunMode.HEREDOC,
                title = "Heredoc: ${lang.interpreter} << '${lang.delimiter}'",
                subtitle = (if (hasSelection) "Hanya seleksi" else "Seluruh isi tab") + " dikirim apa adanya; tanpa berkas. " + (if (lang == TerminalRunLang.PY) "input()" else "read") + " dari keyboard tidak berfungsi.",
                onClick = { mode = PyRunMode.HEREDOC }
            )
            PyOption(
                selected = mode == PyRunMode.FILE,
                title = "Berkas: ${lang.interpreter} nama${lang.ext}",
                subtitle = "Isi tab ditulis ke berkas lalu dijalankan. Berkas lama dicadangkan dan bisa diurungkan.",
                onClick = { mode = PyRunMode.FILE }
            )

            if (mode == PyRunMode.FILE) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it.take(PythonTerminalRun.MAX_NAME + lang.ext.length) },
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        singleLine = true,
                        textStyle = TextStyle(color = CodeColors.TextPrimary, fontSize = CodeDimens.FindTextSize),
                        cursorBrush = SolidColor(CodeColors.Gold),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { run() })
                    )
                    BasicText(
                        text = if (name.endsWith(lang.ext, ignoreCase = true)) "" else lang.ext,
                        style = TextStyle(color = CodeColors.IconDim, fontSize = CodeDimens.FindTextSize)
                    )
                }
                Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(CodeDimens.FindUnderline).background(CodeColors.Gold))
                if (hasSelection) PyNote("Seleksi diabaikan; seluruh isi tab ditulis ke berkas.", CodeColors.LabelChampagne)
                parsed.error?.let { if (name.isNotBlank()) PyNote(it, CodeColors.Danger) }
                if (overwrite) PyNote(parsed.name + " sudah ada dan akan ditimpa (bisa diurungkan).", CodeColors.Danger)
            }
            if (tooLong) PyNote("Isi terlalu panjang untuk heredoc (maks. 20.000 karakter). Pakai opsi Berkas.", CodeColors.Danger)

            PyCheck(dontAsk, "Ingat pilihan ini, langsung jalankan lain kali") { dontAsk = !dontAsk }

            Spacer(Modifier.height(10.dp))
            BasicText(
                text = preview,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CodeColors.IconDim.copy(alpha = 0.15f), RoundedCornerShape(CommandBlockSpec.CORNER_DP.dp))
                    .border(CommandBlockSpec.BORDER_DP.dp, CodeColors.IconDim.copy(alpha = 0.35f), RoundedCornerShape(CommandBlockSpec.CORNER_DP.dp))
                    .padding(CommandBlockSpec.PADDING_DP.dp),
                maxLines = CommandBlockSpec.PREVIEW_MAX_LINES + 1,
                style = TextStyle(
                    color = CodeColors.TextPrimary,
                    fontSize = CommandBlockSpec.FONT_SP.sp,
                    lineHeight = CommandBlockSpec.LINE_HEIGHT_SP.sp,
                    fontFamily = FontFamily.Monospace
                )
            )

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BasicText(
                    text = "BATAL",
                    modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp),
                    style = TextStyle(color = CodeColors.LabelChampagne, fontSize = 14.sp)
                )
                BasicText(
                    text = if (overwrite) "TIMPA & JALANKAN" else "JALANKAN",
                    modifier = Modifier.clickable(enabled = canRun, onClick = { run() }).padding(12.dp),
                    style = TextStyle(color = if (canRun) CodeColors.Gold else CodeColors.IconDim, fontSize = 14.sp)
                )
            }
        }
    }
}

@Composable
private fun PyOption(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier.padding(top = 2.dp).size(18.dp).border(1.5.dp, if (selected) CodeColors.Gold else CodeColors.IconDim, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Box(Modifier.size(9.dp).background(CodeColors.Gold, CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(title, style = TextStyle(color = CodeColors.TextPrimary, fontSize = 14.sp))
            BasicText(subtitle, style = TextStyle(color = CodeColors.LabelChampagne, fontSize = 12.sp))
        }
    }
}

@Composable
private fun PyNote(text: String, color: androidx.compose.ui.graphics.Color) {
    BasicText(text, modifier = Modifier.padding(top = 6.dp), style = TextStyle(color = color, fontSize = 12.sp))
}

@Composable
private fun PyCheck(on: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(16.dp).border(1.5.dp, if (on) CodeColors.Gold else CodeColors.IconDim, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (on) Box(Modifier.size(8.dp).background(CodeColors.Gold, RoundedCornerShape(2.dp)))
        }
        Spacer(Modifier.width(10.dp))
        BasicText(label, style = TextStyle(color = CodeColors.LabelChampagne, fontSize = 12.sp))
    }
}
