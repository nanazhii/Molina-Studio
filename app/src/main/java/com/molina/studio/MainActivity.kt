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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
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
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color

private enum class Tab(@StringRes val labelRes: Int, val glyph: String, val color: Color) {
    Mpv(R.string.tab_mpv, "\u25B6", Color(0xFFB388FF)),          // [NAV-SLIM] ungu pekat: bioskop
    Files(R.string.tab_files, "\u25A4", Color(0xFF5C9DFF)),       // biru pekat: penjelajah berkas (Editor dibuka dari sini, layar penuh)
    Terminal(R.string.tab_terminal, ">_", Color(0xFFFFB74D)),      // amber: terminal (slot lama Editor)
    Settings(R.string.tab_settings, "\u2699", Color(0xFFB8C2CC))  // abu dingin terang: pengaturan
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
    primary = MolinaTheme.Brand,
    onBackground = MolinaTheme.OnBackground,
    onSurface = MolinaTheme.OnBackground
)

class MainActivity : ComponentActivity() {
    /** Izin storage diminta sekali di awal agar AppPaths siap untuk semua fitur (mpv, editor), bukan hanya Terminal. */
    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            AppPaths.ensureIfPermitted(applicationContext)
        }

    // MOLINA-H13-BRIDGE: Code > Simpan ke Vault. Hasil hanya dilaporkan lewat Toast; Code tidak pernah menghapus berkas.
    private var vaultCodeReq: com.molina.studio.settings.VaultBridge.Request? = null
    private val vaultCodeLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val req = vaultCodeReq
            vaultCodeReq = null
            if (req != null) MainScope().launch {
                val o = com.molina.studio.settings.VaultBridge.verify(req, res.resultCode, res.data, offerDelete = false)
                android.widget.Toast.makeText(applicationContext, o.message, android.widget.Toast.LENGTH_LONG).show()
            }
        }

    /** Dipanggil menu Code. Mengembalikan pesan galat, atau null bila Vault berhasil dibuka. */
    private fun sendToVault(f: java.io.File): String? =
        when (val p = com.molina.studio.settings.VaultBridge.prepare(this, listOf(f))) {
            is com.molina.studio.settings.VaultBridge.Prepared.Fail -> p.message
            is com.molina.studio.settings.VaultBridge.Prepared.Ready -> {
                vaultCodeReq = p.request
                try {
                    vaultCodeLauncher.launch(p.request.intent)
                    null
                } catch (e: Exception) {
                    vaultCodeReq = null
                    "Vault tidak bisa dibuka: " + (e.message ?: e.javaClass.simpleName)
                }
            }
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
                "terminal" -> Tab.Terminal.ordinal
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
                "packages" -> { // [SUBTITLE-EXTRACT] hanya navigasi (tanpa efek samping), jadi tidak perlu token
                    com.molina.studio.settings.SettingsNav.openPackages = true
                    com.molina.studio.settings.SettingsNav.scrollToTools = true // [SUBTITLE-EXTRACT-SCROLL]
                    Tab.Settings.ordinal
                }
                "tool_run" -> { // [TOOLRUN-FGS] ketuk notifikasi proses Paket membuka lagi dialognya
                    com.molina.studio.settings.ToolRunHub.restore()
                    -1
                }
                "files" -> Tab.Files.ordinal
                "code" -> {
                    com.molina.studio.code.CodeNav.openEditor = true
                    -1
                }
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
                var codeOpen by rememberSaveable { mutableStateOf(false) } // Editor Code layar penuh, dibuka dari Berkas
                androidx.compose.runtime.LaunchedEffect(selected) { if (selected != Tab.Files.ordinal) codeOpen = false }
                androidx.compose.runtime.LaunchedEffect(selected) { com.molina.core.common.CrashContext.tab = this@MainActivity.getString(Tab.entries[selected].labelRes) }
                val codeSession = remember {
                    com.molina.code.ui.CodeSession(
                        AppPaths.get(this@MainActivity).appRoot.path, // [WORKSPACE-IN-MOLINA]
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
                        it.terminalSink = { text, execute -> com.molina.studio.code.CodeTerminalBridge.deliver(this@MainActivity, text, execute) }
                        it.guestPathResolver = { f -> com.molina.studio.code.CodeTerminalBridge.guestPath(f) } // [CODE-PYRUN]
                        it.debianRoot = { com.molina.studio.terminal.RootfsManager.rootfsDir(applicationContext) } // [CODE-DEBLIB]
                        val runPrefs = this@MainActivity.getSharedPreferences("molina_code_run", android.content.Context.MODE_PRIVATE) // [CODE-PYRUN2]
                        it.runStore = object : com.molina.code.ui.RunStore { // [CODE-PYRUN2]
                            override fun get(key: String): String? = runPrefs.getString(key, null)
                            override fun put(key: String, value: String) { runPrefs.edit().putString(key, value).apply() }
                        }
                        it.snippetSink = { name, cmd -> com.molina.studio.terminal.TerminalSnippets.addFromCode(applicationContext, name, cmd) }
                        it.vaultSink = { f -> this@MainActivity.sendToVault(f) } // MOLINA-H13-BRIDGE
                        com.molina.studio.code.CodeInbox.session = it
                        it.scriptSink = com.molina.code.run.ScriptSink { share ->
                            val r = com.molina.studio.code.ScriptStore.upsertFromEditor(share)
                            com.molina.code.run.ScriptSendResult(r.ok, r.message)
                        }
                    }
                }
                DisposableEffect(codeSession) { onDispose { com.molina.studio.code.CodeInbox.session = null; codeSession.releaseAll() } }
                // Script execution (Settings) menjalankan skrip di Molina Terminal: pindah ke tab Terminal.
                androidx.compose.runtime.LaunchedEffect(com.molina.studio.code.ScriptNav.openTerminal) {
                    if (com.molina.studio.code.ScriptNav.openTerminal) {
                        selected = Tab.Terminal.ordinal
                        com.molina.studio.code.ScriptNav.openTerminal = false
                    }
                }
                // Editor Code: layar penuh dari tab Berkas (bukan tab nav). Dipakai jembatan Terminal -> Code dan pintasan "code".
                androidx.compose.runtime.LaunchedEffect(com.molina.studio.code.CodeNav.openEditor) {
                    if (com.molina.studio.code.CodeNav.openEditor) {
                        selected = Tab.Files.ordinal
                        codeOpen = true
                        com.molina.studio.code.CodeNav.openEditor = false
                    }
                }
                // Monitor yang dibuka dari Terminal: tombol kembali memulangkan ke tab Terminal.
                androidx.compose.runtime.LaunchedEffect(com.molina.studio.settings.SettingsNav.backToTerminal) {
                    if (com.molina.studio.settings.SettingsNav.backToTerminal) {
                        selected = Tab.Terminal.ordinal
                        com.molina.studio.settings.SettingsNav.backToTerminal = false
                    }
                }
                // EDIT di Script Execution membuka skrip di editor Code: pindah ke tab Code.
                androidx.compose.runtime.LaunchedEffect(com.molina.studio.code.CodeNav.pending) {
                    com.molina.studio.code.CodeNav.pending?.let { req ->
                        codeSession.openScript(req.title, req.key, req.text)
                        selected = Tab.Files.ordinal; codeOpen = true
                        com.molina.studio.code.CodeNav.pending = null
                    }
                }
                // Penjelajah Molina: Buka di Code membuka berkas di editor lalu pindah ke tab Code.
                androidx.compose.runtime.LaunchedEffect(com.molina.studio.code.CodeNav.pendingFiles) {
                    val files = com.molina.studio.code.CodeNav.pendingFiles
                    if (files.isNotEmpty()) {
                        files.forEach { codeSession.openFile(it) }
                        selected = Tab.Files.ordinal; codeOpen = true
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
                com.molina.studio.settings.ToolRunHost() // [MINIMIZE-RUN]
                com.molina.studio.preview.PreviewHost(htmlWebView) // [NANO-PREVIEW]
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        if (!keyboardVisible && !codeOpen) {
                            Column {
                                com.molina.studio.settings.ToolRunPill() // [MINIMIZE-RUN]
                                Box(
                                    Modifier.fillMaxWidth().height(2.dp).background(
                                        Brush.horizontalGradient(
                                            // [NAV-RGB] rail RGB: merah -> hijau -> biru, ujung memudar
                                            listOf(
                                                Color.Transparent,
                                                Color(0xFFFF3B3B),
                                                Color(0xFF00E676),
                                                Color(0xFF448AFF),
                                                Color.Transparent
                                            )
                                        )
                                    )
                                )
                                // [NAV-SLIM] nav bawah kompak: tinggi 52dp (bawaan 80dp), warna ikon/label pekat
                                androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                                    Row(
                                        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Tab.entries.forEachIndexed { i, tab ->
                                            val sel = selected == i
                                            val c = if (sel) androidx.compose.ui.graphics.lerp(tab.color, Color.White, 0.15f)
                                            else tab.color.copy(alpha = 0.80f)
                                            val itemShape = RoundedCornerShape(14.dp)
                                            Column(
                                                Modifier.weight(1f).fillMaxHeight().padding(horizontal = 6.dp, vertical = 5.dp)
                                                    .clip(itemShape)
                                                    .background(if (sel) tab.color.copy(alpha = 0.22f) else Color.Transparent, itemShape)
                                                    .clickable(role = Role.Tab) { selected = i },
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                if (tab == Tab.Settings && (dl.running || com.molina.studio.download.DownloadNav.unseen)) {
                                                    androidx.compose.material3.BadgedBox(
                                                        badge = {
                                                            androidx.compose.material3.Badge(
                                                                containerColor = if (dl.running) com.molina.studio.ui.MolinaTheme.Brand
                                                                else com.molina.studio.ui.MolinaTheme.Good
                                                            )
                                                        }
                                                    ) { Text(tab.glyph, color = c, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                                                } else if (tab == Tab.Files) {
                                                    com.molina.studio.settings.TabFolderIcon(c)
                                                } else {
                                                    Text(tab.glyph, color = c, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                                }
                                                Text(
                                                    stringResource(tab.labelRes), color = c, fontSize = 11.sp, maxLines = 1,
                                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                ) { pad ->
                    when (Tab.entries[selected]) {
                        Tab.Files -> {
                            if (codeOpen) {
                                androidx.activity.compose.BackHandler(onBack = { codeOpen = false })
                                com.molina.code.ui.CodeScreen(codeSession, Modifier.fillMaxSize().padding(pad), onExit = { codeOpen = false })
                            } else {
                                // [EDITOR-IN-DROP] Editor kini di menu drop (ikon kisi) Penjelajah; pil mengambang dihapus agar tidak menimpa.
                                com.molina.studio.settings.FilesTabScreen(
                                    Modifier.fillMaxSize().padding(pad),
                                    onExit = { selected = Tab.Mpv.ordinal },
                                    onOpenEditor = { codeOpen = true }
                                )
                            }
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
                        Tab.Terminal -> {
                            com.molina.studio.terminal.TerminalScreen(
                                Modifier.fillMaxSize().padding(pad),
                                onOpenSettings = {
                                    com.molina.studio.settings.SettingsNav.openTerminal = true
                                    selected = Tab.Settings.ordinal
                                },
                                onOpenMonitor = {
                                    com.molina.studio.settings.SettingsNav.openMonitorFromTerminal = true
                                    selected = Tab.Settings.ordinal
                                }
                            )
                            return@Scaffold
                        }
                        else -> {}
                    }
                    Box(
                        Modifier.fillMaxSize().padding(pad),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Molina ${stringResource(Tab.entries[selected].labelRes)}",
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }
    }
}
