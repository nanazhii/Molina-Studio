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
import com.molina.core.common.MolinaDir
import com.molina.core.common.PlaylistPlan
import com.molina.core.common.StreamError
import com.molina.core.common.StreamOutcome
import com.molina.core.common.StreamResolvers
import com.molina.core.common.StreamSearchItem
import com.molina.studio.AppPaths
import com.molina.studio.settings.MolinaExplorer
import com.molina.studio.ui.MolinaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val HINT = "Tempel tautan atau ketik judul di kolom atas, lalu tekan Putar. Ketuk Saluran untuk daftar TV (M3U)."
private const val FAIL = "Pemutar gagal dibuka."
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
fun CinemaHome(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by rememberSaveable { mutableStateOf("") }
    var channelsOpen by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<StreamSearchItem>>(emptyList()) }
    var lastQuery by remember { mutableStateOf("") }
    var showResults by remember { mutableStateOf(false) }
    var openLocal by rememberSaveable { mutableStateOf(false) }
    var openChooser by rememberSaveable { mutableStateOf(false) }
    var openFolder by rememberSaveable { mutableStateOf(false) }
    var iptvFolderOpen by rememberSaveable { mutableStateOf(false) }
    var iptvTick by remember { mutableStateOf(0) }
    val iptvDir = remember { AppPaths.get(ctx).dir(MolinaDir.Iptv) } // /sdcard/Molina/Media/IPTV
    var folderDeep by rememberSaveable { mutableStateOf(false) }
    var tvOpen by rememberSaveable { mutableStateOf(false) }
    val tvOn = remember(TvMode.version) { TvMode.enabled(ctx) }

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
                    results = outcome.value
                    lastQuery = q
                    showResults = true
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
    CinemaWin7Body(
        modifier = modifier,
        info = info.ifEmpty { HINT },
        busy = searching,
        live = live,
        input = input,
        onInput = { input = it },
        onSubmit = { submit(input) },
        onFile = { openChooser = true },
        onChannels = { channelsOpen = true },
        onSettings = { if (!CinemaActions.openSettings(ctx)) info = "Setelan gagal dibuka." },
        onReset = {
            input = ""
            results = emptyList()
            showResults = false
            info = "Menyegarkan\u2026"
            scope.launch {
                val r = withContext(Dispatchers.Default) { MemoryRefresh.run(!live) }
                info = MemoryRefresh.describe(r)
            }
        },
        onRecent = { r ->
            if (r.ref.startsWith("/")) {
                if (!CinemaActions.playPath(ctx, r.ref)) info = FAIL
            } else submit(r.ref)
        },
        onTv = if (tvOn) ({ tvOpen = true }) else null
    )
    if (tvOn && tvOpen) TvBrowserDialog(
        onPlay = { f -> if (!CinemaActions.playPath(ctx, f.absolutePath)) info = FAIL },
        onDismiss = { tvOpen = false },
        recents = remember(CinemaRecent.version) { CinemaRecent.load(ctx) },
        onPlayRecent = { r ->
            if (r.ref.startsWith("/")) {
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
        }
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
        }
    )
    if (iptvFolderOpen) {
        remember { MolinaExplorer.attach(ctx) }
        Win7OpenDialog(
            onDismiss = { iptvFolderOpen = false; iptvTick++ },
            browse = true,
            startDir = iptvDir,
            onCopyPath = { f -> MolinaExplorer.copyPath(ctx, f) },
            onShare = { files -> MolinaExplorer.share(ctx, files) },
            onInsertPaths = { fs -> MolinaExplorer.insertPaths(ctx, fs) },
            isVisibleInDebian = { f -> MolinaExplorer.visibleInDebian(f) },
            onPlay = { files -> MolinaExplorer.play(ctx, files) },
            onOpenInCode = { files -> iptvFolderOpen = false; channelsOpen = false; MolinaExplorer.openInCode(ctx, files) },
        )
    }
    if (showResults) SearchResultsDialog(
        results = results,
        onDismiss = { showResults = false },
        searchQuery = lastQuery,
        onSearch = { s -> input = s; showResults = false; search(s) },
        onPick = { list, r ->
            showResults = false
            val d = CinemaActions.classify(r.pageUrl)
            info = if (d is CinemaActions.Input.Direct && CinemaActions.playResults(ctx, list, r)) "" else FAIL
        }
    )
}
