package com.molina.studio.cinema

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.molina.cinema.WatchProgressStore
import com.molina.core.common.BatchPlan
import com.molina.core.common.DateFilter
import com.molina.core.common.DownloadCommand
import com.molina.core.common.DurationFilter
import com.molina.core.common.RelativeTime
import com.molina.core.common.SearchOrder
import com.molina.core.common.SearchSort
import com.molina.core.common.StreamSearchItem
import com.molina.core.common.VideoKey
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
private val ThumbWidth = 104.dp // [THUMB-169] lebar tetap, tinggi 16:9 (gambar utuh, tidak terpotong)
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
    onFetchRelated: (suspend (String) -> List<StreamSearchItem>)? = null, // [RESULTS-RELATED]
) {
    val ctx = LocalContext.current
    val seen = remember(SeenStore.version) { SeenStore.snapshot(ctx) } // [SEEN-BADGES]
    val showThumbs = remember { SearchThumbPrefs.enabled(ctx) } // [RESULTS-THUMB-TOGGLE]
    val watched = remember { WatchProgressStore.snapshot(ctx) } // [WATCH-PROGRESS]
    var selectMode by remember { mutableStateOf(false) } // [PICK-MANY] mode pilih banyak
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var sort by remember { mutableStateOf(SearchSortPrefs.load(ctx)) } // [RESULTS-SORT] bawaan: terbaru; pilihan terakhir diingat [RESULTS-SORT-PREF]
    var durFilter by remember { mutableStateOf(DurationFilter.ALL) } // [RESULTS-FILTER]
    var dateFilter by remember { mutableStateOf(DateFilter.ALL) }
    // [RESULTS-RELATED] Relevansi: pencarian kedua (kueri kategori) diambil sekali per daftar hasil, saat chip dipilih
    var relatedExtra by remember(results) { mutableStateOf<List<StreamSearchItem>?>(null) }
    var relatedLoading by remember { mutableStateOf(false) }
    val relatedQ = remember(results, searchQuery) { SearchOrder.relatedQuery(results, searchQuery) } // [RELATED-CATEGORY]
    LaunchedEffect(sort, results) {
        if (sort != SearchSort.RELEVANCE || relatedExtra != null || onFetchRelated == null) return@LaunchedEffect
        val rq = relatedQ
        if (rq == null) { relatedExtra = emptyList(); return@LaunchedEffect }
        relatedLoading = true
        try { relatedExtra = onFetchRelated(rq) } finally { relatedLoading = false }
    }
    val relatedMixed = remember(results, relatedExtra, searchQuery, sort) {
        if (sort == SearchSort.RELEVANCE) SearchOrder.relatedMix(results, relatedExtra.orEmpty(), searchQuery) else results
    }
    val relatedFallback = sort == SearchSort.RELEVANCE && !relatedLoading && relatedExtra != null && relatedMixed === results
    val filtered = remember(relatedMixed, durFilter, dateFilter) { SearchOrder.filter(relatedMixed, durFilter, dateFilter) }
    val ordered = remember(filtered, sort) { SearchOrder.sort(filtered, sort) }
    val entries = remember(ordered) { ordered.mapIndexed { i, r -> ResultEntry(i + 1, r) } }
    val undated = remember(results) { results.count { RelativeTime.estimateAtMs(it) == null } }
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
            Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 12.dp)
                .heightIn(max = 640.dp)
                .border(
                    1.dp,
                    Brush.verticalGradient(listOf(MolinaTheme.Brand.copy(alpha = 0.6f), MolinaTheme.CardLine)),
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
                    SearchSort.values().forEach { s -> ResultChip(s.label, s == sort) { sort = s; SearchSortPrefs.save(ctx, s) } }
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
                if (sort == SearchSort.NEWEST && undated > 0) Text(
                    "$undated hasil tanpa tanggal ditaruh di akhir", color = MolinaTheme.Muted, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                )
                // [RELATED-STATUS]
                if (sort == SearchSort.RELEVANCE && (relatedLoading || relatedFallback)) Text(
                    if (relatedLoading) "Mencari video terkait\u2026" else "Belum ada video terkait, menampilkan hasil asli",
                    color = MolinaTheme.Muted, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                )
                // [RELATED-CATEGORY] kueri kategori yang dipakai; ketuk = cari penuh dengan kueri itu
                if (sort == SearchSort.RELEVANCE && relatedQ != null && onSearch != null) Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Kategori", color = MolinaTheme.Muted, fontSize = 12.sp)
                    ResultChip(relatedQ, false) { onSearch(relatedQ) }
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
                        focusedBorderColor = MolinaTheme.Brand,
                        unfocusedBorderColor = MolinaTheme.CardLine,
                        cursorColor = MolinaTheme.Brand,
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
                            progress = VideoKey.of(e.item.pageUrl)?.let { watched[it] }, showThumb = showThumbs,
                            checked = if (selectMode) e.item.pageUrl in chosen else null,
                            onLongPress = { selectMode = true; chosen = chosen + e.item.pageUrl })
                    }
                }

                // [TITLE-STRIP] judul lengkap baris yang disorot (baris daftar tetap 2 baris); tinggi tetap agar daftar tidak bergeser
                Text(
                    visible.firstOrNull { it.number == selected }?.item?.title.orEmpty(),
                    color = Color(0xE6FFFFFF), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 20.dp, vertical = 6.dp),
                )
                // [TITLE-STRIP] judul lengkap baris yang disorot (baris daftar tetap 2 baris); tinggi tetap agar daftar tidak bergeser
                Text(
                    visible.firstOrNull { it.number == selected }?.item?.title.orEmpty(),
                    color = Color(0xE6FFFFFF), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 20.dp, vertical = 6.dp),
                )
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
                        MolinaTheme.Brand, Modifier.weight(1f)) {
                        if (pickedItems.isNotEmpty()) download(pickedItems)
                        else download(visible.map { it.item }, skipDone = true)
                    }
                    TvKey(R.drawable.ic_tv_info, "Info", Color.White, Modifier.weight(1f)) {
                        Toast.makeText(ctx,
                            "Hasil %03d dari %03d\n%s".format(selected, entries.size, visible.firstOrNull { it.number == selected }?.item?.title.orEmpty()), // [TITLE-STRIP]
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
    progress: Int? = null, showThumb: Boolean = true,
) {
    // [PICK-MANY] lambda terbaru: pointerInput tidak dimulai ulang saat daftar diurut/disaring atau mode pilih berganti
    val onPressNow by rememberUpdatedState(onPress)
    val onClickNow by rememberUpdatedState(onClick)
    val onLongNow by rememberUpdatedState(onLongPress)
    val thumbHeld = remember { BooleanArray(1) } // [THUMB-PREVIEW] true selama jari menekan thumbnail YouTube
    val shape = RoundedCornerShape(4.dp)
    val brush = if (isSelected) Brush.verticalGradient(listOf(SelectedTop, SelectedBottom))
        else Brush.verticalGradient(listOf(RowTop, RowBottom))
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .clip(shape).background(brush)
            .border(1.dp, if (isSelected) SelectedLine else RowLine, shape)
            .then(if (isSelected) Modifier.drawBehind { drawRect(MolinaTheme.Brand, size = Size(3.dp.toPx(), size.height)) } else Modifier) // [BRAND-ACCENT]
            .pointerInput(e.number) {
                detectTapGestures(
                    onPress = { onPressNow(); tryAwaitRelease() },
                    onLongPress = { if (!thumbHeld[0]) onLongNow() },
                    onTap = { onClickNow() },
                )
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("%03d".format(e.number), color = if (isSelected) Color.White else ChannelNumber,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            modifier = Modifier.width(34.dp))
        // [PICK-MANY] kotak centang (digambar, bukan glif emoji) hanya saat mode pilih aktif
        if (checked != null) Box(
            Modifier.padding(end = 8.dp).size(20.dp).clip(RoundedCornerShape(4.dp))
                .background(if (checked) MolinaTheme.Brand else Color.Transparent)
                .border(1.5.dp, MolinaTheme.Brand, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) { if (checked) Text("\u2713", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        if (showThumb) SearchThumb(
            e.item.thumbnailUrl, e.item.durationSec, progress,
            VideoKey.of(e.item.pageUrl)?.removePrefix("yt:"), thumbHeld,
            Modifier.padding(end = 8.dp).width(ThumbWidth).aspectRatio(16f / 9f), // [THUMB-169]
        ) // [RESULTS-THUMB]
        Column(Modifier.weight(1f)) {
            Text(e.item.title, color = Color.White, fontSize = 14.sp, maxLines = 2, // [TITLE-2LINE]
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
            val meta = listOfNotNull(e.item.uploader, if (showThumb) null else e.item.durationSec?.let { duration(it) }, RelativeTime.label(e.item))
                .joinToString(" \u00B7 ")
            if (meta.isNotEmpty()) Text(meta, fontSize = 12.sp,
                color = if (isSelected) Color(0xCCFFFFFF) else MolinaTheme.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            // [SEEN-BADGES] lencana hanya muncul bila ada; baris tanpa penanda tidak bertambah tinggi
            if (downloaded || played) Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (downloaded) SeenBadge("\u2193 Diunduh", if (isSelected) Color.White else MolinaTheme.Brand)
                if (played) SeenBadge("\u25B6 Pernah diputar", if (isSelected) Color.White else MolinaTheme.Muted)
            }
        }
        Box(
            Modifier.padding(start = 8.dp).size(36.dp).clip(RoundedCornerShape(18.dp))
                .border(1.dp, MolinaTheme.Brand.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
                .clickable(onClick = onDownload),
            contentAlignment = Alignment.Center,
        ) { Text("\u2193", color = MolinaTheme.Brand, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
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
            .background(if (active) MolinaTheme.Brand else Color(0x14FFFFFF))
            .border(1.dp, if (active) MolinaTheme.Brand else RowLine, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * [RESULTS-THUMB] Thumbnail 16:9 ala YouTube (96x54 dp), lencana durasi kanan bawah, garis progres zamrud [WATCH-PROGRESS] di dasar.
 * [THUMB-PREVIEW] Tekan-tahan pada thumbnail memutar 3 frame publik (/vi/<id>/1-3.jpg) bergantian selama jari ditahan.
 * Ketukan biasa tetap memutar video; tekan-tahan di luar thumbnail tetap membuka mode pilih ([held] menekan long-press baris).
 */
@Composable
private fun SearchThumb(
    url: String?, durationSec: Long?, progress: Int?, videoId: String?, held: BooleanArray, modifier: Modifier = Modifier,
) {
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var frame by remember(videoId) { mutableStateOf<ImageBitmap?>(null) }
    var holding by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(url) { bmp = if (url == null) null else SearchThumbs.load(url)?.asImageBitmap() }
    LaunchedEffect(holding, videoId) {
        if (!holding || videoId == null) { frame = null; return@LaunchedEffect }
        val frames = (1..3).mapNotNull { n -> SearchThumbs.load("https://i.ytimg.com/vi/$videoId/$n.jpg")?.asImageBitmap() }
        if (frames.isEmpty()) return@LaunchedEffect
        var i = 0
        while (true) { frame = frames[i % frames.size]; i++; delay(700) }
    }
    val shown = frame ?: bmp
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.clip(shape).background(Color(0xFF1A1A1A))
            .then(if (holding) Modifier.border(2.dp, MolinaTheme.Brand, shape) else Modifier)
            .pointerInput(videoId) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (videoId == null) return@awaitEachGesture
                    held[0] = true
                    var long = false
                    try {
                        var timedOut = true
                        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id }
                                if (ch == null || !ch.pressed || ch.isConsumed ||
                                    (ch.position - down.position).getDistance() > viewConfiguration.touchSlop
                                ) { timedOut = false; return@withTimeoutOrNull }
                            }
                        }
                        if (timedOut) {
                            long = true
                            holding = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            while (true) {
                                val ev = awaitPointerEvent()
                                ev.changes.forEach { it.consume() }
                                if (ev.changes.none { it.pressed }) break
                            }
                        }
                    } finally {
                        if (long) holding = false
                        held[0] = false
                    }
                }
            },
    ) {
        shown?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
        if (durationSec != null) Text(
            duration(durationSec), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(start = 3.dp, end = 3.dp, top = 3.dp, bottom = if (progress != null) 6.dp else 3.dp)
                .clip(RoundedCornerShape(3.dp)).background(Color(0xCC000000))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
        if (progress != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF))) {
            Box(Modifier.fillMaxWidth(progress / 100f).height(3.dp).background(MolinaTheme.Brand))
        }
    }
}
