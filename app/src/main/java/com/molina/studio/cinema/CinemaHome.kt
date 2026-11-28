package com.molina.studio.cinema

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.cinema.BackgroundPlaybackState
import com.molina.cinema.MpvFolder
import com.molina.core.system.MolinaDir
import com.molina.core.stream.PlaylistPlan
import com.molina.core.system.Refreshers
import com.molina.core.stream.StreamError
import com.molina.core.stream.StreamOutcome
import com.molina.core.stream.StreamResolvers
import com.molina.core.stream.StreamSearchItem
import com.molina.studio.AppPaths
import com.molina.studio.bridge.MolinaExplorer
import com.molina.studio.stream.YtdlpFastDir
import com.molina.studio.ui.MolinaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.molina.explorer.Win7OpenDialog
import com.molina.explorer.ExplorerTheme

private const val HINT = "Tempel tautan atau ketik judul di kolom atas, lalu tekan Putar. Ketuk Saluran untuk daftar TV (M3U)."
private const val FAIL = "Pemutar gagal dibuka."
private const val PREPARING = "Menyiapkan mesin Molina Cinema (sekali saja)\u2026" // [YTDL-WARM]
private const val NO_MEDIA = "Folder ini tidak berisi berkas media."

private fun searchError(e: StreamError): String = when (e) {
    StreamError.NETWORK -> "Pencarian gagal: periksa koneksi internet."
    StreamError.PARSE -> "Pencarian gagal: format YouTube berubah, extractor perlu diperbarui."
    else -> "Pencarian gagal."
}

internal fun duration(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
fun CinemaHome(
    modifier: Modifier = Modifier,
    // [EXPLORER-ONE] Penjelajah Folder IPTV disuntikkan pemanggil (MainActivity) agar paket cinema tidak bergantung ke settings.
    iptvExplorer: @Composable (startDir: java.io.File, onDismiss: () -> Unit, onLeave: () -> Unit) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by rememberSaveable { mutableStateOf("") }
    var channelsOpen by rememberSaveable { mutableStateOf(false) }
    var input by CinemaSearchState.input // [SEARCH-STATE]
    var searching by remember { mutableStateOf(false) }
    var results by CinemaSearchState.results
    var lastQuery by CinemaSearchState.lastQuery
    var showResults by CinemaSearchState.showResults
    var minimized by CinemaSearchState.minimized // [RESULTS-MINIMIZE]
    var openLocal by rememberSaveable { mutableStateOf(false) }
    var openChooser by rememberSaveable { mutableStateOf(false) }
    var openFolder by rememberSaveable { mutableStateOf(false) }
    var iptvFolderOpen by rememberSaveable { mutableStateOf(false) }
    var iptvTick by remember { mutableStateOf(0) }
    val iptvDir = remember { AppPaths.get(ctx).dir(MolinaDir.Iptv) } // /sdcard/Molina/Media/IPTV
    var folderDeep by rememberSaveable { mutableStateOf(false) }
    var tvOpen by rememberSaveable { mutableStateOf(false) }
    val tvOn = remember(TvMode.version) { TvMode.enabled(ctx) }
    var confirmReset by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) } // [ORB-SPIN]

    fun search(q: String) {
        if (searching) return
        searching = true
        info = "Mencari\u2026"
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { StreamResolvers.get()?.search(q, PlaylistPlan.MAX_ITEMS) }
            searching = false
            when (outcome) {
                null -> info = "Mesin pencarian belum siap."
                is StreamOutcome.Failure -> info = searchError(outcome.error)
                is StreamOutcome.Success -> if (outcome.value.isEmpty()) {
                    info = "Tidak ada hasil untuk \"$q\"."
                } else {
                    info = ""
                    CinemaSearchState.resetView() // [RESULTS-MINIMIZE] daftar baru: posisi/pilihan/saringan lama dibuang
                    results = outcome.value
                    lastQuery = q
                    showResults = true
                    minimized = false
                }
            }
        }
    }

    fun submit(raw: String) {
        when (val r = CinemaActions.classify(raw)) {
            is CinemaActions.Input.Direct -> info = if (CinemaActions.play(ctx, r.uri)) "" else FAIL
            CinemaActions.Input.Query -> search(raw.trim())
            CinemaActions.Input.Invalid -> info = "Tautan tidak didukung. Pakai http, https, rtsp, atau rtmp."
            CinemaActions.Input.Empty -> Unit
        }
    }

    val live by BackgroundPlaybackState.active.collectAsState()
    val enginePhase by YtdlpFastDir.phase.collectAsState() // [YTDL-WARM]
    val preparing = enginePhase == YtdlpFastDir.Phase.Preparing // [YTDL-WARM] bingkai + bar bekerja, bukan hanya teks
    CinemaWin7Body(
        modifier = modifier,
        info = info.ifEmpty { if (preparing) PREPARING else HINT }, // [YTDL-WARM]
        busy = searching || preparing, // [YTDL-WARM] RgbFrame (bila RGB nyala) + GlossBar StatusStrip
        refreshing = refreshing, // [ORB-SPIN]
        live = live,
        input = input,
        onInput = { input = it },
        onSubmit = { submit(input) },
        onFile = { openChooser = true },
        onChannels = { channelsOpen = true },
        onSettings = { if (!CinemaActions.openSettings(ctx)) info = "Setelan gagal dibuka." },
        onReset = { confirmReset = true },
        onRecent = { r ->
            if (CinemaActions.playSnapshot(ctx, r.ref)) Unit // [RECENT-PLAYLIST]
            else if (r.ref.startsWith("/")) {
                if (!CinemaActions.playPath(ctx, r.ref)) info = FAIL
            } else submit(r.ref)
        },
        onTv = if (tvOn) ({ tvOpen = true }) else null
    )
    if (confirmReset) MolinaBlackDialog( // [BLACK-DIALOG]
        title = "Segarkan Sistem",
        message = "Menutup semua proses di latar belakang (pemutar, unduhan, skrip Code, proses Paket, sesi Terminal, serta semua proses anak aplikasi) untuk membebaskan memori dan mencegah proses tersisa di latar. Aplikasi tetap terbuka.",
        confirmLabel = "Segarkan",
        dismissLabel = "Batal",
        onDismiss = { confirmReset = false },
        onConfirm = {
            confirmReset = false
            input = ""
            results = emptyList()
            showResults = false
            minimized = false // [RESULTS-MINIMIZE]
            info = "Menyegarkan\u2026"
            scope.launch {
                refreshing = true // [ORB-SPIN]
                try {
                val t0 = System.currentTimeMillis()
                val reports = withContext(Dispatchers.IO) { Refreshers.refreshAll() }
                val rest = 700L - (System.currentTimeMillis() - t0) // putaran terlihat minimal ~0,7 dtk
                if (rest > 0) delay(rest)
                val total = reports.sumOf { it.total }
                val left = reports.sumOf { it.remaining }
                val head = if (total == 0 && left == 0) "Sudah bersih, tidak ada proses aktif."
                else "Bersih: $total proses dihentikan."
                val tail = if (left > 0) " $left proses tidak bisa ditutup." else ""
                val notes = reports.mapNotNull { it.note }.filter { it.isNotBlank() }
                info = head + tail + if (notes.isEmpty()) "" else " " + notes.joinToString(" ")
                } finally { refreshing = false }
            }
        }
    )
    if (tvOn && tvOpen) TvBrowserDialog(
        onPlay = { f -> if (!CinemaActions.playPath(ctx, f.absolutePath)) info = FAIL },
        onDismiss = { tvOpen = false },
        recents = remember(CinemaRecent.version) { CinemaRecent.load(ctx) },
        onPlayRecent = { r ->
            if (CinemaActions.playSnapshot(ctx, r.ref)) Unit // [RECENT-PLAYLIST]
            else if (r.ref.startsWith("/")) {
                if (!CinemaActions.playPath(ctx, r.ref)) info = FAIL
            } else submit(r.ref)
        },
        onClearRecent = { CinemaRecent.clear(ctx) }
    )
    if (channelsOpen) CinemaChannelsDialog(
        onDismiss = { channelsOpen = false },
        onOpenFolder = { runCatching { iptvDir.mkdirs() }; iptvFolderOpen = true },
        refreshTick = iptvTick,
        onOpenInCode = { f -> channelsOpen = false; MolinaExplorer.openInCode(ctx, listOf(f)) },
        onPlay = { list, picked ->
            channelsOpen = false
            info = if (CinemaActions.playChannels(ctx, list, picked)) "" else FAIL
        }
    )
    if (openLocal) Win7OpenDialog(
        onDismiss = { openLocal = false },
        onPick = { f ->
            openLocal = false
            if (!CinemaActions.playPath(ctx, f.absolutePath)) info = FAIL
        },
        env = rememberAppExplorerEnv(ExplorerTheme.Cinema)
    )
    if (openChooser) CinemaOpenChooser(
        onFile = { openChooser = false; openLocal = true },
        onFolder = { openChooser = false; folderDeep = false; openFolder = true },
        onFolderDeep = { openChooser = false; folderDeep = true; openFolder = true },
        onDismiss = { openChooser = false }
    )
    if (openFolder) Win7OpenDialog(
        onDismiss = { openFolder = false },
        pickFolder = true,
        onPickFolder = { d ->
            openFolder = false
            val deep = folderDeep
            scope.launch {
                if (deep) {
                    info = "Memindai subfolder\u2026"
                    val uri = withContext(Dispatchers.IO) { MpvFolder.memoryPlaylist(MpvFolder.mediaTree(d)) }
                    info = when {
                        uri == null -> NO_MEDIA
                        CinemaActions.playTree(ctx, uri) -> ""
                        else -> FAIL
                    }
                } else {
                    val first = withContext(Dispatchers.IO) { MpvFolder.firstMediaIn(d) }
                    info = when {
                        first == null -> NO_MEDIA
                        CinemaActions.playFolder(ctx, first) -> ""
                        else -> FAIL
                    }
                }
            }
        },
        env = rememberAppExplorerEnv(ExplorerTheme.Cinema)
    )
    if (iptvFolderOpen) iptvExplorer( // [EXPLORER-ONE] sumber tunggal Penjelajah, sama dengan tab Berkas
        iptvDir,
        { iptvFolderOpen = false; iptvTick++ },
        { iptvFolderOpen = false; channelsOpen = false }
    )
    if (showResults) SearchResultsDialog(
        results = results,
        onDismiss = { showResults = false; minimized = false },
        onMinimize = { showResults = false; minimized = true }, // [RESULTS-MINIMIZE]
        searchQuery = lastQuery,
        onSearch = { s -> input = s; showResults = false; search(s) },
        onFetchRelated = { q -> // [RESULTS-RELATED] pencarian kedua: video sejenis dari kanal lain
            when (val o = withContext(Dispatchers.IO) { StreamResolvers.get()?.search(q, 30) }) {
                is StreamOutcome.Success -> o.value
                else -> emptyList()
            }
        },
        onPick = { list, r ->
            showResults = false
            minimized = true // [RESULTS-MINIMIZE] setelah memilih, hasil tetap bisa dibuka lagi dari chip
            val d = CinemaActions.classify(r.pageUrl)
            info = if (d is CinemaActions.Input.Direct && CinemaActions.playResults(ctx, list, r)) "" else FAIL
        }
    )
    if (!showResults && minimized && results.isNotEmpty()) MinimizedResultsPill( // [RESULTS-MINIMIZE]
        count = results.size, query = lastQuery,
        onOpen = { showResults = true; minimized = false },
        onClear = { minimized = false },
    )
}
