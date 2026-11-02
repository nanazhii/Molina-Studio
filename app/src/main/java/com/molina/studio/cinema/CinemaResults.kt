package com.molina.studio.cinema

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberUpdatedState
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
import com.molina.core.common.BatchPlan
import com.molina.core.common.DateFilter
import com.molina.core.common.DownloadCommand
import com.molina.core.common.DurationFilter
import com.molina.core.common.RelativeTime
import com.molina.core.common.SearchOrder
import com.molina.core.common.SearchSort
import com.molina.core.common.StreamSearchItem
import com.molina.studio.R
import com.molina.studio.download.DownloadNav
import com.molina.studio.ui.MolinaTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Palet yang sama dengan playlist player (Molina Cinema)
private val DialogBg = Color(0xFF0F0F0F)
private val ChannelNumber = Color(0xFFC8E600)
private val SelectedTop = Color(0xFF3D8BFF)
private val SelectedBottom = Color(0xFF0F4FC4)
private val SelectedLine = Color(0xFF8DBBFF)
private val RowTop = Color(0xFF1F1F1F)
private val RowBottom = Color(0xFF141414)
private val RowLine = Color(0x26FFFFFF)
private val Accent = Color(0xFF4DA3FF)

/** Prev/Next memindahkan sorotan satu 'halaman' (seperti CH+/CH- cepat di remote TV). */
private const val PAGE_SIZE = 10

private class ResultEntry(val number: Int, val item: StreamSearchItem)

private fun clockNow(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

/**
 * Dialog hasil pencarian bergaya daftar channel TV digital: nomor 001.., baris terpilih kotak biru,
 * penghitung + jam di header, Cari (teks = saring, angka = lompat ke nomor, Enter = putar), Info, Tutup.
 */
@Composable
internal fun SearchResultsDialog(
    results: List<StreamSearchItem>,
    onPick: (List<StreamSearchItem>, StreamSearchItem) -> Unit,
    onDismiss: () -> Unit,
    searchQuery: String = "",
    onSearch: ((String) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val seen = remember(SeenStore.version) { SeenStore.snapshot(ctx) } // [SEEN-BADGES]
    var selectMode by remember { mutableStateOf(false) } // [PICK-MANY] mode pilih banyak
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var sort by remember { mutableStateOf(SearchSort.NEWEST) } // [RESULTS-SORT] bawaan: terbaru dulu
    var durFilter by remember { mutableStateOf(DurationFilter.ALL) } // [RESULTS-FILTER]
    var dateFilter by remember { mutableStateOf(DateFilter.ALL) }
    val filtered = remember(results, durFilter, dateFilter) { SearchOrder.filter(results, durFilter, dateFilter) }
    val ordered = remember(filtered, sort) { SearchOrder.sort(filtered, sort) }
    val entries = remember(ordered) { ordered.mapIndexed { i, r -> ResultEntry(i + 1, r) } }
    val related = remember(results) { SearchOrder.related(results) }
    val undated = remember(results) { results.count { RelativeTime.estimateAtMs(it) == null } }
    val suggestions = remember(results, searchQuery) { SearchOrder.relatedQueries(results, searchQuery) }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(1) }
    var clock by remember { mutableStateOf(clockNow()) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) { clock = clockNow(); delay(15_000) }
    }

    val q = query.trim()
    val numeric = q.isNotEmpty() && q.all { it.isDigit() }
    val visible = if (q.isEmpty() || numeric) entries
        else entries.filter { SearchOrder.matches(it.item, q) }
    val pickedItems = visible.map { it.item }.filter { it.pageUrl in chosen } // [PICK-MANY] hanya yang terlihat

    LaunchedEffect(q) {
        if (numeric) {
            val idx = visible.indexOfFirst { it.number == q.toIntOrNull() }
            if (idx >= 0) { selected = visible[idx].number; listState.scrollToItem(idx) }
        }
    }
    LaunchedEffect(searchOpen) { if (searchOpen) focus.requestFocus() }
    LaunchedEffect(sort, durFilter, dateFilter) { selected = 1; listState.scrollToItem(0) } // ganti urutan: kembali ke nomor 001

    fun toggle(url: String) { chosen = if (url in chosen) chosen - url else chosen + url } // [PICK-MANY]

    fun page(delta: Int) {
        if (visible.isEmpty()) return
        val cur = visible.indexOfFirst { it.number == selected }.coerceAtLeast(0)
        val idx = (cur + delta).coerceIn(0, visible.lastIndex)
        selected = visible[idx].number
        scope.launch { listState.scrollToItem(idx) }
    }

    fun pickFromQuery() {
        val t = if (numeric) visible.firstOrNull { it.number == q.toIntOrNull() } else visible.singleOrNull()
        if (t != null) onPick(ordered, t.item)
    }

    // Kirim URL ke tab Unduhan (tidak langsung mengunduh: mode/kualitas dipilih di sana).
    // [PICK-MANY] skipDone = lewati video yang sudah diunduh (dipakai "Unduh semua"; pilihan eksplisit tidak dilewati).
    fun download(items: List<StreamSearchItem>, skipDone: Boolean = false) {
        val plan = BatchPlan.plan(items.map { it.pageUrl }, { seen.isDownloaded(it) }, skipDone, DownloadCommand.MAX_URLS)
        Toast.makeText(ctx, BatchPlan.message(plan, DownloadCommand.MAX_URLS), Toast.LENGTH_SHORT).show()
        if (plan.empty) return
        DownloadNav.request(plan.urls)
        onDismiss()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // [CINEMA-RESULTS-FRAME] margin tepi 24dp, lebar maks 480dp, bingkai tipis bergradasi emas
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
                    Text("Hasil pencarian", color = Color.White, fontSize = 20.sp,
                        fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("%03d/%03d".format(if (entries.isEmpty()) 0 else selected, entries.size), color = MolinaTheme.Muted,
                        fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(12.dp))
                    Text(clock, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }

                // [RESULTS-SORT] urutan waktu unggah + kanal terkait (ketuk = saring daftar dengan nama kanal)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SearchSort.values().forEach { s -> ResultChip(s.label, s == sort) { sort = s } }
                }
                // [RESULTS-FILTER] saringan durasi dan waktu unggah: ketuk = pilihan berikutnya (berputar)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Saring", color = MolinaTheme.Muted, fontSize = 12.sp)
                    ResultChip("Durasi: ${durFilter.label}", durFilter != DurationFilter.ALL) { durFilter = durFilter.next() }
                    ResultChip("Unggah: ${dateFilter.label}", dateFilter != DateFilter.ALL) { dateFilter = dateFilter.next() }
                    // [PICK-MANY] pilih banyak: "Pilih" membuka mode centang (ketuk lama pada baris juga bisa)
                    ResultChip(if (selectMode) "Pilih: ${pickedItems.size}" else "Pilih", selectMode) {
                        if (selectMode) { selectMode = false; chosen = emptySet() } else { selectMode = true }
                    }
                    if (selectMode) {
                        ResultChip("Semua", false) { chosen = visible.map { it.item.pageUrl }.toSet() }
                        ResultChip("Belum diunduh", false) {
                            chosen = visible.map { it.item.pageUrl }.filter { !seen.isDownloaded(it) }.toSet()
                        }
                    }
                }
                if (sort != SearchSort.RELEVANCE && undated > 0) Text(
                    "$undated hasil tanpa tanggal ditaruh di akhir", color = MolinaTheme.Muted, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                )
                if (related.isNotEmpty()) Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Terkait", color = MolinaTheme.Muted, fontSize = 12.sp)
                    related.forEach { (name, n) ->
                        val on = q.equals(name, ignoreCase = true)
                        ResultChip("$name \u00B7 $n", on) {
                            if (on) { query = ""; searchOpen = false } else { query = name; searchOpen = true }
                        }
                    }
                }

                // [RESULTS-SUGGEST] pencarian terkait: kata kunci dari judul hasil, ketuk = cari lagi
                if (onSearch != null && suggestions.isNotEmpty()) Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Cari juga", color = MolinaTheme.Muted, fontSize = 12.sp)
                    suggestions.forEach { s -> ResultChip(s, false) { onSearch(s) } }
                }

                if (searchOpen) OutlinedTextField(
                    query, { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
                        .focusRequester(focus),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MolinaTheme.OnBackground,
                        unfocusedTextColor = MolinaTheme.OnBackground,
                        focusedBorderColor = MolinaTheme.Gold,
                        unfocusedBorderColor = MolinaTheme.CardLine,
                        cursorColor = MolinaTheme.Gold,
                        focusedPlaceholderColor = MolinaTheme.Muted,
                        unfocusedPlaceholderColor = MolinaTheme.Muted,
                    ),
                    placeholder = { Text("Cari judul, kanal, atau nomor", fontSize = 14.sp) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { pickFromQuery() }),
                )

                if (entries.isEmpty()) Text(
                    "Tidak ada hasil dengan saringan ini", color = MolinaTheme.Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f, fill = false),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                ) {
                    items(visible, key = { it.number }) { e ->
                        ChannelRow(e, e.number == selected,
                            onPress = { selected = e.number },
                            onClick = { if (selectMode) toggle(e.item.pageUrl) else onPick(ordered, e.item) },
                            onDownload = { download(listOf(e.item)) },
                            played = seen.isPlayed(e.item.pageUrl),
                            downloaded = seen.isDownloaded(e.item.pageUrl),
                            checked = if (selectMode) e.item.pageUrl in chosen else null,
                            onLongPress = { selectMode = true; chosen = chosen + e.item.pageUrl })
                    }
                }

                HorizontalDivider(color = Color(0x1FFFFFFF))

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    TvKey(R.drawable.ic_tv_page_prev, "Prev", Color.White, Modifier.weight(1f)) { page(-PAGE_SIZE) }
                    TvKey(R.drawable.ic_tv_page_next, "Next", Color.White, Modifier.weight(1f)) { page(PAGE_SIZE) }
                    TvKey(R.drawable.ic_tv_search, "Cari",
                        if (searchOpen) Accent else Color.White, Modifier.weight(1f)) {
                        searchOpen = !searchOpen
                        if (!searchOpen) query = ""
                    }
                    TvKey(R.drawable.ic_tv_download,
                        if (pickedItems.isNotEmpty()) "Unduh ${pickedItems.size}" else "Unduh semua",
                        MolinaTheme.Gold, Modifier.weight(1f)) {
                        if (pickedItems.isNotEmpty()) download(pickedItems)
                        else download(visible.map { it.item }, skipDone = true)
                    }
                    TvKey(R.drawable.ic_tv_info, "Info", Color.White, Modifier.weight(1f)) {
                        Toast.makeText(ctx,
                            "Hasil %03d dari %03d".format(selected, entries.size),
                            Toast.LENGTH_SHORT).show()
                    }
                    TvKey(R.drawable.ic_tv_close, "Tutup", Color.White, Modifier.weight(1f), onDismiss)
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    e: ResultEntry, isSelected: Boolean,
    onPress: () -> Unit, onClick: () -> Unit, onDownload: () -> Unit,
    played: Boolean = false, downloaded: Boolean = false,
    checked: Boolean? = null, onLongPress: () -> Unit = {},
) {
    // [PICK-MANY] lambda terbaru: pointerInput tidak dimulai ulang saat daftar diurut/disaring atau mode pilih berganti
    val onPressNow by rememberUpdatedState(onPress)
    val onClickNow by rememberUpdatedState(onClick)
    val onLongNow by rememberUpdatedState(onLongPress)
    val shape = RoundedCornerShape(4.dp)
    val brush = if (isSelected) Brush.verticalGradient(listOf(SelectedTop, SelectedBottom))
        else Brush.verticalGradient(listOf(RowTop, RowBottom))
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .clip(shape).background(brush)
            .border(1.dp, if (isSelected) SelectedLine else RowLine, shape)
            .pointerInput(e.number) {
                detectTapGestures(
                    onPress = { onPressNow(); tryAwaitRelease() },
                    onLongPress = { onLongNow() },
                    onTap = { onClickNow() },
                )
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("%03d".format(e.number), color = if (isSelected) Color.White else ChannelNumber,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            modifier = Modifier.width(44.dp))
        // [PICK-MANY] kotak centang (digambar, bukan glif emoji) hanya saat mode pilih aktif
        if (checked != null) Box(
            Modifier.padding(end = 8.dp).size(20.dp).clip(RoundedCornerShape(4.dp))
                .background(if (checked) MolinaTheme.Gold else Color.Transparent)
                .border(1.5.dp, MolinaTheme.Gold, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) { if (checked) Text("\u2713", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        Column(Modifier.weight(1f)) {
            Text(e.item.title, color = Color.White, fontSize = 15.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
            val meta = listOfNotNull(e.item.uploader, e.item.durationSec?.let { duration(it) }, RelativeTime.label(e.item))
                .joinToString(" \u00B7 ")
            if (meta.isNotEmpty()) Text(meta, fontSize = 12.sp,
                color = if (isSelected) Color(0xCCFFFFFF) else MolinaTheme.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            // [SEEN-BADGES] lencana hanya muncul bila ada; baris tanpa penanda tidak bertambah tinggi
            if (downloaded || played) Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (downloaded) SeenBadge("\u2193 Diunduh", if (isSelected) Color.White else MolinaTheme.Gold)
                if (played) SeenBadge("\u25B6 Pernah diputar", if (isSelected) Color.White else MolinaTheme.Muted)
            }
        }
        Box(
            Modifier.padding(start = 8.dp).size(36.dp).clip(RoundedCornerShape(18.dp))
                .border(1.dp, MolinaTheme.Gold.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
                .clickable(onClick = onDownload),
            contentAlignment = Alignment.Center,
        ) { Text("\u2193", color = MolinaTheme.Gold, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun TvKey(icon: Int, label: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(icon), contentDescription = label,
            colorFilter = ColorFilter.tint(tint), modifier = Modifier.size(24.dp))
        Text(label.uppercase(), color = tint, fontSize = 10.sp, maxLines = 1)
    }
}

/** [SEEN-BADGES] Lencana kecil bergaris untuk penanda "Diunduh" dan "Pernah diputar". */
@Composable
private fun SeenBadge(label: String, color: Color) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(shape).border(1.dp, color.copy(alpha = 0.6f), shape)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** [RESULTS-SORT] Chip kecil: aktif = emas penuh, tidak aktif = garis tipis. */
@Composable
private fun ResultChip(label: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Text(
        label,
        color = if (active) Color.Black else Color.White,
        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.widthIn(max = 180.dp).clip(shape)
            .background(if (active) MolinaTheme.Gold else Color(0x14FFFFFF))
            .border(1.dp, if (active) MolinaTheme.Gold else RowLine, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
