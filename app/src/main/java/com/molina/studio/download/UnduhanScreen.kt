package com.molina.studio.download

import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import com.molina.core.common.DownloadChoices
import com.molina.core.common.DownloadErrorText
import com.molina.core.common.DownloadCommand
import com.molina.core.common.DownloadFormat
import com.molina.core.common.DownloadPaths
import com.molina.core.common.DownloadUrls
import com.molina.core.common.DownloadItemState
import com.molina.core.common.DownloadItemStatus
import com.molina.core.common.DownloadMode
import com.molina.core.common.DownloadPreset
import com.molina.core.common.DownloadReducer
import com.molina.core.common.DownloadRequest
import com.molina.core.common.DownloadEvent
import com.molina.core.common.DownloadMaintenance
import com.molina.core.common.LogRedact
import com.molina.core.common.MaintenanceKind
import com.molina.core.common.MaintenanceTone
import com.molina.studio.cinema.CinemaActions
import com.molina.studio.cinema.Win7OpenDialog
import com.molina.studio.settings.W7D
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import com.molina.studio.cinema.Win7Button
import com.molina.studio.settings.win7Card
import com.molina.studio.settings.W7IconDownloadBig
import com.molina.studio.settings.GlossBar
import com.molina.studio.settings.Win7GlassWindow
import com.molina.studio.settings.RgbFrame
import com.molina.studio.settings.MolinaExplorer
import com.molina.studio.ui.MolinaTheme
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Tab Unduhan: tempel URL (satu ketukan dari clipboard), pilih mode video/audio, pantau progres per item
 * (bar emas), lalu putar di Molina Player atau buka folder hasil di Penjelajah. Sumber kebenaran: [DownloadRunner].
 * Hasil sesi terakhir tetap tampil sampai dikosongkan.
 */
@Composable
fun UnduhanScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val run by DownloadRunner.state.collectAsState()
    val sess = run.session
    val maintKind = DownloadRunner.maintenanceKind() // null = sesi unduhan biasa

    // Formulir ada di DownloadForm (bukan rememberSaveable): tab hanya dikomposisi saat dipilih.
    var urls by remember { DownloadForm.urls }
    var video by remember { DownloadForm.video }
    var height by remember { DownloadForm.height }
    var videoExt by remember { DownloadForm.videoExt }
    var audioFmt by remember { DownloadForm.audioFmt }
    var bitrate by remember { DownloadForm.bitrate }
    var subs by remember { DownloadForm.subs }
    var subsMode by remember { DownloadForm.subsMode }
    var cover by remember { DownloadForm.cover }
    var limitRate by remember { DownloadForm.limitRate }
    var sidecar by remember { DownloadForm.sidecar }
    var perChannel by remember { DownloadForm.perChannel }
    var wifiOnly by remember { DownloadForm.wifiOnly } // [WIFI-ONLY]
    var error by remember { mutableStateOf<String?>(null) }
    var openDir by remember { mutableStateOf<File?>(null) }

    // Riwayat sesi lama + Ulangi. Riwayat baru terbuka bila diketuk; satu kartu terbuka pada satu waktu.
    val history by DownloadHistoryStore.entries.collectAsState()
    var historyOpen by remember { mutableStateOf(false) }
    var openAt by remember { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val retryUrls = if (run.running) emptyList<String>() else DownloadRunner.retryUrls()

    /** Hasil start/ulangi: galat -> Toast; sukses -> gulir ke atas supaya progres sesi baru terlihat. */
    fun afterStart(msg: String?) {
        if (msg != null) Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        else scope.launch { listState.animateScrollToItem(0) }
    }

    val doneFiles = sess.items.flatMap { it.files }
    val hasResult = run.running || sess.items.isNotEmpty() || sess.fatalError != null || run.exitCode != null
    val count = DownloadUrls.split(urls).size
    // [win7-summary2] kecepatan total item aktif, sisa waktu terlama, dan progres keseluruhan sesi.
    val liveItems = sess.items.filter { it.status == DownloadItemStatus.RUNNING }
    val liveSpeed = liveItems.sumOf { it.streams.values.firstOrNull { s -> s.speedBps != null }?.speedBps ?: 0L }
    val liveEta = liveItems.mapNotNull { it.streams.values.firstOrNull { s -> s.etaSec != null }?.etaSec }.maxOrNull()
    val overall = if (sess.items.isEmpty()) 0f else sess.items.map { x ->
        when (x.status) {
            DownloadItemStatus.DONE, DownloadItemStatus.FAILED -> 1f
            else -> ((DownloadReducer.overallPercent(x) ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
        }
    }.average().toFloat()

    // URL kiriman dari layar lain (kartu hasil pencarian Cinema): digabung ke kolom, duplikat dilewati.
    val pending = DownloadNav.pendingUrls
    LaunchedEffect(pending) {
        if (pending.isNotEmpty()) {
            val r = DownloadUrls.merge(urls, pending)
            urls = r.text
            error = null
            DownloadNav.pendingUrls = emptyList()
            val note = buildString {
                append(if (r.added > 0) "${r.added} URL ditambahkan" else "URL sudah ada di daftar")
                if (r.skipped > 0 && r.added > 0) append(", ${r.skipped} sudah ada")
                if (r.dropped > 0) append(", ${r.dropped} melewati batas ${DownloadCommand.MAX_URLS}")
                if (DownloadRunner.isRunning()) append(". Unduhan masih berjalan; mulai setelah selesai.")
            }
            Toast.makeText(ctx, note, Toast.LENGTH_SHORT).show()
        }
    }

    openDir?.let { dir ->
        Win7OpenDialog(
            onDismiss = { openDir = null },
            browse = true,
            startDir = dir,
            onCopyPath = { f -> MolinaExplorer.copyPath(ctx, f) },
            onShare = { files -> MolinaExplorer.share(ctx, files) },
            onInsertPaths = { fs -> MolinaExplorer.insertPaths(ctx, fs) },
            isVisibleInDebian = { f -> MolinaExplorer.visibleInDebian(f) },
            onPlay = { files -> MolinaExplorer.play(ctx, files) },
        )
    }

    if (confirmClear) {
        ClearHistoryDialog(
            onConfirm = { DownloadHistoryStore.clear(); openAt = null; confirmClear = false },
            onDismiss = { confirmClear = false },
        )
    }

    LazyColumn(
        modifier.fillMaxSize().background(W7D.Bg).padding(horizontal = 16.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Box(Modifier.height(4.dp)) }
        // [win7-form] formulir dalam jendela kaca Aero; frame RGB berputar hanya saat unduhan berjalan.
        item {
            RgbFrame(Modifier.fillMaxWidth(), animate = run.running) {
                Win7GlassWindow("Unduh dari URL", icon = { W7IconDownloadBig(18.dp) }) {
                    OutlinedTextField(
                        value = urls,
                        onValueChange = { urls = it; error = null },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !run.running,
                        label = { Text("URL (satu per baris)") },
                        minLines = 2,
                        maxLines = 6,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = W7D.Accent,
                            unfocusedBorderColor = W7D.Line,
                            focusedLabelColor = W7D.Accent,
                            cursorColor = W7D.Accent,
                        ),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Win7Button("Tempel", enabled = !run.running) {
                            val clip = pasteText(ctx)
                            if (clip.isBlank()) {
                                Toast.makeText(ctx, "Clipboard kosong.", Toast.LENGTH_SHORT).show()
                            } else {
                                urls = if (urls.isBlank()) clip.trim() else urls.trimEnd() + "\n" + clip.trim()
                                error = null
                            }
                        }
                        if (urls.isNotEmpty() && !run.running) {
                            TextButton(onClick = { urls = ""; error = null }) { Text("Hapus", color = W7D.TextDim) }
                        }
                    }
                    // [win7-preset] preset cepat; pilihan tetap bisa disesuaikan lewat chip di bawahnya.
                    Text("Preset", color = W7D.TextDim, fontSize = 12.sp)
                    val now = DownloadChoices(video, height, videoExt, audioFmt, bitrate, subs, subsMode, cover, limitRate, sidecar, perChannel)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DownloadPreset.values().forEach { p ->
                            Chip(p.label, p.matches(now), !run.running) {
                                val n = p.apply(now)
                                video = n.video; height = n.height; videoExt = n.videoExt
                                audioFmt = n.audioFmt; bitrate = n.bitrate
                                subs = n.subs; subsMode = n.subsMode; cover = n.cover; sidecar = n.sidecar
                            }
                        }
                    }
                    Text(DownloadPreset.HINT, color = W7D.TextDim, fontSize = 11.sp)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Video", video, !run.running) { video = true }
                            Chip("Audio", !video, !run.running) { video = false }
                        }
                        if (video) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(0 to "Terbaik", 1080 to "1080p", 720 to "720p", 480 to "480p").forEach { (h, l) ->
                                    Chip(l, height == h, !run.running) { height = h }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DownloadCommand.VIDEO_EXTS.sorted().forEach { e ->
                                    Chip(e.uppercase(Locale.ROOT), videoExt == e, !run.running) { videoExt = e }
                                }
                            }
                            Text("Subtitle", color = W7D.TextDim, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DownloadChoices.SUB_PRESETS.forEach { p ->
                                    Chip(DownloadChoices.subsLabel(p), subs == p, !run.running) { subs = p }
                                }
                            }
                            if (subs.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Chip("Sematkan", subsMode == "embed", !run.running) { subsMode = "embed" }
                                    Chip("File .srt", subsMode == "file", !run.running) { subsMode = "file" }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Chip("Poster + info", sidecar, !run.running) { sidecar = !sidecar }
                            }
                            if (sidecar) {
                                Text(
                                    "Poster (.jpg) dan info (.info.json) disimpan di samping video untuk rak Molina Cinema; poster bisa ikut tampil di Galeri.",
                                    color = W7D.TextDim, fontSize = 11.sp,
                                )
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DownloadCommand.AUDIO_FORMATS.sorted().forEach { f ->
                                    Chip(f.uppercase(Locale.ROOT), audioFmt == f, !run.running) { audioFmt = f }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DownloadCommand.BITRATES.sortedBy { it.dropLast(1).toInt() }.forEach { b ->
                                    Chip(b, bitrate == b, !run.running) { bitrate = b }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Chip("Sampul + tag", cover, !run.running) { cover = !cover }
                            }
                        }
                        Text("Batas kecepatan", color = W7D.TextDim, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DownloadChoices.LIMIT_RATES.forEach { r ->
                                Chip(DownloadChoices.rateLabel(r), limitRate == r, !run.running) { limitRate = r }
                            }
                        }
                        // [DL-PERCHANNEL] subfolder per kanal; tampil sebagai baris rak di Molina Cinema
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Folder per kanal", perChannel, !run.running) { perChannel = !perChannel }
                        }
                        if (perChannel) {
                            Text(
                                "Berkas disimpan di subfolder bernama kanal (mis. Video/Nama Kanal/) dan tampil sebagai baris rak di Molina Cinema.",
                                color = W7D.TextDim, fontSize = 11.sp,
                            )
                        }
                        // [WIFI-ONLY] hemat kuota: unduhan baru hanya mulai di jaringan tidak berkuota
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Hanya Wi-Fi", wifiOnly, !run.running) {
                                DownloadPrefs.setWifiOnly(ctx.applicationContext, !wifiOnly)
                            }
                        }
                        if (wifiOnly) {
                            Text(
                                "Unduhan hanya dimulai di jaringan tidak berkuota. Hotspot yang ditandai berkuota juga ditolak.",
                                color = W7D.TextDim, fontSize = 11.sp,
                            )
                        }
                    }
                    error?.let { Text(it, color = MolinaTheme.Bad, fontSize = 13.sp) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (run.running) {
                            Win7Button("Batalkan", accent = MolinaTheme.Bad) { DownloadRunner.cancel() }
                        } else {
                            Win7Button(
                                if (count > 0) "Mulai unduh ($count)" else "Mulai unduh",
                                isDefault = true,
                                enabled = count > 0,
                            ) {
                                val req = DownloadRequest(
                                    urls = urls.split(Regex("\\s+")),
                                    mode = if (video) DownloadMode.VIDEO else DownloadMode.AUDIO,
                                    height = height,
                                    videoExt = videoExt,
                                    audioFormat = audioFmt,
                                    bitrate = bitrate,
                                    subs = if (video) subs else emptyList(),
                                    subsMode = subsMode,
                                    cover = cover,
                                    limitRate = limitRate.ifEmpty { null },
                                    sidecar = video && sidecar,
                                    perChannel = perChannel,
                                )
                                val msg = DownloadRunner.start(ctx.applicationContext, req)
                                error = msg
                                if (msg == null) {
                                    urls = ""
                                    DownloadPrefs.save(ctx.applicationContext, DownloadForm.choices())
                                }
                            }
                        }
                        if (!run.running && hasResult) {
                            TextButton(onClick = { DownloadRunner.clear(); error = null }) {
                                Text("Kosongkan hasil", color = W7D.TextDim)
                            }
                        }
                    }
                    if (!run.running) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            MaintenanceKind.values().forEach { k ->
                                Win7Button(k.label, compact = true) { afterStart(DownloadRunner.startMaintenance(ctx.applicationContext, k)) }
                            }
                        }
                    }
                }
            }
        }
        if (hasResult) {
            if (maintKind != null) {
                item { MaintenanceSummary(maintKind, run.running, sess.fatalError != null, sess.doctor != null || sess.update != null) }
                sess.doctor?.let { d -> item { DoctorCard(d) } }
                sess.update?.let { u -> item { UpdateCard(u) } }
            } else {
                item { SessionSummary(run.running, sess.ok, sess.failed, sess.cancelled, sess.finished, sess.items.size, liveSpeed, liveEta, overall) }
            }
            sess.fatalError?.let { fe -> item { Text(fe, color = MolinaTheme.Bad, fontSize = 13.sp) } }
            if (sess.fatalError == null && !sess.finished && !run.running && run.exitCode != null && run.exitCode != 0) {
                item { Text(DownloadErrorText.STOPPED, color = MolinaTheme.Bad, fontSize = 13.sp) }
            }
            items(sess.warnings.takeLast(3)) { w -> Text(w, color = MolinaTheme.Warn, fontSize = 12.sp) }
            items(sess.items, key = { it.index }) { item ->
                ItemCard(
                    ctx, item,
                    onRetry = if (!run.running && item.status == DownloadItemStatus.FAILED && item.url.isNotEmpty()) {
                        { afterStart(DownloadRunner.retry(ctx.applicationContext, item.url)) }
                    } else null,
                )
            }
            if (!run.running && doneFiles.isNotEmpty()) {
                item {
                    Win7Button("Buka folder") {
                        val dir = folderOf(doneFiles.first().path)
                        if (dir == null) Toast.makeText(ctx, "Folder hasil ada di dalam Debian dan belum bisa dibuka dari sini.", Toast.LENGTH_LONG).show()
                        else openDir = dir
                    }
                }
            }
            if (!run.running && retryUrls.isNotEmpty()) {
                item {
                    Win7Button("Ulangi yang gagal (${retryUrls.size})") {
                        afterStart(DownloadRunner.retry(ctx.applicationContext))
                    }
                }
            }
        }
        // Sesi terbaru sudah tampil di atas (hasil sesi) bila hasilnya belum dikosongkan: jangan digandakan.
        historySection(
            entries = if (hasResult && !run.running && maintKind == null) history.drop(1) else history,
            running = run.running,
            open = historyOpen,
            onToggle = { historyOpen = !historyOpen },
            openAt = openAt,
            onToggleEntry = { at -> openAt = if (openAt == at) null else at },
            onRetry = { e -> afterStart(DownloadRunner.start(ctx.applicationContext, e.retryRequest())) },
            onDelete = { e ->
                if (openAt == e.at) openAt = null
                DownloadHistoryStore.remove(e.at)
            },
            onClearAll = { confirmClear = true },
        )
        item { Box(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = W7D.SelFill,
            selectedLabelColor = W7D.Accent,
            labelColor = W7D.TextDim,
        ),
    )
}

@Composable
private fun SessionSummary(
    running: Boolean, ok: Int, failed: Int, cancelled: Boolean, finished: Boolean, total: Int,
    speedBps: Long, etaSec: Long?, overall: Float,
) { // [win7-summary2-fn]
    val text = when {
        running && total == 0 -> "Menyiapkan unduhan…"
        running -> "Berjalan: $total item"
        cancelled -> "Dibatalkan: $ok berhasil, $failed gagal. Berkas .part tetap ada; URL yang sama dilanjutkan."
        finished -> "Selesai: $ok berhasil, $failed gagal"
        else -> "Sesi berhenti"
    }
    val dot = when {
        running -> W7D.Accent
        failed > 0 -> MolinaTheme.Warn
        finished -> MolinaTheme.Good
        else -> W7D.TextDim
    }
    Column(
        Modifier.fillMaxWidth().win7Card(dot).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Text(text, color = W7D.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        if (running && total > 0) {
            GlossBar(overall, Color(0xFF8FC4EE), Color(0xFF2D6DB5), height = 10.dp)
            val live = buildString {
                if (speedBps > 0L) append("Total ").append(fmtSize(speedBps)).append("/dtk")
                if (etaSec != null) {
                    if (length > 0) append(" · ")
                    append("sisa ").append(fmtEta(etaSec))
                }
            }
            if (live.isNotEmpty()) Text(live, color = W7D.TextDim, fontSize = 12.sp)
        }
    }
}

private fun toneColor(t: MaintenanceTone): Color = when (t) {
    MaintenanceTone.GOOD -> MolinaTheme.Good
    MaintenanceTone.WARN -> MolinaTheme.Warn
    MaintenanceTone.BAD -> MolinaTheme.Bad
    MaintenanceTone.BUSY -> W7D.Accent
}

@Composable
private fun MaintenanceSummary(kind: MaintenanceKind, running: Boolean, fatal: Boolean, hasResult: Boolean) {
    val dot = when {
        running -> W7D.Accent
        fatal -> MolinaTheme.Bad
        hasResult -> MolinaTheme.Good
        else -> W7D.TextDim
    }
    Row(
        Modifier.fillMaxWidth().win7Card(dot).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(
            DownloadMaintenance.summaryText(kind, running, fatal, hasResult),
            color = W7D.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun DoctorCard(d: DownloadEvent.Doctor) {
    val tone = toneColor(DownloadMaintenance.doctorTone(d))
    Column(
        Modifier.fillMaxWidth().win7Card(tone).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DownloadMaintenance.doctorLines(d).forEach { l ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(l.label, color = W7D.TextDim, fontSize = 12.sp, modifier = Modifier.width(112.dp))
                Text(
                    l.value, color = if (l.ok) W7D.Ink else MolinaTheme.Bad, fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            }
        }
        DownloadMaintenance.issueHints(d).forEach { h -> Text(h, color = MolinaTheme.Warn, fontSize = 12.sp) }
    }
}

@Composable
private fun UpdateCard(u: DownloadEvent.Update) {
    val tone = toneColor(DownloadMaintenance.updateTone(u))
    Column(Modifier.fillMaxWidth().win7Card(tone).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            DownloadMaintenance.updateText(u),
            color = if (u.status == "failed") MolinaTheme.Bad else W7D.Ink, fontSize = 13.sp,
        )
    }
}

/** Warna bilah per status: biru (antre/mengunduh), emas (memproses), hijau (selesai). */
private fun barColors(status: DownloadItemStatus): Pair<Color, Color> = when (status) {
    DownloadItemStatus.DONE -> Color(0xFF7BE87A) to Color(0xFF1E9E1A)
    DownloadItemStatus.PROCESSING -> MolinaTheme.Gold to Color(0xFFB07F22)
    else -> Color(0xFF8FC4EE) to Color(0xFF2D6DB5)
}

@Composable
private fun ItemCard(ctx: Context, item: DownloadItemState, onRetry: (() -> Unit)? = null) { // [win7-itemcard]
    val pct = DownloadReducer.overallPercent(item)
    val title = item.title ?: LogRedact.target(item.url).ifEmpty { "Item ${item.index}" }.take(70)
    val color = when (item.status) {
        DownloadItemStatus.DONE -> MolinaTheme.Good
        DownloadItemStatus.FAILED -> MolinaTheme.Bad
        else -> W7D.Accent
    }
    Column(
        Modifier.fillMaxWidth().win7Card(color).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "${item.index}. $title", color = W7D.Ink, fontSize = 14.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (item.status != DownloadItemStatus.FAILED) {
            val frac: Float? =
                if (pct != null && item.status != DownloadItemStatus.PROCESSING) (pct / 100.0).toFloat().coerceIn(0f, 1f)
                else if (item.status == DownloadItemStatus.DONE) 1f
                else null
            val bc = barColors(item.status)
            GlossBar(frac, bc.first, bc.second)
        }
        Text(statusLine(item, pct), color = if (item.status == DownloadItemStatus.FAILED) MolinaTheme.Bad else W7D.TextDim, fontSize = 12.sp)
        if (item.status == DownloadItemStatus.FAILED && onRetry != null) {
            TextButton(onClick = onRetry) { Text("Ulangi", color = W7D.Accent) }
        }
        if (item.status == DownloadItemStatus.DONE) {
            item.files.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        f.name + "  " + fmtSize(f.sizeBytes), color = W7D.Ink, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { playFile(ctx, f.path) }) { Text("Putar", color = MolinaTheme.Cinema) }
                }
            }
        }
    }
}

private fun statusLine(item: DownloadItemState, pct: Double?): String = when (item.status) {
    DownloadItemStatus.QUEUED -> "Antre"
    DownloadItemStatus.RUNNING -> {
        val p = item.streams.values.firstOrNull { it.speedBps != null }
        buildString {
            append(pct?.let { String.format(Locale.ROOT, "%.0f%%", it) } ?: "Mengunduh")
            p?.speedBps?.let { append(" · ").append(fmtSize(it)).append("/dtk") }
            p?.etaSec?.let { append(" · sisa ").append(fmtEta(it)) }
        }
    }
    DownloadItemStatus.PROCESSING -> when (item.stage) {
        null -> "Memproses"
        "merge" -> "Menggabungkan video dan audio"
        "extract" -> "Mengonversi audio"
        "recode" -> "Menyesuaikan format agar kompatibel (H.264/AAC)"
        "subs" -> "Menyematkan subtitle"
        "cover" -> "Menyematkan sampul"
        else -> "Memproses: ${item.stage}"
    }
    DownloadItemStatus.DONE -> if (item.existed) "Selesai (berkas sudah ada)" else "Selesai"
    DownloadItemStatus.FAILED -> item.errorMsg ?: "Gagal"
}

private fun fmtSize(b: Long): String = DownloadFormat.size(b)

private fun fmtEta(s: Long): String = DownloadFormat.eta(s)

private fun pasteText(ctx: Context): String {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = cm.primaryClip ?: return ""
    if (clip.itemCount == 0) return ""
    return clip.getItemAt(0).coerceToText(ctx)?.toString().orEmpty()
}

/** Path Debian (`/sdcard/...`) -> File Android. null bila di luar /sdcard (mis. cadangan di dalam rootfs Debian). */
private fun androidFile(debianPath: String): File? {
    val base = Environment.getExternalStorageDirectory().absolutePath
    return DownloadPaths.toAndroid(debianPath, base)?.let { File(it) }
}

private fun folderOf(debianPath: String): File? =
    androidFile(debianPath)?.parentFile?.takeIf { it.isDirectory }

private fun playFile(ctx: Context, debianPath: String) {
    val f = androidFile(debianPath)
    when {
        f == null -> Toast.makeText(ctx, "Berkas ada di dalam Debian dan belum bisa diputar dari sini.", Toast.LENGTH_LONG).show()
        !f.isFile -> Toast.makeText(ctx, "Berkas tidak ditemukan (mungkin sudah dipindah atau dihapus).", Toast.LENGTH_LONG).show()
        else -> if (!CinemaActions.playPath(ctx, f.absolutePath)) {
            Toast.makeText(ctx, "Molina Player tidak bisa dibuka.", Toast.LENGTH_SHORT).show()
        }
    }
}
