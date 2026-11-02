package com.molina.studio

import com.molina.studio.ui.MolinaTheme
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import android.view.ViewTreeObserver
import com.molina.core.common.AppVisibility
import com.molina.studio.log.LogServer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private enum class Tab(val label: String, val glyph: String, val color: Color) {
    Mpv("mpv", "\u25B6", Color(0xFFB9A7E8)),          // lavender redup: bioskop
    Files("Berkas", "\u25A4", Color(0xFF7FB2E5)),       // biru lembut: penjelajah berkas (Terminal kini di Settings)
    Code("Code", "< >", Color(0xFFFFB300)),             // emas: editor
    Settings("Settings", "\u2699", Color(0xFFCDBFA3))  // champagne: pengaturan
}

/** True saat keyboard layar tampil. Dipakai agar nav bawah tidak makan ruang saat mengetik. */
@Composable
private fun rememberKeyboardVisible(): Boolean {
    val view = LocalView.current
    var visible by remember { mutableStateOf(false) }
    DisposableEffect(view) {
        val rect = Rect()
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            view.getWindowVisibleDisplayFrame(rect)
            val screenH = view.rootView.height
            visible = screenH > 0 && (screenH - rect.bottom) > screenH * 0.15f
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return visible
}

private val MolinaColors = darkColorScheme(
    background = MolinaTheme.Background,
    surface = MolinaTheme.Background,
    primary = MolinaTheme.Gold,
    onBackground = MolinaTheme.OnBackground,
    onSurface = MolinaTheme.OnBackground
)

class MainActivity : ComponentActivity() {
    /** Izin storage diminta sekali di awal agar AppPaths siap untuk semua fitur (mpv, editor), bukan hanya Terminal. */
    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            AppPaths.ensureIfPermitted(applicationContext)
        }

    override fun onStart() {
        super.onStart()
        AppVisibility.setForeground(true)
    }

    override fun onResume() {
        super.onResume()
        com.molina.core.common.CrashContext.host = com.molina.core.common.CrashContext.HOST_APP
        AppPaths.ensureIfPermitted(applicationContext)
    }

    /** FLAG_SECURE + filter sentuhan sesuai sakelar Pengaturan > Keamanan dan privasi. */
    private fun applyPrivacy() {
        val on = com.molina.studio.security.PrivacyPrefs.secureScreen.value
        if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.filterTouchesWhenObscured = on
    }

    override fun onDestroy() {
        com.molina.studio.security.PrivacyPrefs.onChange = null
        super.onDestroy()
    }

    /** Sesi editor Code; dipegang agar draf bisa ditulis sinkron saat Activity berhenti (F3e). */
    private var codeSessionRef: com.molina.code.ui.CodeSession? = null

    override fun onStop() {
        codeSessionRef?.flushDrafts()
        AppVisibility.setForeground(false)
        super.onStop()
    }

    /** Notifikasi Unduhan diketuk saat Activity sudah hidup (FLAG_ACTIVITY_SINGLE_TOP): tidak perlu launchMode di Manifest. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenTabIntent(intent)
    }

    /** Ketuk notifikasi Unduhan membuka tab Unduhan. Extra dibuang agar tidak terulang saat Activity dibuat ulang. */
    private fun handleOpenTabIntent(i: android.content.Intent?) {
        // [INTENT-TOKEN] MainActivity exported: extra dari aplikasi lain bisa memuat Parcelable yang tidak bisa dimuat
        // (BadParcelableException saat dibaca). Jangan crash; buang extra-nya.
        runCatching { dispatchOpenTabIntent(i) }.onFailure {
            runCatching { i?.replaceExtras(null as android.os.Bundle?) }
            com.molina.studio.log.LogBus.log("sistem", com.molina.core.common.LogLevel.Warn, "intent", "Intent dengan extra tidak sah diabaikan")
        }
    }

    /** [INTENT-TOKEN] Aksi dengan efek samping (putar terakhir, dialog jalankan skrip) hanya untuk intent bertoken milik Molina. */
    private fun trustedShortcut(i: android.content.Intent): Boolean {
        val ok = com.molina.studio.security.ShortcutToken.verify(this, i.getStringExtra(com.molina.studio.security.ShortcutToken.EXTRA))
        if (!ok) {
            com.molina.studio.log.LogBus.log("sistem", com.molina.core.common.LogLevel.Warn, "intent", "Aksi pintasan ditolak: token tidak cocok")
            android.widget.Toast.makeText(this, "Pintasan kedaluwarsa. Buka ulang Molina lalu coba lagi.", android.widget.Toast.LENGTH_LONG).show()
        }
        return ok
    }

    private fun dispatchOpenTabIntent(i: android.content.Intent?) {
        val tabKey = i?.getStringExtra("molina_open_tab")
        if (i != null && tabKey != null) {
            i.removeExtra("molina_open_tab")
            ShortcutNav.tab = when (tabKey) {
                "cinema" -> Tab.Mpv.ordinal
                "terminal" -> {
                    com.molina.studio.settings.SettingsNav.openTerminalApp = true
                    Tab.Settings.ordinal
                }
                "downloads" -> {
                    com.molina.studio.download.DownloadNav.openTab = true
                    -1
                }
                "storage" -> {
                    com.molina.studio.settings.SettingsNav.openStorage = true
                    Tab.Settings.ordinal
                }
                "play_last" -> if (!trustedShortcut(i)) -1 else {
                    if (!com.molina.studio.cinema.CinemaActions.playLast(this)) {
                        android.widget.Toast.makeText(this, "Belum ada riwayat putar", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    Tab.Mpv.ordinal
                }
                "files" -> Tab.Files.ordinal
                "code" -> Tab.Code.ordinal
                else -> -1
            }
        }
        val runId = i?.getStringExtra(ScriptShortcuts.EXTRA_RUN)
        if (i != null && runId != null) {
            i.removeExtra(ScriptShortcuts.EXTRA_RUN)
            if (trustedShortcut(i)) com.molina.studio.code.ScriptNav.confirmRunId = runId
        }
        val key = com.molina.studio.download.DownloadService.EXTRA_OPEN_TAB
        if (i?.getStringExtra(key) == com.molina.studio.download.DownloadService.OPEN_TAB_DOWNLOADS) {
            i.removeExtra(key)
            com.molina.studio.download.DownloadNav.openTab = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        LogServer.start()
        MolinaShortcuts.refresh(applicationContext)
        applyPrivacy()
        com.molina.studio.security.PrivacyPrefs.onChange = { runOnUiThread { applyPrivacy() } }
        com.molina.studio.terminal.TerminalPrefs.init(applicationContext)
        com.molina.studio.terminal.CrashReporter.install(applicationContext)
        com.molina.studio.terminal.CrashReporter.notifyPending(applicationContext)
        com.molina.studio.terminal.LogcatReporter.setEnabled(
            com.molina.studio.terminal.TerminalPrefs.logcatReporter
        )
        com.molina.studio.download.DownloadPrefs.restore(applicationContext)
        com.molina.studio.download.DownloadHistoryStore.init(applicationContext)
        if (savedInstanceState == null) handleOpenTabIntent(intent)
        AppExit.attach(this)
        setContent {
            MaterialTheme(colorScheme = MolinaColors) {
                var selected by rememberSaveable { mutableIntStateOf(0) }
                androidx.compose.runtime.LaunchedEffect(selected) { com.molina.core.common.CrashContext.tab = Tab.entries[selected].label }
                val codeSession = remember {
                    com.molina.code.ui.CodeSession(
                        AppPaths.get(this@MainActivity).workspace.path,
                        java.io.File(filesDir, "code-drafts")
                    ).also {
                        codeSessionRef = it
                        it.scriptExecutor = com.molina.studio.code.ProotScriptExecutor(applicationContext)
                        it.formatExecutor = com.molina.studio.code.ProotScriptExecutor(applicationContext, com.molina.studio.code.ProotScriptExecutor.Lane.FORMAT) // [CODE-FORMAT]
                        it.checkExecutor = com.molina.studio.code.ProotScriptExecutor(applicationContext, com.molina.studio.code.ProotScriptExecutor.Lane.CHECK) // [CODE-CHECK]
                        it.lspExecutor = com.molina.studio.code.ProotScriptExecutor(applicationContext, com.molina.studio.code.ProotScriptExecutor.Lane.LSP) // [CODE-LSP2]
                        it.lspPathMap = { // [CODE-LSP3] lingkup storage sama dengan bind PRoot Terminal; dibaca ulang tiap sesi LSP mulai
                            com.molina.code.lsp.LspPathMap(
                                android.os.Environment.getExternalStorageDirectory().absolutePath,
                                com.molina.studio.terminal.TerminalSessions.WORKSPACE_DIR,
                                com.molina.studio.terminal.TerminalPrefs.fullStorageAccess,
                                listOf(com.molina.core.common.MolinaPaths.APP_FOLDER),
                            )
                        }
                        it.terminalSink = { text, execute -> com.molina.studio.code.CodeTerminalBridge.deliver(text, execute) }
                        it.guestPathResolver = { f -> com.molina.studio.code.CodeTerminalBridge.guestPath(f) } // [CODE-PYRUN]
                        it.debianRoot = { com.molina.studio.terminal.RootfsManager.rootfsDir(applicationContext) } // [CODE-DEBLIB]
                        val runPrefs = this@MainActivity.getSharedPreferences("molina_code_run", android.content.Context.MODE_PRIVATE) // [CODE-PYRUN2]
                        it.runStore = object : com.molina.code.ui.RunStore { // [CODE-PYRUN2]
                            override fun get(key: String): String? = runPrefs.getString(key, null)
                            override fun put(key: String, value: String) { runPrefs.edit().putString(key, value).apply() }
                        }
                        it.snippetSink = { name, cmd -> com.molina.studio.terminal.TerminalSnippets.addFromCode(applicationContext, name, cmd) }
                        com.molina.studio.code.CodeInbox.session = it
                        it.scriptSink = com.molina.code.run.ScriptSink { share ->
                            val r = com.molina.studio.code.ScriptStore.upsertFromEditor(share)
                            com.molina.code.run.ScriptSendResult(r.ok, r.message)
                        }
                    }
                }
                DisposableEffect(codeSession) { onDispose { com.molina.studio.code.CodeInbox.session = null; codeSession.releaseAll() } }
                // Script execution (Settings) menjalankan skrip di Molina Terminal: pindah ke Settings > Terminal.
                androidx.compose.runtime.LaunchedEffect(com.molina.studio.code.ScriptNav.openTerminal) {
                    if (com.molina.studio.code.ScriptNav.openTerminal) {
                        com.molina.studio.settings.SettingsNav.openTerminalApp = true
                        selected = Tab.Settings.ordinal
                        com.molina.studio.code.ScriptNav.openTerminal = false
                    }
                }
                // EDIT di Script Execution membuka skrip di editor Code: pindah ke tab Code.
                androidx.compose.runtime.LaunchedEffect(com.molina.studio.code.CodeNav.pending) {
                    com.molina.studio.code.CodeNav.pending?.let { req ->
                        codeSession.openScript(req.title, req.key, req.text)
                        selected = Tab.Code.ordinal
                        com.molina.studio.code.CodeNav.pending = null
                    }
                }
                // Penjelajah Molina: Buka di Code membuka berkas di editor lalu pindah ke tab Code.
                androidx.compose.runtime.LaunchedEffect(com.molina.studio.code.CodeNav.pendingFiles) {
                    val files = com.molina.studio.code.CodeNav.pendingFiles
                    if (files.isNotEmpty()) {
                        files.forEach { codeSession.openFile(it) }
                        selected = Tab.Code.ordinal
                        com.molina.studio.code.CodeNav.pendingFiles = emptyList()
                    }
                }
                // Kartu hasil pencarian Cinema: Unduh mengirim URL ke tab Unduhan.
                androidx.compose.runtime.LaunchedEffect(com.molina.studio.download.DownloadNav.pendingUrls) {
                    if (com.molina.studio.download.DownloadNav.pendingUrls.isNotEmpty()) {
                        com.molina.studio.settings.SettingsNav.openDownloads = true
                        selected = Tab.Settings.ordinal
                    }
                }
                // Ketuk notifikasi Unduhan: pindah ke tab Unduhan.
                androidx.compose.runtime.LaunchedEffect(com.molina.studio.download.DownloadNav.openTab) {
                    if (com.molina.studio.download.DownloadNav.openTab) {
                        com.molina.studio.settings.SettingsNav.openDownloads = true
                        selected = Tab.Settings.ordinal
                        com.molina.studio.download.DownloadNav.openTab = false
                    }
                }
                // Jalan pintas ikon (tekan lama): pindah ke tab Cinema, Terminal, atau Code.
                androidx.compose.runtime.LaunchedEffect(ShortcutNav.tab) {
                    val t = ShortcutNav.tab
                    if (t in Tab.entries.indices) selected = t
                    ShortcutNav.tab = -1
                }
                // Pintasan skrip di layar utama: dialog konfirmasi sebelum skrip dijalankan.
                com.molina.studio.code.ScriptRunHost()
                // Lencana tab Unduhan: emas saat berjalan, hijau saat selesai dan belum dilihat.
                val dl by com.molina.studio.download.DownloadRunner.state.collectAsState()
                var dlWasRunning by remember { mutableStateOf(dl.running) }
                androidx.compose.runtime.LaunchedEffect(dl.running) {
                    if (dlWasRunning && !dl.running && !com.molina.studio.download.DownloadNav.visible) {
                        com.molina.studio.download.DownloadNav.unseen = true
                    }
                    dlWasRunning = dl.running
                }
                val htmlWebView by com.molina.studio.code.CodePrefs.htmlWebView.collectAsState()
                SideEffect { codeSession.htmlWebView = htmlWebView }
                val keyboardVisible = rememberKeyboardVisible()
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        if (!keyboardVisible && !(selected == Tab.Code.ordinal && codeSession.previewFullScreen)) {
                            Column {
                                Box(
                                    Modifier.fillMaxWidth().height(1.dp).background(
                                        Brush.horizontalGradient(
                                            // rail emas, selaras dengan rail toolbar Code
                                            listOf(Color.Transparent, Color(0xFFFFB300).copy(alpha = 0.40f), Color.Transparent)
                                        )
                                    )
                                )
                                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                    Tab.entries.forEachIndexed { i, tab ->
                                        NavigationBarItem(
                                            selected = selected == i,
                                            onClick = { selected = i },
                                            icon = {
                                                if (tab == Tab.Settings && (dl.running || com.molina.studio.download.DownloadNav.unseen)) {
                                                    androidx.compose.material3.BadgedBox(
                                                        badge = {
                                                            androidx.compose.material3.Badge(
                                                                containerColor = if (dl.running) com.molina.studio.ui.MolinaTheme.Gold
                                                                else com.molina.studio.ui.MolinaTheme.Good
                                                            )
                                                        }
                                                    ) { Text(tab.glyph, fontWeight = FontWeight.Bold) }
                                                } else if (tab == Tab.Files) {
                                                    com.molina.studio.settings.TabFolderIcon(androidx.compose.material3.LocalContentColor.current)
                                                } else {
                                                    Text(tab.glyph, fontWeight = FontWeight.Bold)
                                                }
                                            },
                                            label = { Text(tab.label) },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = tab.color,
                                                selectedTextColor = tab.color,
                                                indicatorColor = tab.color.copy(alpha = 0.18f),
                                                unselectedIconColor = tab.color.copy(alpha = 0.55f),
                                                unselectedTextColor = tab.color.copy(alpha = 0.55f)
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                ) { pad ->
                    when (Tab.entries[selected]) {
                        Tab.Files -> {
                            com.molina.studio.settings.FilesTabScreen(Modifier.fillMaxSize().padding(pad), onExit = { selected = Tab.Mpv.ordinal })
                            return@Scaffold
                        }
                        Tab.Mpv -> {
                            com.molina.studio.cinema.CinemaHome(Modifier.fillMaxSize().padding(pad))
                            return@Scaffold
                        }
                        Tab.Settings -> {
                            com.molina.studio.settings.SettingsScreen(Modifier.fillMaxSize().padding(pad))
                            return@Scaffold
                        }
                        Tab.Code -> {
                            com.molina.code.ui.CodeScreen(codeSession, Modifier.fillMaxSize().padding(pad))
                            return@Scaffold
                        }
                        else -> {}
                    }
                    Box(
                        Modifier.fillMaxSize().padding(pad),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Molina ${Tab.entries[selected].label}",
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }
    }
}
