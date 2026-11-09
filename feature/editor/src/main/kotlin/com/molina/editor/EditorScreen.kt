package com.molina.editor

import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.component.Magnifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val AUTOSAVE_DELAY_MS = 1500L

/** State editor per berkas. Semua properti Compose-state dibaca UI; [eol] dan [loaded] hanya dibaca kode. */
@Stable
private class EditorHolder {
    var eol = Eol.LF
    var loaded = false
    var failed by mutableStateOf<String?>(null)
    var dirty by mutableStateOf(false)
    var rev by mutableIntStateOf(0)        // naik tiap edit pengguna: pemicu autosave (debounce)
    var uiRev by mutableIntStateOf(0)      // naik tiap perubahan konten: segarkan tombol undo/redo
    var findRev by mutableIntStateOf(0)    // naik tiap hasil cari/kursor berubah
    var line by mutableIntStateOf(1)
    var col by mutableIntStateOf(1)
    var message by mutableStateOf<String?>(null)
}

/**
 * [EDITOR-SCREEN] Editor teks Molina (Sora Editor) sebagai layar penuh di dalam tab Berkas.
 * Simpan otomatis 1,5 detik setelah edit berhenti, dan diselamatkan saat layar dibuang (pindah tab).
 * Tombol Kembali menyimpan lalu memanggil [onClose].
 */
@Composable
fun EditorScreen(
    file: File,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    typeface: Typeface = Typeface.MONOSPACE,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val holder = remember(file) { EditorHolder() }
    val editor = remember(file) { createEditor(ctx, typeface, holder) }
    var searchOpen by remember(file) { mutableStateOf(false) }

    // Muat isi berkas di IO, lalu pasang ke editor dan highlight.
    LaunchedEffect(file) {
        val r = withContext(Dispatchers.IO) { FileIo.readForOpen(file) }
        val text = r.text
        if (text == null) {
            holder.failed = r.error ?: "Gagal membuka berkas"
            return@LaunchedEffect
        }
        holder.eol = r.eol
        editor.setText(text)
        editor.setEditable(true)
        holder.loaded = true
        holder.dirty = false
        holder.uiRev++
        val sc = if (EditorSyntax.ready && text.length <= EditorSyntax.MAX_HIGHLIGHT_CHARS) EditorSyntax.scopeFor(file.name) else null
        if (sc != null) {
            EditorSyntax.createAsync(sc) { lang -> editor.post { if (lang != null) editor.setEditorLanguage(lang) } }
        }
    }

    suspend fun saveNow() {
        if (!holder.loaded) return
        val text = editor.text.toString()
        val revAtSave = holder.rev
        try {
            withContext(Dispatchers.IO) { FileIo.writeUtf8Atomic(file, FileIo.applyEol(text, holder.eol)) }
            if (holder.rev == revAtSave) holder.dirty = false
            holder.message = null
        } catch (e: Exception) {
            holder.message = "Gagal menyimpan: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // Autosave berdebounce: LaunchedEffect dimulai ulang tiap rev berubah.
    LaunchedEffect(holder.rev) {
        if (holder.rev > 0 && holder.dirty) {
            delay(AUTOSAVE_DELAY_MS)
            saveNow()
        }
    }

    // Selamatkan edit yang belum tersimpan saat layar dibuang (mis. pindah tab), lalu lepas editor.
    DisposableEffect(file) {
        onDispose {
            if (holder.loaded && holder.dirty) {
                runCatching { FileIo.writeUtf8Atomic(file, FileIo.applyEol(editor.text.toString(), holder.eol)) }
            }
            runCatching { editor.release() }
        }
    }

    BackHandler(enabled = true) {
        if (searchOpen) {
            closeSearch(editor)
            searchOpen = false
        } else {
            scope.launch {
                saveNow()
                onClose()
            }
        }
    }

    Column(
        modifier
            .background(EditorColors.EditorBg)
            .pointerInput(Unit) { detectTapGestures { } } // cegah ketukan tembus ke Penjelajah di bawahnya
    ) {
        TopBar(
            name = file.name,
            dirty = holder.dirty,
            canUndo = holder.uiRev >= 0 && editor.canUndo(),
            canRedo = holder.uiRev >= 0 && editor.canRedo(),
            searchOpen = searchOpen,
            onBack = { scope.launch { saveNow(); onClose() } },
            onUndo = { editor.undo() },
            onRedo = { editor.redo() },
            onSearch = {
                if (searchOpen) closeSearch(editor)
                searchOpen = !searchOpen
            },
            onSave = { scope.launch { saveNow() } },
        )
        if (searchOpen) FindBar(editor, holder.findRev, onClose = { closeSearch(editor); searchOpen = false })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { editor }, modifier = Modifier.fillMaxSize())
            val failed = holder.failed
            if (failed != null) {
                Box(Modifier.fillMaxSize().background(EditorColors.EditorBg), contentAlignment = Alignment.Center) {
                    BasicText(failed, style = TextStyle(color = EditorColors.Danger, fontSize = 14.sp), modifier = Modifier.padding(24.dp))
                }
            } else if (!holder.loaded) {
                Box(Modifier.fillMaxSize().background(EditorColors.EditorBg), contentAlignment = Alignment.Center) {
                    BasicText("Memuat…", style = TextStyle(color = EditorColors.IconDefault, fontSize = 14.sp))
                }
            }
        }
        StatusBar(holder.line, holder.col, holder.eol, holder.message)
    }
}

private fun closeSearch(ed: CodeEditor) {
    val s = ed.searcher
    if (s.hasQuery()) s.stopSearch()
}

private fun createEditor(ctx: android.content.Context, typeface: Typeface, holder: EditorHolder): CodeEditor {
    val ed = CodeEditor(ctx)
    ed.typefaceText = typeface
    ed.setTextSize(14f)
    ed.setWordwrap(true)
    ed.setEditable(false) // aktif setelah isi berkas termuat
    ed.getComponent(Magnifier::class.java).isEnabled = false
    if (EditorSyntax.ensure(ctx)) {
        ed.colorScheme = EditorSyntax.colorScheme() ?: ed.colorScheme.also { applyMolinaColors(it) }
    } else {
        applyMolinaColors(ed.colorScheme)
    }
    ed.subscribeEvent(ContentChangeEvent::class.java) { ev, _ ->
        // setText (memuat berkas) bukan edit pengguna: tidak menandai dirty.
        if (ev.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) {
            holder.dirty = true
            holder.rev++
        }
        ed.post { holder.uiRev++ }
    }
    ed.subscribeEvent(SelectionChangeEvent::class.java) { _, _ ->
        ed.post {
            holder.line = ed.cursor.leftLine + 1
            holder.col = ed.cursor.leftColumn + 1
            holder.findRev++
        }
    }
    ed.subscribeEvent(PublishSearchResultEvent::class.java) { _, _ -> ed.post { holder.findRev++ } }
    return ed
}

@Composable
private fun TopBar(
    name: String, dirty: Boolean, canUndo: Boolean, canRedo: Boolean, searchOpen: Boolean,
    onBack: () -> Unit, onUndo: () -> Unit, onRedo: () -> Unit, onSearch: () -> Unit, onSave: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(EditorColors.Surface).padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextBtn("Kembali", true, EditorColors.Brand, onBack)
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (dirty) EditorColors.Modified else EditorColors.Clean))
        BasicText(
            name,
            style = TextStyle(color = EditorColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        TextBtn("Urung", canUndo, EditorColors.IconDefault, onUndo)
        TextBtn("Ulang", canRedo, EditorColors.IconDefault, onRedo)
        TextBtn("Cari", true, if (searchOpen) EditorColors.Brand else EditorColors.IconDefault, onSearch)
        TextBtn("Simpan", dirty, EditorColors.Brand, onSave)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(EditorColors.Hairline))
}

@Composable
private fun TextBtn(label: String, enabled: Boolean, color: Color, onClick: () -> Unit) {
    BasicText(
        label,
        style = TextStyle(color = if (enabled) color else EditorColors.IconDisabled, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 14.dp),
    )
}

@Composable
private fun FindBar(editor: CodeEditor, findRev: Int, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    fun runSearch(q: String) {
        val s = editor.searcher
        if (q.isEmpty()) {
            if (s.hasQuery()) s.stopSearch()
        } else {
            s.search(q, EditorSearcher.SearchOptions(EditorSearcher.SearchOptions.TYPE_NORMAL, true))
        }
    }
    val count = if (findRev >= 0 && editor.searcher.hasQuery()) editor.searcher.matchedPositionCount else 0
    val index = if (count > 0) editor.searcher.currentMatchedPositionIndex + 1 else 0
    Column(Modifier.fillMaxWidth().background(EditorColors.Surface2)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(query, "Cari", Modifier.weight(1f)) { query = it; runSearch(it) }
            BasicText("$index/$count", style = TextStyle(color = EditorColors.IconDefault, fontSize = 12.sp), modifier = Modifier.padding(horizontal = 6.dp))
            TextBtn("Sebelum", count > 0, EditorColors.IconDefault) { editor.searcher.gotoPrevious() }
            TextBtn("Berikut", count > 0, EditorColors.IconDefault) { editor.searcher.gotoNext() }
            TextBtn("Tutup", true, EditorColors.IconDefault, onClose)
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(replacement, "Ganti dengan", Modifier.weight(1f)) { replacement = it }
            TextBtn("Ganti", count > 0, EditorColors.Brand) { editor.searcher.replaceCurrentMatch(replacement) }
            TextBtn("Semua", count > 0, EditorColors.Brand) { editor.searcher.replaceAll(replacement) }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(EditorColors.Hairline))
}

@Composable
private fun Field(value: String, hint: String, modifier: Modifier, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = EditorColors.TextPrimary, fontSize = 14.sp),
        cursorBrush = SolidColor(EditorColors.Caret),
        modifier = modifier.padding(vertical = 10.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) BasicText(hint, style = TextStyle(color = EditorColors.IconDisabled, fontSize = 14.sp))
                inner()
            }
        },
    )
}

@Composable
private fun StatusBar(line: Int, col: Int, eol: Eol, message: String?) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(EditorColors.Hairline))
    Row(Modifier.fillMaxWidth().background(EditorColors.Surface).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (message != null) {
            BasicText(message, style = TextStyle(color = EditorColors.Danger, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        } else {
            BasicText("Ln $line, Kol $col", style = TextStyle(color = EditorColors.IconDefault, fontSize = 12.sp), modifier = Modifier.weight(1f))
        }
        BasicText("UTF-8 · ${eol.label}", style = TextStyle(color = EditorColors.IconDefault, fontSize = 12.sp))
    }
}
