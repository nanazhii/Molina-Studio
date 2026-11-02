package com.molina.code.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

private val KNOWN_EXTS = listOf(
    "txt", "md", "kt", "py", "sh", "json", "xml", "html", "css", "js",
    "c", "cpp", "java", "yml", "gradle", "toml", "ini", "log", "csv"
)

private const val MAX_SUGGESTIONS = 5
private const val MAX_EXT_LENGTH = 16

/**
 * Dialog "Simpan sebagai" (F3e, blueprint bagian 20.3): nama berkas + titik + ekstensi dengan
 * saran, Enkode terkunci UTF-8, konfirmasi timpa. Folder tujuan = [CodeSession.defaultSaveDir];
 * pemilih folder lewat tombol UBAH (F4).
 */
@Composable
internal fun SaveAsDialog(session: CodeSession, req: SaveAsRequest) {
    val ctx = LocalContext.current
    val focusManager = LocalFocusManager.current
    val tab = req.tab
    var dir by remember { mutableStateOf(session.defaultSaveDir(tab)) }
    var pickerOpen by remember { mutableStateOf(false) }
    val initial = tab.file?.name

    var name by remember { mutableStateOf(initial?.substringBeforeLast('.') ?: "") }
    var ext by remember { mutableStateOf(initial?.substringAfterLast('.', "") ?: "txt") }
    var extFocused by remember { mutableStateOf(false) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val nameFocus = remember { FocusRequester() }
    val shape = RoundedCornerShape(CodeDimens.DialogCorner)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp

    val suggestions = run {
        val q = ext.trim().trimStart('.')
        KNOWN_EXTS
            .filter { q.isEmpty() || it.startsWith(q, ignoreCase = true) }
            .filter { !it.equals(q, ignoreCase = true) }
            .take(MAX_SUGGESTIONS)
    }

    fun submit() {
        val err = FileStore.validate(name, ext)
        if (err != null) {
            error = err
            return
        }
        val target = File(dir, FileStore.fullName(name, ext))
        if (target.isDirectory) {
            error = "Sudah ada folder bernama itu"
            return
        }
        val holder = session.tabFor(target)
        if (holder != null && holder !== tab) {
            error = "Berkas itu sedang terbuka di tab lain"
            return
        }
        if (target.exists() && target != tab.file && !confirmOverwrite) {
            confirmOverwrite = true
            error = null
            return
        }
        session.commitSaveAs(req, target)
    }

    LaunchedEffect(Unit) { runCatching { nameFocus.requestFocus() } }

    Dialog(
        onDismissRequest = { session.cancelSaveAs() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 360.dp)
                .heightIn(max = maxHeight)
                .win7DialogFrame(shape)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            BasicText(
                text = "Simpan sebagai",
                style = TextStyle(color = CodeColors.Gold, fontSize = CodeDimens.DialogTitleSize)
            )
            Spacer(Modifier.height(16.dp))

            Label("Folder:")
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    text = dir.path,
                    modifier = Modifier.weight(1f),
                    style = TextStyle(color = CodeColors.TextSecondary, fontSize = 13.sp)
                )
                BasicText(
                    text = "UBAH",
                    modifier = Modifier.clickable { pickerOpen = true }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                    style = TextStyle(color = CodeColors.Gold, fontSize = 13.sp)
                )
            }
            Spacer(Modifier.height(16.dp))

            Label("Nama Berkas:")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                UnderlineField(
                    value = name,
                    onChange = { v ->
                        name = v.replace("\n", "")
                        error = null
                        confirmOverwrite = false
                    },
                    hint = "nama",
                    modifier = Modifier.weight(1f).focusRequester(nameFocus),
                    imeAction = ImeAction.Next,
                    onAction = { focusManager.moveFocus(FocusDirection.Next) },
                    onFocus = {}
                )
                BasicText(
                    text = ".",
                    modifier = Modifier.padding(horizontal = 6.dp),
                    style = TextStyle(color = CodeColors.TextPrimary, fontSize = CodeDimens.FindTextSize)
                )
                UnderlineField(
                    value = ext,
                    onChange = { v ->
                        ext = v.filter { it != ' ' && it != '\n' }.take(MAX_EXT_LENGTH)
                        error = null
                        confirmOverwrite = false
                    },
                    hint = "ext",
                    modifier = Modifier.width(88.dp),
                    imeAction = ImeAction.Done,
                    onAction = { submit() },
                    onFocus = { extFocused = it }
                )
            }

            if (extFocused && suggestions.isNotEmpty()) {
                Column(
                    Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .background(CodeColors.SurfaceMenu, RoundedCornerShape(8.dp))
                        .border(CodeDimens.Hairline, CodeColors.Hairline, RoundedCornerShape(8.dp))
                ) {
                    val q = ext.trim().trimStart('.')
                    suggestions.forEach { s ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(CodeDimens.SuggestionRowHeight)
                                .clickable {
                                    ext = s
                                    error = null
                                    confirmOverwrite = false
                                }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            BasicText(
                                text = buildAnnotatedString {
                                    withStyle(SpanStyle(color = CodeColors.Gold)) { append(s.take(q.length)) }
                                    withStyle(SpanStyle(color = CodeColors.IconDefault)) { append(s.drop(q.length)) }
                                },
                                style = TextStyle(fontSize = 14.sp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Label("Enkode:")
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(CodeDimens.SuggestionRowHeight)
                    .clickable {
                        Toast.makeText(ctx, "Molina selalu menyimpan dalam UTF-8", Toast.LENGTH_SHORT).show()
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    text = "UTF-8",
                    modifier = Modifier.weight(1f),
                    style = TextStyle(color = CodeColors.TextPrimary, fontSize = CodeDimens.FindTextSize)
                )
                Canvas(Modifier.size(16.dp)) { drawToolbarIcon(ToolbarIcon.Lock, CodeColors.IconDim, false) }
            }

            val shownError = error
            if (shownError != null) {
                Spacer(Modifier.height(4.dp))
                BasicText(
                    text = shownError,
                    style = TextStyle(color = CodeColors.Danger, fontSize = 13.sp)
                )
            } else if (confirmOverwrite) {
                Spacer(Modifier.height(4.dp))
                BasicText(
                    text = "${FileStore.fullName(name, ext)} sudah ada. Timpa?",
                    style = TextStyle(color = CodeColors.GoldSoft, fontSize = 13.sp)
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BasicText(
                    text = "BATAL",
                    modifier = Modifier.clickable { session.cancelSaveAs() }.padding(12.dp),
                    style = TextStyle(color = CodeColors.LabelChampagne, fontSize = 14.sp)
                )
                BasicText(
                    text = if (confirmOverwrite) "TIMPA" else "SIMPAN",
                    modifier = Modifier.clickable { submit() }.padding(12.dp),
                    style = TextStyle(color = CodeColors.Gold, fontSize = 14.sp)
                )
            }
        }
    }

    if (pickerOpen) {
        PickerDialog(
            mode = PickMode.Folder,
            start = dir,
            title = "Pilih folder",
            onPick = {
                dir = it
                pickerOpen = false
                error = null
                confirmOverwrite = false
            },
            onDismiss = { pickerOpen = false }
        )
    }
}

@Composable
internal fun Label(text: String) {
    BasicText(
        text = text,
        style = TextStyle(color = CodeColors.LabelChampagne, fontSize = 13.sp)
    )
}

/** Field satu baris dengan garis bawah: hairline saat idle, emas saat fokus. */
@Composable
internal fun UnderlineField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier,
    imeAction: ImeAction,
    onAction: () -> Unit,
    onFocus: (Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    focused = it.isFocused
                    onFocus(it.isFocused)
                },
            singleLine = true,
            textStyle = TextStyle(color = CodeColors.TextPrimary, fontSize = CodeDimens.FindTextSize),
            cursorBrush = SolidColor(CodeColors.Gold),
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(onNext = { onAction() }, onDone = { onAction() }),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.CenterStart) {
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
                .padding(top = 4.dp)
                .fillMaxWidth()
                .height(if (focused) CodeDimens.FindUnderline else CodeDimens.Hairline)
                .background(if (focused) CodeColors.Gold else CodeColors.Hairline)
        )
    }
}
