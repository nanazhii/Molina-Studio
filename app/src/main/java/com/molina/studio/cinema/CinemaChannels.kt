package com.molina.studio.cinema

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
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
import com.molina.core.net.IptvChannel
import com.molina.core.net.IptvFetch
import com.molina.core.net.IptvLoad
import com.molina.core.net.IptvLocal
import com.molina.core.net.IptvTrust // [IPTV-TRUST]
import com.molina.core.system.MolinaDir
import com.molina.core.system.MolinaPaths
import com.molina.core.net.M3uParser
import com.molina.core.net.M3uResult
import com.molina.studio.AppPaths
import com.molina.studio.R
import com.molina.studio.common.TabFolderIcon
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

/** [IPTV-BUNDLED] Baca feed Molina dari aset APK. Memblokir: panggil dari thread IO. */
private fun bundledLoad(ctx: android.content.Context, ref: String): IptvLoad = try { // [RADIO-BUNDLED] bundledLoad: aset dipilih dari ref sumber
    val asset = IptvSources.bundledAssetFor(ref) ?: throw java.io.IOException("bukan sumber bawaan")
    IptvLocal.loadBundled(ctx.assets.open(asset).use { it.readBytes() })
} catch (e: java.io.IOException) {
    IptvLoad.Fail("Feed bawaan tidak ditemukan di aplikasi.")
}

/** [IPTV-NAT-HTTP] Salin daftar channel nasional HTTP (aset) ke folder IPTV sebagai berkas biasa. Memblokir: panggil dari thread IO. */
private fun copyNationalHttp(ctx: android.content.Context, dir: File): File? = try {
    dir.mkdirs()
    val out = File(dir, IptvSources.NATIONAL_HTTP_FILE)
    ctx.assets.open(IptvSources.NATIONAL_HTTP_ASSET).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
    out
} catch (e: java.io.IOException) {
    null
}

/** [IPTV-NAT-HTTP] Tindakan impor channel nasional HTTP (opsional, di luar Molina Feed). */
@Composable
private fun ImportHttpAction(onClick: () -> Unit) {
    Text(
        "Impor channel nasional (HTTP)",
        color = MolinaTheme.Warn, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

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
    var grupTab by remember { mutableIntStateOf(0) } // [GRUP-TAB] 0 = Nasional, 1 = Daerah, 2 = Lainnya, 3 = Semua
    var radioGroup by remember { mutableStateOf<String?>(null) } // [RADIO-TAB] state: null = Semua, selain itu nama kota (group-title)
    var errOnly by remember { mutableStateOf(false) } // [ERR-FILTER] bar ringkasan ERR = tombol: hanya tampilkan channel ERR
    var confirmPrune by remember { mutableStateOf(false) } // [ERR-PRUNE] dua ketukan, kembali sendiri setelah 3 detik
    var confirmImport by remember { mutableStateOf(false) } // [IPTV-NAT-HTTP] dialog peringatan impor channel nasional HTTP
    var pendingInsecure by remember { mutableStateOf<ChEntry?>(null) } // [IPTV-TRUST-EXEMPT] channel pengecualian menunggu konfirmasi
    var pruneNote by remember { mutableStateOf("") } // [ERR-PRUNE] catatan hasil, ditempel ke status setelah muat ulang
    var selected by remember { mutableIntStateOf(1) }
    var clock by remember { mutableStateOf(chClock()) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    // [UNPLAYABLE-CHIP] channel yang pernah memicu penjaga entri palsu ditandai broken (chip "ERR", lewati di antrean, tetap bisa dicoba manual) [CHIP-SPLIT]
    fun markBad(list: List<IptvChannel>): List<IptvChannel> {
        val bad = com.molina.cinema.UnplayableStore.all(ctx)
        if (bad.isEmpty()) return list
        val marked = list.map { c -> if (!c.drm && !c.broken && c.url in bad) IptvChannel(c.name, c.url, c.group, c.logo, c.headers, false, true, c.unverified) else c }
        return if (current == IptvSources.RADIO_JATIM_FEED) marked.sortedBy { it.broken } else marked // [RADIO-DEAD] sort: stabil, ERR turun ke bawah (khusus radio)
    }

    fun show(r: M3uResult) {
        if (current == IptvSources.BUNDLED_FEED) IptvTrust.registerTrustedFeed(r.channels.map { it.name to it.url }) // [IPTV-TRUST-EXEMPT] hanya feed Molina
        val shown = r.channels.filter { !IptvTrust.isBlocked(it.url) } // [IPTV-TRUST-BLOCK]
        channels = markBad(shown)
        selected = 1
        query = ""
        radioGroup = null // [RADIO-TAB] reset
        errOnly = false // [ERR-FILTER]
        confirmPrune = false // [ERR-PRUNE]
        status = buildString {
            append("${shown.size} saluran")
            if (r.skipped > 0) append(" \u00B7 ${r.skipped} ditolak kebijakan URL")
            if (shown.size < r.channels.size) append(" \u00B7 ${r.channels.size - shown.size} tidak aman disembunyikan") // [IPTV-TRUST-BLOCK]
            if (r.truncated) append(" \u00B7 dipotong di ${M3uParser.MAX_CHANNELS}")
            if (pruneNote.isNotEmpty()) { append(" \u00B7 ").append(pruneNote); pruneNote = "" } // [ERR-PRUNE]
        }
    }

    androidx.compose.runtime.DisposableEffect(ctx) { // [UNPLAYABLE-CHIP] segarkan chip saat kembali dari pemutar
        val owner = ctx as? androidx.lifecycle.LifecycleOwner
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_RESUME && channels.isNotEmpty()) {
                val keep = channels.getOrNull(selected - 1)?.url // [RADIO-DEAD] resume: sorotan mengikuti stasiun, bukan nomor
                channels = markBad(channels)
                val idx = if (keep != null) channels.indexOfFirst { it.url == keep } else -1
                if (idx >= 0) selected = idx + 1
            }
        }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }

    fun load(url: String, force: Boolean) {
        if (loading) return
        current = url
        IptvSources.setLast(ctx, url)
        if (force) com.molina.cinema.UnplayableStore.clear(ctx) // [UNPLAYABLE-CHIP] Muat ulang = coba semua lagi
        val hit = if (force) null else IptvSources.cached(url)
        if (hit != null) { show(hit); return }
        loading = true
        channels = emptyList()
        status = "Memuat daftar saluran\u2026"
        scope.launch {
            when (val r = withContext(Dispatchers.IO) {
                if (IptvSources.isBundled(url)) bundledLoad(ctx, url) // [IPTV-BUNDLED] [RADIO-BUNDLED] call
                else if (IptvLocal.isLocalRef(url)) IptvLocal.load(iptvDir, File(url)) else IptvFetch.load(url)
            }) {
                is IptvLoad.Ok -> { IptvSources.cachePut(url, r.result); show(r.result) }
                is IptvLoad.Fail -> status = r.message
            }
            loading = false
        }
    }

    fun saveAndLoad(raw: String = urlInput) {
        val u = raw.trim()
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

    fun importNationalHttp() { // [IPTV-NAT-HTTP] salin aset ke folder IPTV, lalu jadikan sumber (EKSTERNAL)
        scope.launch {
            val f = withContext(Dispatchers.IO) { copyNationalHttp(ctx, iptvDir) }
            if (f == null) status = "Gagal menyalin daftar ke folder IPTV (periksa izin penyimpanan)." else pickFile(f)
        }
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
    LaunchedEffect(confirmPrune) { if (confirmPrune) { delay(3_000L); confirmPrune = false } } // [ERR-PRUNE]
    LaunchedEffect(refreshTick) { if (picking) refreshFiles() } // Penjelajah ditutup: daftar berkas disegarkan

    val entries = remember(channels) { channels.mapIndexed { i, c -> ChEntry(i + 1, c) } }
    val errLine = remember(channels) { com.molina.core.net.ChannelErrSummary.summarize(channels) } // [ERR-SUMMARY]
    val q = query.trim()
    val numeric = q.isNotEmpty() && q.all { it.isDigit() }
    val onlyErr = errOnly && errLine.isNotEmpty() // [ERR-FILTER] otomatis mati bila tak ada ERR lagi (mis. setelah Muat ulang)
    val grupCnt = remember(entries) { IntArray(3).also { a -> entries.forEach { a[grupOf(it.ch)]++ } } } // [GRUP-TAB] Nasional, Daerah, Lainnya
    val hasGrup = grupCnt[0] + grupCnt[1] > 0 // [GRUP-TAB] hanya feed Molina yang punya grup Nasional/Daerah
    val isRadio = current == IptvSources.RADIO_JATIM_FEED // [RADIO-TAB] derive
    val radioGroups = remember(entries, isRadio) { // kota terbanyak dulu, "Lainnya" paling akhir
        if (!isRadio) emptyList() else entries.groupingBy { it.ch.group.ifBlank { "Lainnya" } }.eachCount().entries
            .sortedWith(compareBy({ it.key == "Lainnya" }, { -it.value }, { it.key })).map { it.key to it.value }
    }
    val grupMode = if (hasGrup && q.isEmpty() && !onlyErr) (if (grupTab == 3 || grupCnt[grupTab] > 0) grupTab else 3) else 3 // [GRUP-TAB] pencarian tetap menjangkau semua
    val base = if (onlyErr) entries.filter { it.ch.broken }
        else if (isRadio && radioGroup != null && q.isEmpty()) entries.filter { it.ch.group.ifBlank { "Lainnya" } == radioGroup } // [RADIO-TAB] base
        else if (grupMode == 3) entries
        else entries.filter { grupOf(it.ch) == grupMode }
    val visible = if (q.isEmpty() || numeric) base
        else base.filter { it.ch.name.contains(q, ignoreCase = true) || it.ch.group.contains(q, ignoreCase = true) }
    val isLocal = current?.let { IptvLocal.isLocalRef(it) } == true // [ERR-PRUNE] hanya berkas lokal yang bisa diubah
    val canPrune = onlyErr && isLocal && visible.isNotEmpty() && !loading // [ERR-PRUNE]
    LaunchedEffect(q) { confirmPrune = false } // [ERR-PRUNE]
    LaunchedEffect(grupTab) { // [GRUP-TAB] ganti tab: sorot dan gulir ke item pertama
        if (visible.isNotEmpty()) { selected = visible.first().number; listState.scrollToItem(0) }
    }
    LaunchedEffect(radioGroup) { // [RADIO-TAB] scroll: ganti kota, sorot dan gulir ke item pertama
        if (visible.isNotEmpty()) { selected = visible.first().number; listState.scrollToItem(0) }
    }
    LaunchedEffect(onlyErr) { // [ERR-FILTER] ganti tampilan: sorot dan gulir ke item pertama
        confirmPrune = false // [ERR-PRUNE]
        if (visible.isNotEmpty()) { selected = visible.first().number; listState.scrollToItem(0) }
    }

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

    fun pick(e: ChEntry) { // [DRM-FLAG]
        if (e.ch.drm) { status = "${e.ch.name} memakai DRM dan tidak bisa diputar di Molina."; return }
        if (IptvTrust.isBlocked(e.ch.url)) { status = "${e.ch.name} memakai stream tidak aman dan diblokir (Keamanan jaringan)."; return } // [IPTV-TRUST-BLOCK]
        if (IptvTrust.needsConfirm(e.ch.url)) { pendingInsecure = e; return } // [IPTV-TRUST-EXEMPT]
        // [PLAYLIST-FULL] playlist pemutar = seluruh daftar M3U (urutan asli), bukan hasil filter pencarian;
        // saluran terpilih tetap jadi item pertama (lihat IptvPlayback.memoryPlaylist), prev/next menelusuri seluruh daftar
        if (e.ch.broken) status = "${e.ch.name} pernah gagal, mencoba lagi..." // [ERR-LOG]
        onPlay(channels, e.ch)
    }

    fun pruneErr() { // [ERR-PRUNE] yang dihapus = persis daftar ERR yang sedang tampil
        val u = current ?: return
        if (loading || !IptvLocal.isLocalRef(u)) return
        val urls = visible.filter { it.ch.broken }.map { it.ch.url }.toSet()
        if (urls.isEmpty()) return
        loading = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { IptvLocal.prune(iptvDir, File(u), urls) }
            loading = false
            when (r) {
                is IptvLocal.Prune.Done -> {
                    pruneNote = if (r.removed > 0) "${r.removed} entri ERR dihapus (cadangan .bak)" else "tidak ada entri yang cocok di berkas"
                    IptvSources.invalidate(u)
                    load(u, false)
                }
                is IptvLocal.Prune.Fail -> status = r.message
            }
        }
    }

    fun pickFromQuery() {
        val t = if (numeric) visible.firstOrNull { it.number == q.toIntOrNull() } else visible.singleOrNull()
        if (t != null) pick(t)
    }

    pendingInsecure?.let { p -> // [IPTV-TRUST-EXEMPT] fokus awal = Batal (aman untuk remote D-pad)
        val cancelFocus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { pendingInsecure = null },
            title = { Text("Putar stream tidak aman?") },
            text = { Text("${p.ch.name} memakai HTTP tanpa enkripsi. Isi siaran dan alamat Anda dapat terlihat oleh jaringan. Konfirmasi berlaku sampai aplikasi ditutup.") },
            confirmButton = {
                TextButton(onClick = { IptvTrust.confirm(p.ch.url); pendingInsecure = null; pick(p) }) { Text("Putar tetap", color = MolinaTheme.Warn) }
            },
            dismissButton = {
                LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { cancelFocus.requestFocus() } }
                TextButton(onClick = { pendingInsecure = null }, modifier = Modifier.focusRequester(cancelFocus)) { Text("Batal") }
            },
        )
    }

    if (confirmImport) { // [IPTV-NAT-HTTP] fokus awal = Batal (aman untuk remote D-pad)
        val cancelFocus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("Impor channel nasional (HTTP)?") },
            text = {
                Text(
                    "ANTV, SCTV, Indosiar, dan TVRI Sport hanya tersedia lewat HTTP dan alamat IP mentah, tanpa enkripsi. " +
                        "Isi siaran dan alamat Anda dapat terlihat atau diubah oleh jaringan, dan alamatnya tidak diverifikasi Molina. " +
                        "Daftar disalin ke folder IPTV sebagai sumber EKSTERNAL, bukan bagian dari Molina Feed. " +
                        "Jika \"Blokir stream tidak aman di IPTV\" menyala di Pengaturan, channel ini tidak akan diputar.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmImport = false; importNationalHttp() }) { Text("Impor", color = MolinaTheme.Warn) }
            },
            dismissButton = {
                LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { cancelFocus.requestFocus() } }
                TextButton(onClick = { confirmImport = false }, modifier = Modifier.focusRequester(cancelFocus)) { Text("Batal") }
            },
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val frame = RoundedCornerShape(20.dp)
        Surface(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 24.dp)
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
                        val bundled = IptvSources.isBundled(s.url) // [IPTV-BADGE] lencana dari URL, bukan nama: tidak bisa dipalsukan lewat nama sumber
                        SourceChip(
                            s.name, s.url == current && !adding && !picking,
                            badge = if (bundled) "BAWAAN" else "EKSTERNAL", badgeBrand = bundled,
                        ) { adding = false; picking = false; load(s.url, false) }
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
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) { // [IPTV-BUNDLED] tombol feed dihapus: feed Molina bawaan APK
                        Text("Simpan & muat", color = MolinaTheme.Brand, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { saveAndLoad() }.padding(8.dp))
                    }
                    ImportHttpAction { confirmImport = true } // [IPTV-NAT-HTTP]
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
                    ImportHttpAction { confirmImport = true } // [IPTV-NAT-HTTP]
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (onOpenFolder != null) {
                            Row(Modifier.clickable { onOpenFolder() }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(20.dp)) { TabFolderIcon(MolinaTheme.Explorer.Folder) }
                                Spacer(Modifier.width(6.dp))
                                Text("Folder IPTV", color = MolinaTheme.Brand, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        } else Spacer(Modifier.width(1.dp))
                        Text("Segarkan", color = MolinaTheme.Brand, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { refreshFiles() }.padding(8.dp))
                    }
                }

                if (loading) LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    color = MolinaTheme.Brand,
                    trackColor = Color(0x1FFFFFFF),
                )
                if (status.isNotEmpty()) Text(status, color = MolinaTheme.Muted, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp))
                if (errLine.isNotEmpty()) Row( // [ERR-SUMMARY] [ERR-FILTER]
                    Modifier.fillMaxWidth().clickable { errOnly = !errOnly }.padding(horizontal = 24.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tint = if (onlyErr) MolinaTheme.Brand else MolinaTheme.Muted
                    Box(Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape).background(MolinaTheme.Bad)) // [ERR-SUMMARY-DOT] selaras dengan chip ERR di kartu
                    Text(errLine, color = MolinaTheme.Bad, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(if (onlyErr) "SEMUA" else "SARING", color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp))
                }

                if (hasGrup && !onlyErr && q.isEmpty()) Row( // [GRUP-TAB] chip tab Nasional | Daerah | Lainnya | Semua
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (grupCnt[0] > 0) SourceChip("Nasional \u00B7 ${grupCnt[0]}", grupMode == 0) { grupTab = 0 }
                    if (grupCnt[1] > 0) SourceChip("Daerah \u00B7 ${grupCnt[1]}", grupMode == 1) { grupTab = 1 }
                    if (grupCnt[2] > 0) SourceChip("Lainnya \u00B7 ${grupCnt[2]}", grupMode == 2) { grupTab = 2 }
                    SourceChip("Semua \u00B7 ${entries.size}", grupMode == 3) { grupTab = 3 }
                }

                if (isRadio && radioGroups.isNotEmpty() && !onlyErr && q.isEmpty()) Row( // [RADIO-TAB] chips: Semua | kota...
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SourceChip("Semua \u00B7 ${entries.size}", radioGroup == null) { radioGroup = null }
                    radioGroups.forEach { (g, n) -> SourceChip("$g \u00B7 $n", radioGroup == g) { radioGroup = g } }
                }

                if (onlyErr) Row( // [ERR-HEADING] [ERR-PRUNE]
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape).background(MolinaTheme.Bad)) // [ERR-SUMMARY-DOT]
                    Text("Channel bermasalah", color = MolinaTheme.Bad, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (canPrune) Text(
                        if (confirmPrune) "Ketuk lagi: hapus ${visible.size}" else "Hapus dari berkas",
                        color = if (confirmPrune) MolinaTheme.Bad else MolinaTheme.Muted, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                            .clickable { if (confirmPrune) { confirmPrune = false; pruneErr() } else confirmPrune = true }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) else if (!isLocal) Text("Sumber URL: hanya saring", color = MolinaTheme.Muted, fontSize = 11.sp)
                }

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
                    "Belum ada saluran. Pilih Molina Feed di atas, atau ketuk + Tambah untuk menempel URL playlist (https), atau Berkas untuk .m3u di folder IPTV Molina.",
                    color = MolinaTheme.Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f, fill = false),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    items(visible, key = { it.number }) { e ->
                        ChRow(e, e.number == selected, radio = isRadio, // [RADIO-DIAL] call
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
                        if (searchOpen) MolinaTheme.Brand else Color.White, Modifier.weight(1f)) {
                        searchOpen = !searchOpen
                        if (!searchOpen) query = ""
                    }
                    ChKey(null, "\u21BB", "Muat", Color.White, Modifier.weight(1f)) { current?.let { load(it, true) } }
                    ChKey(null, "\u2715", "Hapus", Color.White, Modifier.weight(1f)) { if (IptvSources.isBundled(current)) status = "Feed Molina bawaan tidak bisa dihapus." else removeCurrent() }
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
    focusedBorderColor = MolinaTheme.Brand,
    unfocusedBorderColor = MolinaTheme.CardLine,
    cursorColor = MolinaTheme.Brand,
    focusedPlaceholderColor = MolinaTheme.Muted,
    unfocusedPlaceholderColor = MolinaTheme.Muted,
)

/** [GRUP-TAB] 0 = Nasional, 1 = Daerah, 2 = grup lain (termasuk tanpa grup). */
private fun grupOf(c: IptvChannel): Int = when {
    c.group.equals("Nasional", ignoreCase = true) -> 0
    c.group.equals("Daerah", ignoreCase = true) -> 1
    else -> 2
}

/** [IPTV-BADGE] [badge] opsional: "BAWAAN" (hijau merek) untuk feed Molina di APK, "EKSTERNAL" (abu) untuk sumber buatan pengguna. */
@Composable
private fun SourceChip(label: String, active: Boolean, badge: String? = null, badgeBrand: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.widthIn(max = if (badge != null) 220.dp else 170.dp).clip(shape)
            .border(1.dp, if (active) MolinaTheme.Brand else MolinaTheme.CardLine, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (active) MolinaTheme.Brand else MolinaTheme.Muted,
            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (badge != null) {
            val tint = if (badgeBrand) MolinaTheme.Brand else MolinaTheme.Muted
            val badgeShape = RoundedCornerShape(6.dp)
            Text(
                badge,
                color = tint, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.padding(start = 6.dp).clip(badgeShape)
                    .background(tint.copy(alpha = 0.16f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun ChRow(e: ChEntry, isSelected: Boolean, radio: Boolean = false, onPress: () -> Unit, onClick: () -> Unit) { // [RADIO-DIAL] sig
    val shape = RoundedCornerShape(4.dp)
    val inter = remember { MutableInteractionSource() } // [DPAD-FOCUS]
    val focused by inter.collectIsFocusedAsState() // [DPAD-FOCUS]
    val tv = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION // [TV-SCALE]
    LaunchedEffect(focused) { if (focused) onPress() } // [DPAD-FOCUS] fokus remote ikut menyorot nomor terpilih
    val hot = isSelected || focused
    val brush = if (hot) Brush.verticalGradient(listOf(SelTop, SelBottom))
        else Brush.verticalGradient(listOf(RowTop, RowBottom))
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .clip(shape).background(brush)
            .border(if (focused) 3.dp else if (isSelected) 2.dp else 1.dp, if (hot) MolinaTheme.Brand else RowLine, shape) // [DPAD-FOCUS] fokus lebih tebal dari sorot
            .pointerInput(e.number) {
                detectTapGestures(onPress = { onPress(); tryAwaitRelease() }, onTap = { onClick() })
            }
            .onKeyEvent { k -> // [DPAD-FOCUS] OK/Enter di remote = putar
                if (k.type == KeyEventType.KeyUp && (k.key == Key.DirectionCenter || k.key == Key.Enter || k.key == Key.NumPadEnter)) { onClick(); true } else false
            }
            .focusable(interactionSource = inter)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (radio) RadioDial(e.ch.name, e.number, tv) // [RADIO-DIAL] nomor: frekuensi bergaya dial, bukan nomor urut
        else Text("%03d".format(e.number), color = MolinaTheme.Brand,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = if (tv) 18.sp else 14.sp,
            modifier = Modifier.width(if (tv) 56.dp else 44.dp))
        Column(Modifier.weight(1f)) {
            Text(e.ch.name, color = if (radio && e.ch.broken) Color(0x99FFFFFF) else Color.White, fontSize = if (tv) 20.sp else 15.sp, maxLines = 2, // [RADIO-DIAL] redup: stasiun ERR dimmed
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
            if (e.ch.group.isNotEmpty()) Text(e.ch.group, fontSize = if (tv) 15.sp else 12.sp,
                color = MolinaTheme.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (e.ch.unverified && !e.ch.drm) { // [UNVERIFIED-BADGE] ERR pada channel ini belum tentu salah aplikasi
            Box(Modifier.padding(end = 4.dp).size(if (tv) 12.dp else 8.dp).clip(CircleShape).background(MolinaTheme.Warn)) // [TV-SCALE] titik terbaca dari jauh
            Text("BELUM DIUJI", color = MolinaTheme.Warn.copy(alpha = 0.8f), fontSize = if (tv) 13.sp else 9.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp))
        }
        when (e.ch.trust) { // [IPTV-TRUST] dihitung di perangkat dari URL, bukan dari label feed
            IptvTrust.Level.INSECURE -> Text(if (IptvTrust.needsConfirm(e.ch.url)) "TIDAK AMAN \u00B7 KONFIRMASI" else "TIDAK AMAN", color = MolinaTheme.Warn, fontSize = if (tv) 13.sp else 9.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp))
            IptvTrust.Level.COMMUNITY -> Text("KOMUNITAS", color = MolinaTheme.Muted.copy(alpha = 0.6f),
                fontSize = if (tv) 13.sp else 9.sp, modifier = Modifier.padding(end = 6.dp))
            IptvTrust.Level.OFFICIAL -> Unit
        }
        if (e.ch.drm) Text("DRM", color = MolinaTheme.Warn, fontSize = if (tv) 13.sp else 10.sp, fontWeight = FontWeight.Bold) // [DRM-FLAG]
        else if (e.ch.broken) { // [CHIP-SPLIT] [ERR-DOT] merah = gagal diputar, kuning = belum diuji
            Box(Modifier.padding(end = 4.dp).size(if (tv) 12.dp else 8.dp).clip(CircleShape).background(MolinaTheme.Bad))
            Text("ERR", color = MolinaTheme.Bad, fontSize = if (tv) 13.sp else 10.sp, fontWeight = FontWeight.Bold)
        }
        else if (e.ch.isDash) Text("DASH", color = MolinaTheme.Muted.copy(alpha = 0.6f), fontSize = if (tv) 13.sp else 10.sp) // [DASH-CHIP] petunjuk saja
    }
}

/** [RADIO-DIAL] composable: frekuensi dibaca dari nama stasiun (FM 76-108 MHz, AM kHz). Tanpa frekuensi = radio internet ("NET"). */
private class Dial(val value: String, val unit: String)

private val FM_DEC = Regex("""(?<![\d.,])(\d{2,3})[.,](\d{1,2})(?!\d)""")
private val FM_INT = Regex("""(?<![\d.,])(\d{2,3})\s*(?:FM|MHz)\b""", RegexOption.IGNORE_CASE)
private val AM_KHZ = Regex("""(?<![\d.,])(\d{3,4})\s*kHz\b""", RegexOption.IGNORE_CASE)

private fun dialOf(name: String): Dial? {
    AM_KHZ.find(name)?.let { return Dial(it.groupValues[1], "AM") }
    FM_DEC.find(name)?.let { m ->
        val d = (m.groupValues[1] + "." + m.groupValues[2]).toDoubleOrNull()
        if (d != null && d in 76.0..108.0) return Dial("%.1f".format(Locale.US, d), "FM")
    }
    FM_INT.find(name)?.let { m ->
        val n = m.groupValues[1].toIntOrNull()
        if (n != null && n in 76..108) return Dial(n.toString(), "FM")
    }
    return null
}

@Composable
private fun RadioDial(name: String, number: Int, tv: Boolean) {
    val dial = remember(name) { dialOf(name) }
    Column(Modifier.width(if (tv) 76.dp else 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(dial?.value ?: "%03d".format(number), color = if (dial != null) MolinaTheme.Brand else MolinaTheme.Muted,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = if (tv) 20.sp else 16.sp, maxLines = 1)
        Text(dial?.unit ?: "NET", color = MolinaTheme.Muted, fontSize = if (tv) 12.sp else 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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
        if (onCode != null) Text("Code", color = MolinaTheme.Brand, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onCode).padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

private fun sizeLabel(b: Long): String =
    if (b < 1024) "$b B" else if (b < 1024 * 1024) "${b / 1024} KB" else "%.1f MB".format(b / 1048576.0)
