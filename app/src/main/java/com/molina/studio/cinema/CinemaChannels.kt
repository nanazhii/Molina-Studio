package com.molina.studio.cinema

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.molina.core.common.IptvChannel
import com.molina.core.common.IptvFetch
import com.molina.core.common.IptvLoad
import com.molina.core.common.IptvLocal
import com.molina.core.common.MolinaDir
import com.molina.core.common.MolinaPaths
import com.molina.core.common.M3uParser
import com.molina.core.common.M3uResult
import com.molina.studio.AppPaths
import com.molina.studio.R
import com.molina.studio.settings.TabFolderIcon
import com.molina.studio.ui.MolinaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Hitam netral + emas merek (tema Molina Cinema), bukan biru.
private val DialogBg = Color(0xFF0F0F0F)
private val RowTop = Color(0xFF1C1C1C)
private val RowBottom = Color(0xFF111111)
private val RowLine = Color(0x26FFFFFF)
private val SelTop = Color(0xFF2A220F)
private val SelBottom = Color(0xFF16120A)

/** Prev/Next memindahkan sorotan satu 'halaman' (seperti CH+/CH- cepat di remote TV). */
private const val PAGE = 10

private class ChEntry(val number: Int, val ch: IptvChannel)

private fun chClock(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

/**
 * Dialog Saluran (playlist M3U/IPTV) bergaya daftar channel TV digital: nomor 001.., penghitung + jam,
 * Cari (teks/grup = saring, angka = lompat ke nomor, Enter = putar), Prev/Next halaman, Muat ulang, Hapus sumber.
 * Mengetuk saluran memutar daftar yang tampil mulai dari saluran itu: prev/next di pemutar = ganti saluran.
 */
@Composable
internal fun CinemaChannelsDialog(
    onPlay: (List<IptvChannel>, IptvChannel) -> Unit,
    onDismiss: () -> Unit,
    onOpenFolder: (() -> Unit)? = null,
    refreshTick: Int = 0,
    onOpenInCode: ((File) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf(IptvSources.load(ctx)) }
    var current by remember {
        val last = IptvSources.last(ctx)
        mutableStateOf(sources.firstOrNull { it.url == last }?.url ?: sources.firstOrNull()?.url)
    }
    var adding by remember { mutableStateOf(sources.isEmpty()) }
    val iptvDir = remember { AppPaths.get(ctx).dir(MolinaDir.Iptv) } // /sdcard/Molina/Media/IPTV
    var picking by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf<List<IptvLocal.Entry>?>(emptyList()) } // null = folder tak terbaca
    var filesBusy by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var channels by remember { mutableStateOf<List<IptvChannel>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(1) }
    var clock by remember { mutableStateOf(chClock()) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    fun show(r: M3uResult) {
        channels = r.channels
        selected = 1
        query = ""
        status = buildString {
            append("${r.channels.size} saluran")
            if (r.skipped > 0) append(" \u00B7 ${r.skipped} ditolak kebijakan URL")
            if (r.truncated) append(" \u00B7 dipotong di ${M3uParser.MAX_CHANNELS}")
        }
    }

    fun load(url: String, force: Boolean) {
        if (loading) return
        current = url
        IptvSources.setLast(ctx, url)
        val hit = if (force) null else IptvSources.cached(url)
        if (hit != null) { show(hit); return }
        loading = true
        channels = emptyList()
        status = "Memuat daftar saluran\u2026"
        scope.launch {
            when (val r = withContext(Dispatchers.IO) {
                if (IptvLocal.isLocalRef(url)) IptvLocal.load(iptvDir, File(url)) else IptvFetch.load(url)
            }) {
                is IptvLoad.Ok -> { IptvSources.cachePut(url, r.result); show(r.result) }
                is IptvLoad.Fail -> status = r.message
            }
            loading = false
        }
    }

    fun saveAndLoad() {
        val u = urlInput.trim()
        val err = IptvFetch.problem(u)
        if (err != null) { status = err; return }
        IptvSources.add(ctx, u)
        sources = IptvSources.load(ctx)
        urlInput = ""
        adding = false
        load(u, true)
    }

    fun refreshFiles() {
        if (filesBusy) return
        filesBusy = true
        scope.launch {
            files = withContext(Dispatchers.IO) { IptvLocal.list(iptvDir) }
            filesBusy = false
        }
    }

    fun togglePicker() {
        picking = !picking
        if (picking) { adding = false; refreshFiles() }
    }

    fun pickFile(f: File) {
        val path = f.absolutePath
        IptvSources.add(ctx, path)
        sources = IptvSources.load(ctx)
        picking = false
        adding = false
        load(path, true)
    }

    fun removeCurrent() {
        val u = current ?: return
        IptvSources.remove(ctx, u)
        sources = IptvSources.load(ctx)
        channels = emptyList()
        val next = sources.firstOrNull()?.url
        current = next
        if (next != null) load(next, false) else { adding = true; status = "" }
    }

    LaunchedEffect(Unit) { while (true) { clock = chClock(); delay(15_000) } }
    LaunchedEffect(Unit) { current?.let { load(it, false) } }
    LaunchedEffect(searchOpen) { if (searchOpen) focus.requestFocus() }
    LaunchedEffect(refreshTick) { if (picking) refreshFiles() } // Penjelajah ditutup: daftar berkas disegarkan

    val entries = remember(channels) { channels.mapIndexed { i, c -> ChEntry(i + 1, c) } }
    val q = query.trim()
    val numeric = q.isNotEmpty() && q.all { it.isDigit() }
    val visible = if (q.isEmpty() || numeric) entries
        else entries.filter { it.ch.name.contains(q, ignoreCase = true) || it.ch.group.contains(q, ignoreCase = true) }

    LaunchedEffect(q) {
        if (numeric) {
            val idx = visible.indexOfFirst { it.number == q.toIntOrNull() }
            if (idx >= 0) { selected = visible[idx].number; listState.scrollToItem(idx) }
        }
    }

    fun page(delta: Int) {
        if (visible.isEmpty()) return
        val cur = visible.indexOfFirst { it.number == selected }.coerceAtLeast(0)
        val idx = (cur + delta).coerceIn(0, visible.lastIndex)
        selected = visible[idx].number
        scope.launch { listState.scrollToItem(idx) }
    }

    fun pick(e: ChEntry) = onPlay(visible.map { it.ch }, e.ch)

    fun pickFromQuery() {
        val t = if (numeric) visible.firstOrNull { it.number == q.toIntOrNull() } else visible.singleOrNull()
        if (t != null) pick(t)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val frame = RoundedCornerShape(20.dp)
        Surface(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 24.dp)
                .heightIn(max = 640.dp)
                .border(
                    1.dp,
                    Brush.verticalGradient(listOf(MolinaTheme.Gold.copy(alpha = 0.6f), MolinaTheme.CardLine)),
                    frame,
                ),
            shape = frame,
            color = DialogBg,
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Saluran", color = Color.White, fontSize = 20.sp,
                        fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (entries.isNotEmpty()) {
                        Text("%03d/%03d".format(selected, entries.size), color = MolinaTheme.Muted,
                            fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(clock, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }

                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sources.forEach { s ->
                        SourceChip(s.name, s.url == current && !adding && !picking) { adding = false; picking = false; load(s.url, false) }
                    }
                    SourceChip("+ Tambah", adding) { adding = !adding; if (adding) picking = false }
                    SourceChip("Berkas", picking) { togglePicker() }
                }

                if (adding) Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                    OutlinedTextField(
                        urlInput, { urlInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                        placeholder = { Text("https://\u2026/daftar.m3u", fontSize = 14.sp) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { saveAndLoad() }),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                        Text("Simpan & muat", color = MolinaTheme.Gold, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { saveAndLoad() }.padding(8.dp))
                    }
                }

                if (picking) Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text(
                        "Folder: /sdcard/" + MolinaPaths.APP_FOLDER + "/" + MolinaDir.Iptv.folderName,
                        color = MolinaTheme.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val list = files
                    when {
                        filesBusy && list.isNullOrEmpty() -> Text("Membaca folder\u2026", color = MolinaTheme.Muted,
                            fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                        list == null -> Text("Folder IPTV tidak bisa dibaca. Beri izin penyimpanan, lalu ketuk Segarkan.",
                            color = MolinaTheme.Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                        list.isEmpty() -> Text("Belum ada berkas .m3u / .m3u8 di folder ini. Ketuk Folder IPTV, salin playlist ke sana, lalu tutup Penjelajah.",
                            color = MolinaTheme.Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                        else -> Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                            list.forEach { e -> FileRow(e, onCode = onOpenInCode?.let { fn -> { fn(e.file) } }) { pickFile(e.file) } }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (onOpenFolder != null) {
                            Row(Modifier.clickable { onOpenFolder() }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(20.dp)) { TabFolderIcon(MolinaTheme.Explorer.Folder) }
                                Spacer(Modifier.width(6.dp))
                                Text("Folder IPTV", color = MolinaTheme.Gold, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        } else Spacer(Modifier.width(1.dp))
                        Text("Segarkan", color = MolinaTheme.Gold, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { refreshFiles() }.padding(8.dp))
                    }
                }

                if (loading) LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    color = MolinaTheme.Gold,
                    trackColor = Color(0x1FFFFFFF),
                )
                if (status.isNotEmpty()) Text(status, color = MolinaTheme.Muted, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp))

                if (searchOpen) OutlinedTextField(
                    query, { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
                        .focusRequester(focus),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors(),
                    placeholder = { Text("Cari nama, grup, atau ketik nomor", fontSize = 14.sp) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { pickFromQuery() }),
                )

                if (channels.isEmpty() && !loading && !adding && !picking) Text(
                    "Belum ada saluran. Ketuk + Tambah untuk URL playlist (https), atau Berkas untuk .m3u di folder IPTV Molina.",
                    color = MolinaTheme.Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f, fill = false),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    items(visible, key = { it.number }) { e ->
                        ChRow(e, e.number == selected,
                            onPress = { selected = e.number },
                            onClick = { pick(e) })
                    }
                }

                HorizontalDivider(color = Color(0x1FFFFFFF))

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ChKey(R.drawable.ic_tv_page_prev, null, "Prev", Color.White, Modifier.weight(1f)) { page(-PAGE) }
                    ChKey(R.drawable.ic_tv_page_next, null, "Next", Color.White, Modifier.weight(1f)) { page(PAGE) }
                    ChKey(R.drawable.ic_tv_search, null, "Cari",
                        if (searchOpen) MolinaTheme.Gold else Color.White, Modifier.weight(1f)) {
                        searchOpen = !searchOpen
                        if (!searchOpen) query = ""
                    }
                    ChKey(null, "\u21BB", "Muat", Color.White, Modifier.weight(1f)) { current?.let { load(it, true) } }
                    ChKey(null, "\u2715", "Hapus", Color.White, Modifier.weight(1f)) { removeCurrent() }
                    ChKey(R.drawable.ic_tv_close, null, "Tutup", Color.White, Modifier.weight(1f), onDismiss)
                }
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MolinaTheme.OnBackground,
    unfocusedTextColor = MolinaTheme.OnBackground,
    focusedBorderColor = MolinaTheme.Gold,
    unfocusedBorderColor = MolinaTheme.CardLine,
    cursorColor = MolinaTheme.Gold,
    focusedPlaceholderColor = MolinaTheme.Muted,
    unfocusedPlaceholderColor = MolinaTheme.Muted,
)

@Composable
private fun SourceChip(label: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Text(
        label,
        color = if (active) MolinaTheme.Gold else MolinaTheme.Muted,
        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 170.dp).clip(shape)
            .border(1.dp, if (active) MolinaTheme.Gold else MolinaTheme.CardLine, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun ChRow(e: ChEntry, isSelected: Boolean, onPress: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    val brush = if (isSelected) Brush.verticalGradient(listOf(SelTop, SelBottom))
        else Brush.verticalGradient(listOf(RowTop, RowBottom))
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .clip(shape).background(brush)
            .border(if (isSelected) 2.dp else 1.dp, if (isSelected) MolinaTheme.Gold else RowLine, shape)
            .pointerInput(e.number) {
                detectTapGestures(onPress = { onPress(); tryAwaitRelease() }, onTap = { onClick() })
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("%03d".format(e.number), color = MolinaTheme.Gold,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            modifier = Modifier.width(44.dp))
        Column(Modifier.weight(1f)) {
            Text(e.ch.name, color = Color.White, fontSize = 15.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
            if (e.ch.group.isNotEmpty()) Text(e.ch.group, fontSize = 12.sp,
                color = MolinaTheme.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Tombol bergaya remote: ikon vektor ([icon]) atau glyph teks ([glyph]). */
@Composable
private fun ChKey(icon: Int?, glyph: String?, label: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (icon != null) Image(painterResource(icon), contentDescription = label,
                colorFilter = ColorFilter.tint(tint), modifier = Modifier.size(24.dp))
            else Text(glyph.orEmpty(), color = tint, fontSize = 20.sp)
        }
        Text(label.uppercase(), color = tint, fontSize = 10.sp, maxLines = 1)
    }
}

/** Satu berkas playlist di folder IPTV Molina: nama + ukuran. */
@Composable
private fun FileRow(e: IptvLocal.Entry, onCode: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(shape)
            .border(1.dp, RowLine, shape).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(e.file.name, color = Color.White, fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(sizeLabel(e.sizeBytes), color = MolinaTheme.Muted, fontSize = 12.sp)
        if (onCode != null) Text("Code", color = MolinaTheme.Gold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onCode).padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

private fun sizeLabel(b: Long): String =
    if (b < 1024) "$b B" else if (b < 1024 * 1024) "${b / 1024} KB" else "%.1f MB".format(b / 1048576.0)
