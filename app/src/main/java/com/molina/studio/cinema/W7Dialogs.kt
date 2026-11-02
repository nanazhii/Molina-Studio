package com.molina.studio.cinema

import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.composed
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.molina.core.common.TrashBin

// [SPLIT-DIALOGS] Dialog kecil Penjelajah: nama, kata sandi, konfirmasi hapus, info, plus tapToFocus; dipecah dari Win7OpenDialog.kt.

@Composable
internal fun W7NameDialog(
    title: String, initial: String, selectEnd: Int, okLabel: String,
    onCancel: () -> Unit, onOk: (String) -> Unit
) {
    var tf by remember(initial) { mutableStateOf(TextFieldValue(initial, TextRange(0, selectEnd.coerceIn(0, initial.length)))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(initial) { delay(150); runCatching { focus.requestFocus() } }
    W7Panel(title, onCancel) {
        BasicTextField(
            value = tf,
            onValueChange = { tf = it },
            singleLine = true,
            textStyle = TextStyle(color = Win7.Ink, fontSize = 14.sp),
            cursorBrush = SolidColor(Win7.Ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (tf.text.isNotBlank()) onOk(tf.text) }),
            modifier = Modifier.fillMaxWidth().height(32.dp).background(Win7.Input)
                .border(1.dp, Win7.Field).padding(horizontal = 8.dp).focusRequester(focus),
            decorationBox = { inner -> Box(Modifier.fillMaxSize(), Alignment.CenterStart) { inner() } }
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Win7Button(okLabel, isDefault = true, enabled = tf.text.isNotBlank()) { onOk(tf.text) }
            Spacer(Modifier.width(8.dp))
            Win7Button("Batal", onClick = onCancel)
        }
    }
}

@Composable
internal fun W7SecretField( // [ARCHIVE2] internal: dipakai ArchiveCompressDialog
    value: String, onChange: (String) -> Unit, show: Boolean,
    modifier: Modifier, onDone: () -> Unit
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Win7.Ink, fontSize = 14.sp),
        cursorBrush = SolidColor(Win7.Ink),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.fillMaxWidth().height(32.dp).background(Win7.Input)
            .border(1.dp, Win7.Field).padding(horizontal = 8.dp),
        decorationBox = { inner -> Box(Modifier.fillMaxSize(), Alignment.CenterStart) { inner() } }
    )
}

/** [ZIP-AES] Dialog kata sandi: [confirmField] meminta ketik dua kali (membuat ZIP), tanpa itu satu kali (mengekstrak). */
@Composable
internal fun W7PasswordDialog(
    title: String, okLabel: String, confirmField: Boolean, note: String,
    onCancel: () -> Unit, onOk: (String) -> Unit
) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { focus.requestFocus() } }
    val valid = if (confirmField) pw.length >= 4 && pw == pw2 else pw.isNotEmpty()
    val hint = when {
        !confirmField -> ""
        pw.isNotEmpty() && pw.length < 4 -> "Minimal 4 karakter."
        pw2.isNotEmpty() && pw != pw2 -> "Kata sandi tidak sama."
        else -> ""
    }
    W7Panel(title, onCancel) {
        Text(note, color = Win7.TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Text("Kata sandi", color = Win7.Ink, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        W7SecretField(pw, { pw = it }, show, Modifier.focusRequester(focus)) { if (valid) onOk(pw) }
        if (confirmField) {
            Spacer(Modifier.height(8.dp))
            Text("Ulangi kata sandi", color = Win7.Ink, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            W7SecretField(pw2, { pw2 = it }, show, Modifier) { if (valid) onOk(pw) }
        }
        if (hint.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(hint, color = Color(0xFFFF8A80), fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().clickable { show = !show }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (show) "[x]" else "[  ]", color = Win7.Ink, fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Text("Tampilkan kata sandi", color = Win7.TextDim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Win7Button(okLabel, isDefault = true, enabled = valid) { onOk(pw) }
            Spacer(Modifier.width(8.dp))
            Win7Button("Batal", onClick = onCancel)
        }
    }
}

@Composable
internal fun W7ConfirmDialog(files: List<File>, permanent: Boolean, onCancel: () -> Unit, onOk: () -> Unit) {
    val hasDir = files.any { it.isDirectory }
    val verb = if (permanent) "Hapus permanen" else "Pindahkan ke Recycle Bin"
    W7Panel(if (permanent) "Hapus permanen" else "Recycle Bin", onCancel) {
        Text(
            if (files.size == 1) verb + ": \"" + files[0].name + "\"" + (if (hasDir) " beserta seluruh isinya?" else "?")
            else verb + ": " + files.size + " item terpilih" + (if (hasDir) " beserta isi folder?" else "?"),
            color = Win7.Ink, fontSize = 13.sp
        )
        if (files.size > 1) {
            Spacer(Modifier.height(6.dp))
            files.take(4).forEach { Text("\u2022 " + it.name, color = Win7.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (files.size > 4) Text("\u2022 \u2026 dan " + (files.size - 4) + " lainnya", color = Win7.TextDim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        if (permanent) Text("Penghapusan permanen tidak dapat dibatalkan.", color = Color(0xFFFF8A80), fontSize = 12.sp)
        else Text("Bisa dipulihkan dari Recycle Bin selama " + TrashBin.RETAIN_DAYS + " hari.", color = Win7.TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Win7Button(if (permanent) "Hapus permanen" else "Pindahkan", isDefault = true) { onOk() }
            Spacer(Modifier.width(8.dp))
            Win7Button("Batal", onClick = onCancel)
        }
    }
}

@Composable
internal fun W7InfoDialog(files: List<File>, debian: Boolean?, onDismiss: () -> Unit) {
    var ps by remember(files) { mutableStateOf<List<FileOps.Props>?>(null) }
    var failed by remember(files) { mutableStateOf(false) }
    LaunchedEffect(files) {
        val r = withContext(Dispatchers.IO) { runCatching { files.map { FileOps.props(it) } } }
        ps = r.getOrNull()
        failed = r.isFailure
    }
    val first = files.first()
    val shaCtx = LocalContext.current
    val shaScope = rememberCoroutineScope()
    val shaCancel = remember(files) { AtomicBoolean(false) }
    var sha by remember(files) { mutableStateOf<String?>(null) }
    var shaBusy by remember(files) { mutableStateOf(false) }
    var shaPct by remember(files) { mutableStateOf(0f) }
    var shaErr by remember(files) { mutableStateOf("") }
    DisposableEffect(files) { onDispose { shaCancel.set(true) } }
    W7Panel("Properti", onDismiss) {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            W7InfoRow("Nama", if (files.size == 1) first.name.ifEmpty { first.path } else files.size.toString() + " item")
            W7InfoRow("Lokasi", first.parent ?: "/")
            val x = ps
            if (x == null) {
                Text(
                    if (failed) "Properti tidak dapat dibaca." else "Menghitung ukuran\u2026",
                    color = Win7.TextDim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp)
                )
            } else if (x.size == 1) {
                val p = x[0]
                W7InfoRow("Jenis", if (p.symlink) "Tautan simbolik" else if (p.isDir) "Folder" else if (first.extension.isEmpty()) "Berkas" else "Berkas ." + first.extension.lowercase())
                W7InfoRow("Ukuran", FileOps.readable(p.size) + " (" + String.format(W7_ID, "%,d", p.size) + " byte)")
                if (p.isDir) W7InfoRow("Isi", p.files.toString() + " berkas, " + p.folders + " folder")
                W7InfoRow("Diubah", w7Date(p.modified))
                W7InfoRow("Izin", (if (p.canRead) "Baca" else "Tanpa baca") + ", " + (if (p.canWrite) "Tulis" else "Tanpa tulis"))
                if (!p.isDir && !p.symlink && W7Thumb.hasThumb(first)) {
                    val media by produceState<List<Pair<String, String>>?>(null, first.path) { value = W7MediaInfo.read(first) }
                    media?.forEach { W7InfoRow(it.first, it.second) }
                }
                if (!p.isDir && !p.symlink && W7Thumb.isVideo(first)) {
                    val side by produceState<List<Pair<String, String>>?>(null, first.path) { value = W7MediaInfo.sidecar(first) }
                    side?.forEach { W7InfoRow(it.first, it.second) }
                }
                if (!p.isDir && !p.symlink) {
                    val h = sha
                    if (h != null) {
                        W7InfoRow("SHA-256", h)
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                            Win7Button("Salin hash") {
                                val cm = shaCtx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("sha256", h))
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            if (shaErr.isNotEmpty()) Text(shaErr, color = Color(0xFFFF8A80), fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Win7Button(
                                if (shaBusy) "Menghitung " + (shaPct * 100).toInt() + "%" else "Hitung SHA-256",
                                enabled = !shaBusy
                            ) {
                                shaBusy = true
                                shaErr = ""
                                shaScope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        runCatching { FileOps.sha256(first, FileOps.Ctl(shaCancel) { pc -> shaPct = pc }) }
                                    }
                                    shaBusy = false
                                    r.onSuccess { sha = it }.onFailure { shaErr = it.message ?: "Gagal menghitung" }
                                }
                            }
                        }
                    }
                }
            } else {
                val bytes = x.fold(0L) { a, p -> a + p.size }
                val nFiles = x.fold(0) { a, p -> a + (if (p.isDir) p.files else 1) }
                val nDirs = x.fold(0) { a, p -> a + (if (p.isDir) p.folders + 1 else 0) }
                W7InfoRow("Ukuran", FileOps.readable(bytes) + " (" + String.format(W7_ID, "%,d", bytes) + " byte)")
                W7InfoRow("Isi", nFiles.toString() + " berkas, " + nDirs + " folder")
            }
            if (debian != null) W7InfoRow("Debian", if (debian) "Terlihat di Debian" else "Di luar Debian")
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Win7Button("Tutup", isDefault = true, onClick = onDismiss)
        }
    }
}

@Composable
internal fun W7InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Win7.TextDim, fontSize = 12.sp, modifier = Modifier.width(62.dp))
        Text(value, color = Win7.Ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

/**
 * [KB-TAP] Kolom teks tidak bisa menerima fokus otomatis (fokus awal jendela dialog); fokus baru
 * diizinkan setelah disentuh pengguna, dan dikunci lagi saat fokus lepas. Keyboard tidak muncul sendiri.
 */
internal fun Modifier.tapToFocus(): Modifier = composed {
    var armed by remember { mutableStateOf(false) }
    this
        .focusProperties { canFocus = armed }
        .onFocusChanged { if (!it.isFocused) armed = false }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                armed = true
            }
        }
}
