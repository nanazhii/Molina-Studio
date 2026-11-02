package com.molina.studio.cinema

import android.content.Context
import android.content.ContextWrapper
import android.os.Environment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.core.common.ChannelTidy
import com.molina.core.common.MolinaDir
import com.molina.core.common.ResumeMark
import com.molina.core.common.ShelfCard
import com.molina.core.common.TvGuide
import com.molina.studio.AppPaths
import com.molina.studio.ui.MolinaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.molina.core.common.CrashContext
import com.molina.core.common.LogLevel
import com.molina.studio.log.LogBus

private val TvBg = Color(0xFF0E0B10)
private val TileShape = RoundedCornerShape(6.dp)
private val TvPanel = Color(0xFF17121B)

private tailrec fun Context.lifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.lifecycleOwner()
    else -> null
}

@Volatile private var tidyWarned = false

/**
 * Rencana "Rapikan ke folder kanal" hanya fitur tambahan. Kegagalan apa pun, termasuk error init kelas
 * (ExceptionInInitializerError / NoClassDefFoundError), tidak boleh mematikan Mode TV: hasil null = tombol
 * Rapikan disembunyikan. OutOfMemoryError tetap dilempar. Dicatat sekali per proses agar log tidak banjir.
 */
private fun safeTidyPlan(dir: File): ChannelTidy.Plan? = try {
    ChannelTidy.plan(dir)
} catch (t: Throwable) {
    if (t is OutOfMemoryError) throw t
    if (!tidyWarned) {
        tidyWarned = true
        LogBus.log("sistem", LogLevel.Warn, "TvBrowser", "ChannelTidy.plan gagal: ${t::class.java.simpleName}: ${t.message}")
    }
    null
}

private class TvData(val rows: List<TvGuide.Row>, val cards: Map<String, ShelfCard.Card>, val tidy: ChannelTidy.Plan? = null)

private fun tvPlaces(ctx: android.content.Context): List<Pair<String, File>> {
    val p = AppPaths.get(ctx)
    return listOf(
        "Unduhan" to p.dir(MolinaDir.Downloads),
        "Video" to p.dir(MolinaDir.Video),
        "Audio" to p.dir(MolinaDir.Audio),
        "IPTV" to p.dir(MolinaDir.Iptv),
        "Molina" to p.appRoot,
        "Film" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
        "Musik" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
    )
}

/**
 * Mode TV penjelajah berkas Molina Cinema (SIDE-5, tahap 2), dirancang untuk ponsel potret layar sentuh:
 * bilah status (jam, ruang bebas, tutup), menu saluran drop-up (tombol ikon pojok kanan bawah), baris-baris kartu yang bisa digeser, dan
 * panel info ala EPG. Ketuk = buka (folder masuk, video/audio putar); tekan lama = tampilkan info di panel.
 * Model dari TvGuide/ShelfCard (teruji di :core:common); semua pembacaan berkas di Dispatchers.IO.
 * Pemanggil memberi [onPlay] (mis. CinemaActions.playPath) dan [onClose]; sakelar/titik masuk menyusul (tahap 3).
 */
@Composable
internal fun TvBrowser(
    onPlay: (File) -> Unit,
    onClose: () -> Unit,
    recents: List<CinemaRecent.Item> = emptyList(),
    onPlayRecent: (CinemaRecent.Item) -> Unit = {},
    onClearRecent: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val channels = remember { TvGuide.channels(tvPlaces(ctx)) }
    val resumeDirs = remember {
        listOf(File(ctx.filesDir, "watch_later"), File(File(ctx.filesDir, "state"), "watch_later"))
    }
    var idx by remember { mutableIntStateOf(0) }
    var dir by remember { mutableStateOf(channels.firstOrNull()?.dir) }
    // Saluran 0 "Terakhir" (bekas Rak film Beranda): riwayat putar; jadi layar awal bila ada riwayat.
    var recentMode by remember { mutableStateOf(recents.isNotEmpty()) }
    // Hapus riwayat butuh dua ketukan; kembali ke awal sendiri setelah 3 detik.
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(confirmClear) { if (confirmClear) { delay(3_000L); confirmClear = false } }
    var focus by remember { mutableStateOf<TvGuide.Item?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<TvSheet?>(null) }
    val guard = remember(channels) {
        TvGuide.guard(tvVolumes(), AppPaths.get(ctx).appRoot, channels.map { it.dir })
    }
    val ops = remember(channels) {
        TvOps(ctx, scope, tvVolumes(), guard, resumeDirs) { focus = null; tick++ }
    }

    // Muat ulang setelah layar kembali dari pemutar (ON_PAUSE lalu ON_RESUME), agar garis "dilanjutkan" mutakhir.
    val owner = remember { ctx.lifecycleOwner() }
    DisposableEffect(owner) {
        var armed = false
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) {
                armed = true
            } else if (e == Lifecycle.Event.ON_RESUME && armed) {
                armed = false
                tick++
            }
        }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }

    // Penanda layar untuk laporan crash (baris "Mode").
    DisposableEffect(Unit) {
        CrashContext.overlay = "Mode TV"
        onDispose { if (CrashContext.overlay == "Mode TV") CrashContext.overlay = null }
    }

    val clock by produceState("") {
        while (true) {
            value = TvGuide.clock(System.currentTimeMillis())
            delay(30_000L)
        }
    }
    val free by produceState<String?>(null, dir) { value = dir?.let { d -> withContext(Dispatchers.IO) { TvGuide.freeLabel(d) } } }
    val data by produceState<TvData?>(null, dir, tick, recentMode) {
        val d = dir
        value = if (d == null || recentMode) null else withContext(Dispatchers.IO) {
            val rows = TvGuide.rowsWithShelves(d)
            val vids = rows.flatMap { it.items }.filter { it.kind == TvGuide.Kind.Video }
                .map { ShelfCard.Entry(it.file.path, it.name, true) }
            TvData(rows, ShelfCard.build(vids, max = vids.size, resumeDirs = resumeDirs).associateBy { it.ref }, safeTidyPlan(d))
        }
    }

    val recentCards by produceState(initialValue = emptyList<ShelfCard.Card>(), recents, tick, recentMode) {
        value = if (!recentMode) emptyList() else withContext(Dispatchers.IO) {
            ShelfCard.build(recents.map { ShelfCard.Entry(it.ref, it.title, it.video) }, resumeDirs = resumeDirs)
        }
    }

    Column(Modifier.fillMaxSize().background(TvBg)) {
        // ---- bilah status
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text("Molina Cinema", color = MolinaTheme.Gold, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                val chLabel = if (recentMode) "Terakhir" else channels.getOrNull(idx)?.label.orEmpty()
                Text(
                    " \u00B7 " + chLabel, color = Win7.TextDim, fontSize = 13.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                )
            }
            free?.let { Text(it, color = Win7.TextDim, fontSize = 11.sp, modifier = Modifier.padding(end = 10.dp)) }
            Text(clock, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                "Tutup", color = Win7.TextDim, fontSize = 12.sp,
                modifier = Modifier.padding(start = 14.dp).clickable(onClick = onClose).padding(vertical = 4.dp)
            )
        }
        if (channels.isEmpty()) {
            Text("Belum ada folder media. Buat folder di /sdcard/Molina atau beri izin penyimpanan.", color = Win7.TextDim, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
            return@Column
        }
        // ---- jejak folder + tombol naik
        val root = channels.getOrNull(idx)?.dir
        val cur = dir
        if (!recentMode && cur != null && root != null && cur.path != root.path) {
            Text(
                "\u2039  " + cur.name,
                color = MolinaTheme.Gold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
                    .clickable { cur.parentFile?.let { dir = it; focus = null } }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        } else {
            Spacer(Modifier.height(6.dp))
        }
        // ---- baris kartu
        val d = data
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (recentMode) {
                if (recentCards.isEmpty()) {
                    Text("Belum ada yang diputar.", color = Win7.TextDim, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
                } else {
                    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Terakhir diputar \u00B7 " + recentCards.size, color = Color.White, fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)
                                )
                                Text(
                                    if (confirmClear) "Ketuk lagi untuk hapus" else "Hapus riwayat",
                                    color = if (confirmClear) Color(0xFFFF8A80) else Win7.TextDim, fontSize = 12.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            if (confirmClear) { confirmClear = false; focus = null; onClearRecent() } else confirmClear = true
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                )
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(recentCards, key = { it.ref }) { card ->
                                    ShelfCardView(card, onLong = {
                                        // Info panel hanya untuk berkas lokal; tautan (http/rtsp) tidak punya info berkas.
                                        val f = File(card.ref)
                                        if (card.ref.startsWith("/") && f.exists()) {
                                            focus = TvGuide.Item(f, TvGuide.kindOf(f), if (f.isFile) f.length() else 0L, f.lastModified())
                                        }
                                    }) {
                                        onPlayRecent(recents.firstOrNull { it.ref == card.ref } ?: CinemaRecent.Item(card.ref, card.title, card.video))
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (d == null) {
                Text("Memuat\u2026", color = Win7.TextDim, fontSize = 12.sp, modifier = Modifier.padding(14.dp))
            } else if (d.rows.isEmpty()) {
                Text("Folder ini kosong.", color = Win7.TextDim, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(d.rows, key = { it.key }) { row ->
                        Column {
                            // Baris rak kanal: sampul bulat + judul emas + lencana jumlah; ketuk untuk membuka folder kanal.
                            val rowFolder = row.folder
                            if (rowFolder != null) {
                                Row(
                                    Modifier.fillMaxWidth().clickable { dir = rowFolder; focus = null }.padding(bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ChannelCover(row.title, row.coverFile?.let { d.cards[it.path] })
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        row.title, color = MolinaTheme.Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    CountBadge(TvGuide.badge(row.total))
                                    Text("  \u203A", color = MolinaTheme.Gold, fontSize = 13.sp)
                                }
                            } else {
                                Text(
                                    row.title + " \u00B7 " + row.total, color = Color.White, fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp)
                                )
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(row.items, key = { it.file.path }) { item ->
                                    val open: () -> Unit = {
                                        when (item.kind) {
                                            TvGuide.Kind.Folder -> { dir = item.file; focus = null }
                                            TvGuide.Kind.Video, TvGuide.Kind.Audio -> onPlay(item.file)
                                            TvGuide.Kind.Other -> focus = item
                                        }
                                    }
                                    val card = d.cards[item.file.path]
                                    if (card != null) ShelfCardView(card, onLong = { focus = item }) { open() }
                                    else TvTile(item, onLong = { focus = item }) { open() }
                                }
                            }
                        }
                    }
                }
            }
        }
        // ---- bilah bawah: tombol saluran (kanan) + panel info (EPG)
        Row(Modifier.fillMaxWidth().background(TvPanel), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) {
        TvInfoPanel(
            item = focus,
            extra = focus?.let { f -> data?.cards?.get(f.file.path)?.let { c -> listOfNotNull(c.duration, c.subtitle.takeIf { it.isNotEmpty() }).joinToString(" \u00B7 ") } },
            fallback = (if (recentMode) "Terakhir diputar \u00B7 " + recentCards.size + " item" else cur?.path.orEmpty()) + (data?.let { dd -> "  \u00B7  " + dd.rows.filter { !it.shelf }.joinToString(", ") { it.total.toString() + " " + it.title.lowercase() } } ?: ""),
            resumeSec = focus?.let { f -> data?.cards?.get(f.file.path)?.resumeSec },
            tidy = data?.tidy,
            onReset = { f ->
                scope.launch {
                    withContext(Dispatchers.IO) { ResumeMark.clear(resumeDirs, f.file.path) }
                    tick++
                }
            },
            onOpen = { f ->
                when (f.kind) {
                    TvGuide.Kind.Folder -> { dir = f.file; focus = null }
                    TvGuide.Kind.Video, TvGuide.Kind.Audio -> onPlay(f.file)
                    TvGuide.Kind.Other -> {}
                }
            },
            onClear = { focus = null },
            ops = ops,
            onSheet = { sheet = it }
        )
            }
            TvChannelMenu(
                channels = channels,
                selected = if (recentMode) -1 else idx,
                recentCount = recents.size,
                onRecent = { if (recentMode) tick++ else { recentMode = true; focus = null } }
            ) { ch ->
                recentMode = false
                if (ch.number - 1 == idx) tick++ else { idx = ch.number - 1; dir = ch.dir; focus = null }
            }
        }
    }
    TvOpsDialogs(sheet, ops, channels) { sheet = null }
}

@Composable
private fun TvTile(item: TvGuide.Item, onLong: () -> Unit, onClick: () -> Unit) {
    val folder = item.kind == TvGuide.Kind.Folder
    val label = when (item.kind) {
        TvGuide.Kind.Folder -> "FOLDER"
        TvGuide.Kind.Audio -> "AUDIO"
        else -> "BERKAS"
    }
    Column(
        Modifier.width(104.dp).pointerInput(item.file.path) { detectTapGestures(onLongPress = { onLong() }, onTap = { onClick() }) }
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(TileShape)
                .background(Brush.verticalGradient(listOf(Color(0xFF241B2B), Color(0xFF0E0B10))), TileShape)
                .border(1.dp, if (folder) MolinaTheme.Gold else Win7.Line, TileShape),
            Alignment.Center
        ) {
            Text(label, color = Win7.TextDim, fontSize = 9.sp, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
            Text(ShelfCard.monogram(item.title), color = MolinaTheme.Gold, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text(item.title, color = Win7.Ink, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TvInfoPanel(
    item: TvGuide.Item?,
    extra: String?,
    fallback: String,
    resumeSec: Int?,
    tidy: ChannelTidy.Plan?,
    onReset: (TvGuide.Item) -> Unit,
    onOpen: (TvGuide.Item) -> Unit,
    onClear: () -> Unit,
    ops: TvOps,
    onSheet: (TvSheet) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(TvPanel).padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
    ) {
        if (ops.message.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ops.message + (if (ops.busy) ops.progress?.let { " " + (it * 100).toInt() + "%" }.orEmpty() else ""),
                    color = if (ops.ok) MolinaTheme.Gold else Color(0xFFFF8A80), fontSize = 12.sp,
                    modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis
                )
                if (ops.busy) {
                    Text("Batal", color = Win7.TextDim, fontSize = 12.sp, modifier = Modifier.clickable { ops.cancelOp() }.padding(8.dp))
                } else if (ops.undo != null) {
                    Text("Urungkan", color = MolinaTheme.Gold, fontSize = 12.sp, modifier = Modifier.clickable { ops.undoTrash() }.padding(8.dp))
                } else if (ops.tidyUndo != null) {
                    Text("Urungkan", color = MolinaTheme.Gold, fontSize = 12.sp, modifier = Modifier.clickable { ops.undoTidy() }.padding(8.dp))
                }
            }
        }
        if (item == null) {
            Text(fallback, color = Win7.TextDim, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Tekan lama kartu untuk melihat info.", color = Win7.TextDim, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
            if (tidy != null && tidy.items.isNotEmpty() && !ops.busy) {
                // [CINEMA-TIDY] video lama yang masih campur di folder ini -> folder per kanal (dari .info.json sidecar)
                Text(
                    "Rapikan " + tidy.items.size + " video ke folder kanal", color = MolinaTheme.Gold, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MolinaTheme.Gold, RoundedCornerShape(12.dp))
                        .clickable { onSheet(TvSheet.Tidy(tidy)) }.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        } else {
            Text(item.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(TvGuide.describe(item, extra), color = Win7.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            if (resumeSec != null) {
                Text(
                    "Dilanjutkan di " + ShelfCard.clock(resumeSec), color = MolinaTheme.Gold, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (item.kind != TvGuide.Kind.Other) {
                    Text(
                        if (item.kind == TvGuide.Kind.Folder) "Buka" else "Putar",
                        color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MolinaTheme.Gold)
                            .clickable { onOpen(item) }.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
                if (resumeSec != null) {
                    Text(
                        "Mulai dari awal", color = MolinaTheme.Gold, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, MolinaTheme.Gold, RoundedCornerShape(12.dp))
                            .clickable { onReset(item) }.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
                Text(
                    "Tutup info", color = Win7.TextDim, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Win7.Line, RoundedCornerShape(12.dp))
                        .clickable(onClick = onClear).padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
            if (!ops.busy) {
                val editable = TvGuide.canModify(item.file, ops.guard)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editable) TvAction("Ganti nama") { onSheet(TvSheet.Rename(item.file)) }
                    TvAction("Salin") { onSheet(TvSheet.Dest(item.file, false)) }
                    if (editable) TvAction("Pindah") { onSheet(TvSheet.Dest(item.file, true)) }
                    if (editable) TvAction("Hapus", danger = true) { onSheet(TvSheet.Delete(item.file)) }
                }
            }
        }
    }
}

@Composable
private fun TvAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = if (danger) Color(0xFFFF8A80) else Win7.Ink
    Text(
        label, color = c, fontSize = 11.sp, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x14FFFFFF))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/**
 * Tombol ikon kisi di pojok kanan bawah. Ketuk ATAU geser ke atas dari tombol membuka menu saluran ke atas
 * (drop-up) dengan getar ringan. Isi menu: 0 = Terakhir (riwayat putar), 1..n = folder saluran.
 */
@Composable
private fun TvChannelMenu(
    channels: List<TvGuide.Channel>,
    selected: Int,
    recentCount: Int,
    onRecent: () -> Unit,
    onPick: (TvGuide.Channel) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    fun show() {
        if (!open) {
            open = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    Box(
        Modifier.width(64.dp).pointerInput(Unit) {
            var dy = 0f
            detectVerticalDragGestures(
                onDragStart = { dy = 0f },
                onVerticalDrag = { change, amount ->
                    dy += amount
                    if (dy < -24.dp.toPx()) { change.consume(); show() }
                }
            )
        },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.padding(vertical = 6.dp)) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (open) Color(0x33FFFFFF) else Color(0x14FFFFFF))
                    .border(1.dp, if (open) MolinaTheme.Gold else Color.Transparent, RoundedCornerShape(14.dp))
                    .clickable { if (open) open = false else show() },
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(20.dp)) {
                    val gap = 3.dp.toPx()
                    val cell = (size.width - gap) / 2f
                    for (r in 0..1) for (c in 0..1) {
                        drawRoundRect(
                            color = MolinaTheme.Gold,
                            topLeft = Offset(c * (cell + gap), r * (cell + gap)),
                            size = Size(cell, cell),
                            cornerRadius = CornerRadius(2.dp.toPx())
                        )
                    }
                }
            }
            if (recentCount > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp)
                        .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                        .clip(CircleShape).background(MolinaTheme.Gold).padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (recentCount > 9) "9+" else recentCount.toString(), color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            if (open) Popup(
                alignment = Alignment.BottomEnd,
                offset = IntOffset(0, -with(density) { 56.dp.roundToPx() }),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true)
            ) {
                Column(
                    Modifier.width(210.dp).heightIn(max = 440.dp)
                        .clip(RoundedCornerShape(14.dp)).background(TvPanel)
                        .border(1.dp, MolinaTheme.Gold, RoundedCornerShape(14.dp))
                        .verticalScroll(rememberScrollState()).padding(vertical = 6.dp)
                ) {
                    TvMenuRow("0", "Terakhir" + if (recentCount > 0) " \u00B7 $recentCount" else "", selected == -1) { open = false; onRecent() }
                    channels.forEach { ch ->
                        TvMenuRow(ch.number.toString(), ch.label, ch.number - 1 == selected) { open = false; onPick(ch) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvMenuRow(number: String, label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp)
            .background(if (on) Color(0x26FFFFFF) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(number, color = MolinaTheme.Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
        Text(label, color = if (on) Color.White else Win7.TextDim, fontSize = 14.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal)
    }
}
