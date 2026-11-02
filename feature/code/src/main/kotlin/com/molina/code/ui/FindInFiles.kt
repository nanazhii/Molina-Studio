package com.molina.code.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** [CODE-SEARCH] Satu baris yang cocok. [line] dan [col] basis 1; [hs]..[he] = rentang sorot di [text]. */
internal class FifMatch(val line: Int, val col: Int, val text: String, val hs: Int, val he: Int)
internal class FifFile(val file: File, val matches: List<FifMatch>)
internal class FifResult(val files: List<FifFile>, val total: Int, val scanned: Int, val capped: Boolean)

/** Mesin Find in Files: murni Kotlin di thread pemanggil (tanpa PRoot), berbatas ukuran berkas dan jumlah hasil. */
internal object FindInFiles {
    const val MAX_MATCHES = 500
    private const val MAX_BYTES = 1_000_000L
    private const val MAX_PER_FILE = 50
    private const val MAX_LINE = 2000

    /** Null bila regex tidak valid. Mode biasa meloloskan semua karakter khusus. */
    fun compile(q: String, regex: Boolean, caseSensitive: Boolean): Regex? = try {
        val opts: Set<RegexOption> = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        if (regex) Regex(q, opts) else Regex(Regex.escape(q), opts)
    } catch (e: Exception) {
        null
    }

    /** Cari [re] di [files]; satu hasil per baris. [active] dicek tiap berkas agar pencarian bisa dibatalkan. */
    fun search(files: List<File>, re: Regex, active: () -> Boolean): FifResult {
        val out = ArrayList<FifFile>()
        var total = 0
        var scanned = 0
        var capped = false
        for (f in files) {
            if (!active()) break
            if (total >= MAX_MATCHES) { capped = true; break }
            val len = f.length()
            if (len == 0L || len > MAX_BYTES) continue
            val bytes = try { f.readBytes() } catch (e: Exception) { continue }
            var binary = false
            for (i in 0 until minOf(bytes.size, 4096)) if (bytes[i] == 0.toByte()) { binary = true; break }
            if (binary) continue
            scanned++
            val hits = ArrayList<FifMatch>()
            var lineNo = 0
            for (raw in String(bytes, Charsets.UTF_8).lineSequence()) {
                lineNo++
                val line = if (raw.length > MAX_LINE) raw.substring(0, MAX_LINE) else raw
                val m = re.find(line) ?: continue
                if (m.value.isEmpty()) continue
                val lead = line.length - line.trimStart().length
                val from = if (m.range.first > 60) m.range.first - 40 else minOf(lead, m.range.first)
                val to = minOf(line.length, from + 160)
                val shown = line.substring(from, to)
                val hs = m.range.first - from
                val he = minOf(m.range.last + 1 - from, shown.length)
                hits.add(FifMatch(lineNo, m.range.first + 1, shown, hs, he))
                if (hits.size >= MAX_PER_FILE || total + hits.size >= MAX_MATCHES) break
            }
            if (hits.isNotEmpty()) {
                out.add(FifFile(f, hits))
                total += hits.size
            }
        }
        return FifResult(out, total, scanned, capped)
    }
}

private class FifRow(val file: File, val rel: String, val count: Int, val match: FifMatch?)

@Composable
internal fun FifToggle(label: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (on) CodeColors.Gold.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, if (on) CodeColors.Gold else CodeColors.IconDefault.copy(alpha = 0.4f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(label, style = TextStyle(color = if (on) CodeColors.Gold else CodeColors.IconDefault, fontSize = 13.sp))
    }
}

/**
 * Find in Files (pola Ctrl+Shift+F Android Studio): cari isi berkas teks di folder utama dengan opsi
 * huruf besar/kecil dan regex. Hasil dikelompokkan per berkas; ketuk baris membuka berkas dan melompat ke sana.
 */
@Composable
internal fun FindInFilesDialog(session: CodeSession, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var regexOn by remember { mutableStateOf(false) }
    var caseOn by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var result by remember { mutableStateOf<FifResult?>(null) }
    var searching by remember { mutableStateOf(false) }
    var badRegex by remember { mutableStateOf(false) }
    var replaceOn by remember { mutableStateOf(false) } // [CODE-REPLACE]
    var replacement by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val root = session.dir.root

    LaunchedEffect(root.path) { files = withContext(Dispatchers.IO) { PaletteScan.scan(root) } }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query, regexOn, caseOn, files) {
        if (query.length < 2) {
            result = null
            badRegex = false
            searching = false
            return@LaunchedEffect
        }
        val re = FindInFiles.compile(query, regexOn, caseOn)
        if (re == null) {
            badRegex = true
            result = null
            searching = false
            return@LaunchedEffect
        }
        badRegex = false
        searching = true
        delay(300)
        result = withContext(Dispatchers.IO) { FindInFiles.search(files, re) { isActive } }
        searching = false
    }

    val rows = remember(result) {
        val list = ArrayList<FifRow>()
        val r = result
        if (r != null) {
            for (ff in r.files) {
                val rel = ff.file.path.removePrefix(root.path).trimStart('/')
                list.add(FifRow(ff.file, rel, ff.matches.size, null))
                for (m in ff.matches) list.add(FifRow(ff.file, rel, 0, m))
            }
        }
        list
    }
    val r = result
    val status = when {
        query.length < 2 -> "Ketik minimal 2 karakter. Mencari di " + root.name.ifEmpty { root.path } + "."
        badRegex -> "Regex tidak valid."
        searching && r == null -> "Mencari\u2026"
        r == null -> ""
        else -> r.total.toString() + " hasil di " + r.files.size + " berkas (" + r.scanned + " dipindai)" +
            (if (r.capped) ", dibatasi " + FindInFiles.MAX_MATCHES else "") + (if (searching) ", memperbarui\u2026" else "")
    }

    val shape = RoundedCornerShape(CodeDimens.DialogCorner)
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).win7DialogFrame(shape)) {
            BasicText(
                text = "Cari di berkas",
                modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                style = TextStyle(color = CodeColors.IconDefault, fontSize = CodeDimens.DialogTitleSize)
            )
            BasicTextField(
                value = query,
                onValueChange = { query = it.take(120) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).padding(horizontal = 16.dp, vertical = 12.dp),
                singleLine = true,
                textStyle = TextStyle(color = CodeColors.TextPrimary, fontSize = CodeDimens.FindTextSize),
                cursorBrush = SolidColor(CodeColors.Gold),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            BasicText(
                                text = "Teks yang dicari",
                                style = TextStyle(color = CodeColors.IconDefault.copy(alpha = 0.5f), fontSize = CodeDimens.FindTextSize)
                            )
                        }
                        inner()
                    }
                }
            )
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                FifToggle("Aa", caseOn) { caseOn = !caseOn }
                Spacer(Modifier.width(8.dp))
                FifToggle(".*", regexOn) { regexOn = !regexOn }
                Spacer(Modifier.width(8.dp))
                FifToggle("Ganti", replaceOn) { replaceOn = !replaceOn }
                Spacer(Modifier.width(12.dp))
                BasicText(
                    text = status,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = if (badRegex) CodeColors.Danger else CodeColors.SecondaryChampagne, fontSize = 12.sp)
                )
            }
            if (replaceOn) {
                ReplaceBar(
                    session = session, files = files, query = query, regex = regexOn, caseSensitive = caseOn,
                    hasHits = (result?.total ?: 0) > 0 && !badRegex, replacement = replacement,
                    onReplacement = { replacement = it }, onDone = onDismiss
                )
            }
            Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(CodeDimens.Hairline).background(CodeColors.Hairline))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxH)) {
                items(rows) { row ->
                    val m = row.match
                    if (m == null) {
                        Row(
                            Modifier.fillMaxWidth().background(CodeColors.IconDefault.copy(alpha = 0.06f)).padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Canvas(Modifier.size(CodeDimens.MenuIconSize)) { drawMenuIcon(MenuIcon.File, CodeColors.IconDefault) }
                            Spacer(Modifier.width(8.dp))
                            BasicText(
                                text = row.file.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                                style = TextStyle(color = CodeColors.IconDefault, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            )
                            Spacer(Modifier.width(8.dp))
                            BasicText(
                                text = row.rel.removeSuffix(row.file.name).trimEnd('/'),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 11.sp)
                            )
                            BasicText(
                                text = row.count.toString(),
                                style = TextStyle(color = CodeColors.Gold, fontSize = 11.sp)
                            )
                        }
                    } else {
                        val txt = remember(m) {
                            buildAnnotatedString {
                                append(m.text)
                                val a = m.hs.coerceIn(0, m.text.length)
                                val b = m.he.coerceIn(a, m.text.length)
                                addStyle(SpanStyle(background = CodeColors.Gold.copy(alpha = 0.35f), color = CodeColors.TextPrimary), a, b)
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    session.openLocation(row.file.path, m.line, m.col)
                                    onDismiss()
                                }
                                .padding(horizontal = 16.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicText(
                                text = m.line.toString(),
                                modifier = Modifier.width(44.dp),
                                maxLines = 1,
                                style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            )
                            BasicText(
                                text = txt,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                style = TextStyle(color = CodeColors.IconDefault, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                }
            }
        }
    }
}
