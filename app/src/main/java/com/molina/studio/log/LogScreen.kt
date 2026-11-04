package com.molina.studio.log

// LOG-PRO-V3 (cari + ekspor .txt) di atas LOG-PRO-V2: chip sumber, dropdown level, menu aksi, baris berstruktur, grup mpv yang bisa dilipat.

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.core.common.LogLevel
import com.molina.core.common.MolinaDir
import com.molina.studio.AppPaths
import com.molina.studio.ui.MolinaTheme
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val SOURCES = listOf(
    "" to "Semua",
    "cinema" to "Cinema",
    "terminal" to "Terminal",
    "sistem" to "Sistem",
    "logcat" to "Logcat",
    "crash" to "Crash"
)

// Log pemutar (MpvLog memakai sumber "mpv") ditampilkan di filter Cinema.
private val CINEMA_SOURCES = setOf("cinema", "mpv")

private fun matchesFilter(source: String, filter: String): Boolean =
    if (filter == "cinema") source in CINEMA_SOURCES else source == filter

/** Filter level minimum (urutan enum: Debug < Info < Warn < Error). */
private enum class LevelFilter(val label: String, val min: LogLevel) {
    All("Semua level", LogLevel.Debug),
    Info("Info ke atas", LogLevel.Info),
    Warn("Peringatan ke atas", LogLevel.Warn),
    Error("Galat saja", LogLevel.Error)
}

private val TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
private val TIME_SHORT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/** Run log Cinema beruntun sebanyak ini (atau lebih) dikelompokkan jadi satu bagian yang bisa dilipat. */
private const val GROUP_MIN = 4

private sealed interface LogRow { val key: String }
private class LogLine(val e: LogEntry, val child: Boolean) : LogRow {
    override val key: String = (if (child) "c" else "e") + e.id
}
private class GroupRow(val items: List<LogEntry>, val open: Boolean) : LogRow {
    override val key: String = "g" + items.first().id
}

private fun buildRows(list: List<LogEntry>, group: Boolean, openKeys: Set<String>): List<LogRow> {
    val out = ArrayList<LogRow>(list.size)
    var i = 0
    while (i < list.size) {
        val isCinema = list[i].source in CINEMA_SOURCES
        if (group && isCinema) {
            var j = i
            while (j < list.size && list[j].source in CINEMA_SOURCES) j++
            val run = list.subList(i, j).toList()
            if (run.size >= GROUP_MIN) {
                val g = GroupRow(run, ("g" + run.first().id) in openKeys)
                out.add(g)
                if (g.open) run.forEach { out.add(LogLine(it, true)) }
            } else {
                run.forEach { out.add(LogLine(it, false)) }
            }
            i = j
        } else {
            out.add(LogLine(list[i], false))
            i++
        }
    }
    return out
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogScreen(modifier: Modifier = Modifier) {
    val entries by LogBus.entries.collectAsState()
    var source by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { LogNav.take()?.let { source = it } } // [DIAG-LOGNAV]
    var levelIdx by rememberSaveable { mutableStateOf(0) }
    var openKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var levelMenu by remember { mutableStateOf(false) }
    var actionMenu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val level = LevelFilter.entries[levelIdx.coerceIn(0, LevelFilter.entries.lastIndex)]
    val q = query.trim()
    val filterDesc = "sumber=" + (SOURCES.firstOrNull { it.first == source }?.second ?: source) +
        ", level=" + level.label + if (q.isEmpty()) "" else ", kata kunci=\"" + q + "\""

    val shown = remember(entries, source, level, q) {
        entries.filter {
            (source.isEmpty() || matchesFilter(it.source, source)) &&
                it.level.ordinal >= level.min.ordinal &&
                (q.isEmpty() || it.message.contains(q, ignoreCase = true) ||
                    it.tag.contains(q, ignoreCase = true) || it.source.contains(q, ignoreCase = true))
        }
    }
    val counts = remember(entries) {
        SOURCES.associate { (key, _) ->
            key to if (key.isEmpty()) entries.size else entries.count { matchesFilter(it.source, key) }
        }
    }
    val rows = remember(shown, source, openKeys, q) {
        buildRows(shown, source != "cinema" && q.isEmpty(), openKeys)
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val lastCount = shown.lastOrNull()?.count ?: 0

    // Ikuti entri terbaru hanya bila pengguna sudah berada di dasar daftar.
    LaunchedEffect(rows.size, shown.size, lastCount) {
        if (rows.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= info.totalItemsCount - 2) listState.scrollToItem(rows.lastIndex)
    }
    LaunchedEffect(searchOpen) { if (searchOpen) runCatching { searchFocus.requestFocus() } }
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 2
        }
    }

    Column(modifier) {
        // Baris 1: filter sumber (chip, bisa digeser)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SOURCES.forEach { (key, label) ->
                SourceChip(label, counts[key] ?: 0, source == key) { source = key }
            }
        }
        // Baris 2: dropdown level, jumlah baris, menu aksi
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                LevelButton(level.label) { levelMenu = true }
                DropdownMenu(expanded = levelMenu, onDismissRequest = { levelMenu = false }) {
                    LevelFilter.entries.forEachIndexed { i, f ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    f.label, fontSize = 13.sp,
                                    fontWeight = if (i == levelIdx) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = { levelIdx = i; levelMenu = false }
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            LevelButton("Cari", caret = false, active = searchOpen || q.isNotEmpty()) { searchOpen = !searchOpen }
            Spacer(Modifier.weight(1f))
            Text("${shown.size} baris", fontSize = 11.sp, color = MolinaTheme.Muted)
            Box {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable { actionMenu = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text("\u22EE", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                DropdownMenu(expanded = actionMenu, onDismissRequest = { actionMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Salin", fontSize = 13.sp) },
                        onClick = { actionMenu = false; copyLogs(ctx, shown) }
                    )
                    DropdownMenuItem(
                        text = { Text("Bagikan", fontSize = 13.sp) },
                        onClick = { actionMenu = false; shareLogs(ctx, shown) }
                    )
                    DropdownMenuItem(
                        text = { Text("Simpan ke berkas (.txt)", fontSize = 13.sp) },
                        onClick = { actionMenu = false; exportLogs(ctx, shown, filterDesc) }
                    )
                    DropdownMenuItem(
                        text = { Text("Kirim pesan uji", fontSize = 13.sp) },
                        onClick = { actionMenu = false; sendTest() }
                    )
                    DropdownMenuItem(
                        text = { Text("Hapus semua log", fontSize = 13.sp, color = MolinaTheme.Bad) },
                        onClick = { actionMenu = false; confirmClear = true }
                    )
                }
            }
        }
        if (searchOpen) {
            val boxShape = RoundedCornerShape(8.dp)
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    modifier = Modifier.weight(1f).heightIn(min = 38.dp).clip(boxShape)
                        .border(1.dp, MolinaTheme.CardLine, boxShape)
                        .focusRequester(searchFocus)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text("Cari pesan, tag, atau sumber\u2026", fontSize = 13.sp, color = MolinaTheme.Muted)
                            }
                            inner()
                        }
                    }
                )
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(22.dp))
                        .clickable { query = ""; searchOpen = false; focusManager.clearFocus() },
                    contentAlignment = Alignment.Center
                ) { Text("\u2715", fontSize = 14.sp, color = MolinaTheme.Muted) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MolinaTheme.CardLine))

        Box(Modifier.fillMaxSize()) {
            if (rows.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val filtered = entries.isNotEmpty()
                    Text(
                        if (filtered) "Tidak ada log yang cocok dengan filter." else "Belum ada log.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    if (filtered) {
                        TextButton(onClick = { source = ""; levelIdx = 0; query = "" }) { Text("Atur ulang filter") }
                    }
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is LogLine -> EntryRow(row.e, row.child, q) { copyText(ctx, format(row.e), "1 baris") }
                            is GroupRow -> GroupHeader(row) {
                                openKeys = if (row.key in openKeys) openKeys - row.key else openKeys + row.key
                            }
                        }
                    }
                }
                if (!atBottom) {
                    Text(
                        "\u2193 Ke terbaru",
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f))
                            .clickable { scope.launch { listState.animateScrollToItem(rows.lastIndex) } }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Hapus semua log?") },
            text = { Text("${entries.size} entri akan dihapus. Tindakan ini tidak dapat dibatalkan.") },
            confirmButton = {
                TextButton(onClick = { LogBus.clear(); confirmClear = false }) {
                    Text("Hapus", color = MolinaTheme.Bad)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Batal") } }
        )
    }
}

@Composable
private fun SourceChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.heightIn(min = 36.dp).clip(shape)
            .background(if (selected) accent.copy(alpha = 0.16f) else MolinaTheme.CardBg)
            .border(1.dp, if (selected) accent else MolinaTheme.CardLine, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface
        )
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Text(count.toString(), fontSize = 10.sp, color = MolinaTheme.Muted)
        }
    }
}

@Composable
private fun LevelButton(label: String, caret: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.heightIn(min = 36.dp).clip(shape)
            .border(1.dp, if (active) accent else MolinaTheme.CardLine, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = if (active) accent else MaterialTheme.colorScheme.onSurface)
        if (caret) {
            Spacer(Modifier.width(6.dp))
            Text("\u25BE", fontSize = 12.sp, color = MolinaTheme.Muted)
        }
    }
}

private fun levelAccent(level: LogLevel): Color = when (level) {
    LogLevel.Error -> MolinaTheme.Bad
    LogLevel.Warn -> MolinaTheme.Warn
    LogLevel.Info -> Color(0xFF6CB4FF)
    LogLevel.Debug -> MolinaTheme.Muted
}

private fun sourceColor(source: String): Color = when (source) {
    "cinema", "mpv" -> MolinaTheme.Cinema
    "terminal" -> MolinaTheme.Good
    "sistem" -> Color(0xFF6CB4FF)
    "crash" -> MolinaTheme.Bad
    else -> MolinaTheme.Muted
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(e: LogEntry, child: Boolean, query: String, onCopy: () -> Unit) {
    val body = when (e.level) {
        LogLevel.Error -> MolinaTheme.Bad
        LogLevel.Warn -> MolinaTheme.Warn
        LogLevel.Info -> MaterialTheme.colorScheme.onSurface
        LogLevel.Debug -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    }
    val label = if (e.tag.isEmpty() || e.tag == e.source) e.source else "${e.source}/${e.tag}"
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onCopy)
            .padding(start = if (child) 28.dp else 12.dp, end = 12.dp, top = 3.dp, bottom = 3.dp)
    ) {
        Text(
            TIME_SHORT.format(Instant.ofEpochMilli(e.time)),
            modifier = Modifier.width(54.dp).padding(top = 2.dp),
            fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MolinaTheme.Muted
        )
        val accent = levelAccent(e.level)
        Box(
            Modifier.padding(top = 1.dp).size(16.dp).clip(RoundedCornerShape(3.dp))
                .background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Text(e.level.code.toString(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = accent)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = sourceColor(e.source), fontWeight = FontWeight.SemiBold)) {
                    append(label)
                }
                append("  ")
                appendHighlighted(e.message, query, body)
                if (e.count > 1) {
                    withStyle(SpanStyle(color = MolinaTheme.Muted)) { append("  \u00D7${e.count}") }
                }
            },
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp
        )
    }
}

@Composable
private fun GroupHeader(g: GroupRow, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val errors = g.items.count { it.level == LogLevel.Error }
    val warns = g.items.count { it.level == LogLevel.Warn }
    val range = TIME_SHORT.format(Instant.ofEpochMilli(g.items.first().time)) + " \u2013 " +
        TIME_SHORT.format(Instant.ofEpochMilli(g.items.last().time))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape).background(MolinaTheme.CardBg)
            .border(1.dp, MolinaTheme.CardLine, shape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("\u25B6", fontSize = 11.sp, color = MolinaTheme.Cinema)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Cinema \u00B7 ${g.items.size} baris", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(range, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MolinaTheme.Muted)
        }
        if (errors > 0) CountPill("$errors galat", MolinaTheme.Bad)
        if (warns > 0) CountPill("$warns peringatan", MolinaTheme.Warn)
        Spacer(Modifier.width(8.dp))
        Text(if (g.open) "\u25B4" else "\u25BE", fontSize = 14.sp, color = MolinaTheme.Muted)
    }
}

@Composable
private fun CountPill(text: String, color: Color) {
    Text(
        text,
        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp),
        fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = color
    )
}

private fun format(e: LogEntry): String {
    val n = if (e.count > 1) "  \u00D7${e.count}" else ""
    return "${TIME_FMT.format(Instant.ofEpochMilli(e.time))} ${e.level.code} " +
        "${e.source}/${e.tag}: ${e.message}$n"
}

private const val MAX_COPY_LINES = 2000
private const val MAX_SHARE_LINES = 800

private fun joinLogs(list: List<LogEntry>, max: Int): String =
    // [LOG-REDACT] Salin/Bagikan keluar dari aplikasi (clipboard terbaca app lain di Android 8): samarkan URL dan token
    com.molina.core.common.LogRedact.text(list.takeLast(max).joinToString("\n") { format(it) })

private fun copyText(ctx: Context, text: String, what: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Molina Log", text))
    Toast.makeText(ctx, "Tersalin: $what", Toast.LENGTH_SHORT).show()
}

private fun copyLogs(ctx: Context, list: List<LogEntry>) {
    if (list.isEmpty()) {
        Toast.makeText(ctx, "Belum ada log", Toast.LENGTH_SHORT).show()
        return
    }
    copyText(ctx, joinLogs(list, MAX_COPY_LINES), "${minOf(list.size, MAX_COPY_LINES)} baris")
}

private fun shareLogs(ctx: Context, list: List<LogEntry>) {
    if (list.isEmpty()) {
        Toast.makeText(ctx, "Belum ada log", Toast.LENGTH_SHORT).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, joinLogs(list, MAX_SHARE_LINES))
    }
    ctx.startActivity(Intent.createChooser(send, "Bagikan log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Kirim satu baris uji lewat socket IPC (jalur penuh klien -> server -> bus -> UI). */
private fun sendTest() {
    Thread({
        val ok = LogClient.send(
            "terminal", LogLevel.Info, "uji",
            "Pesan uji lewat socket IPC @${LogServer.SOCKET_NAME}"
        )
        if (!ok) LogBus.log("sistem", LogLevel.Error, "LogClient", "Gagal mengirim lewat socket")
    }, "molina-log-test").start()
}

/** Menambahkan [text] dengan sorotan emas pada setiap kemunculan [query] (tanpa membedakan huruf besar/kecil). */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendHighlighted(
    text: String, query: String, color: Color
) {
    if (query.isEmpty()) {
        withStyle(SpanStyle(color = color)) { append(text) }
        return
    }
    var from = 0
    while (from < text.length) {
        val at = text.indexOf(query, from, ignoreCase = true)
        if (at < 0) {
            withStyle(SpanStyle(color = color)) { append(text.substring(from)) }
            return
        }
        if (at > from) withStyle(SpanStyle(color = color)) { append(text.substring(from, at)) }
        withStyle(SpanStyle(color = Color.Black, background = MolinaTheme.Mint)) {
            append(text.substring(at, at + query.length))
        }
        from = at + query.length
    }
}

/** Ekspor log terfilter ke berkas bernama jelas di <appRoot>/Documents/Export (tanpa FileProvider/Manifest). */
private fun exportLogs(ctx: Context, list: List<LogEntry>, filterDesc: String) {
    if (list.isEmpty()) {
        Toast.makeText(ctx, "Belum ada log", Toast.LENGTH_SHORT).show()
        return
    }
    val app = ctx.applicationContext
    Thread({
        val msg = try {
            val dir = AppPaths.get(app).dir(MolinaDir.Export)
            if (!dir.isDirectory && !dir.mkdirs()) throw java.io.IOException("folder tidak bisa dibuat: ${dir.path}")
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneId.systemDefault()).format(Instant.now())
            val file = File(dir, "molina-log-$stamp.txt")
            file.writeText(buildReport(app, list, filterDesc), Charsets.UTF_8)
            "Tersimpan: ${file.path}"
        } catch (e: Exception) {
            "Gagal menyimpan: ${e.message}"
        }
        Handler(Looper.getMainLooper()).post { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
    }, "molina-log-export").start()
}

private fun buildReport(ctx: Context, list: List<LogEntry>, filterDesc: String): String {
    val version = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }
    val now = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault()).format(Instant.now())
    val sb = StringBuilder()
    sb.append("Molina Log\n")
    sb.append("Dibuat    : ").append(now).append('\n')
    sb.append("Versi app : ").append(version).append('\n')
    sb.append("Perangkat : ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
        .append(", Android ").append(android.os.Build.VERSION.RELEASE)
        .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append("), ")
        .append(android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?").append('\n')
    sb.append("Filter    : ").append(filterDesc).append('\n')
    sb.append("Jumlah    : ").append(list.size).append(" baris\n")
    sb.append("----------------------------------------\n")
    list.forEach { sb.append(format(it)).append('\n') }
    return sb.toString()
}
