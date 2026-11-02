package com.molina.studio.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.log.BugReport
import com.molina.studio.log.LogScreen
import com.molina.studio.terminal.CrashReporter
import com.molina.studio.terminal.LogcatReporter
import com.molina.studio.terminal.PromptTheme
import com.molina.studio.terminal.TerminalPrefs
import java.io.File
import kotlin.math.roundToInt

/** Permintaan navigasi dari luar Settings (mis. tombol ⚙ di drawer sesi terminal). */
object SettingsNav {
    var openTerminal by mutableStateOf(false)
    var openDownloads by mutableStateOf(false)
    var openStorage by mutableStateOf(false)
    /** Buka halaman Terminal (sesi Debian) di dalam Settings; dulu tab nav bawah. */
    var openTerminalApp by mutableStateOf(false)
}

private enum class Page {
    Home, System, Terminal, Debugging, TerminalIO, TerminalView, Tampilan, CrashReports, Log, Stream, Network, Scripts, Packages, Bundle, Code, TerminalApp, Downloads, Storage, Monitor, Player, Stats, Info
}

private fun parentOf(p: Page): Page? = when (p) {
    Page.Home -> null
    Page.System, Page.Scripts, Page.Packages, Page.TerminalApp, Page.Downloads, Page.Player, Page.Stats -> Page.Home
    Page.Stream, Page.Bundle -> Page.Packages
    Page.Debugging, Page.TerminalIO, Page.TerminalView, Page.Tampilan -> Page.Terminal
    Page.CrashReports -> Page.Debugging
    Page.Log, Page.Monitor -> Page.Info // [INFO-PAGE]
    else -> Page.System
}

private fun titleOf(p: Page): String = when (p) {
    Page.Home -> "Settings"
    Page.System -> "Sistem"
    Page.Terminal -> "Molina Terminal"
    Page.Debugging -> "Debugging"
    Page.TerminalIO -> "Terminal I/O"
    Page.TerminalView -> "Terminal View"
    Page.Tampilan -> "Tampilan Molina"
    Page.CrashReports -> "Crash Reporter"
    Page.Log -> "Log"
    Page.Stream -> "Mesin cadangan (yt-dlp)"
    Page.Network -> "Keamanan dan privasi"
    Page.Scripts -> "Script execution"
    Page.Code -> "Editor Code"
    Page.Packages -> "Paket"
    Page.Bundle -> "Komponen bundel"
    Page.TerminalApp -> "Terminal"
    Page.Downloads -> "Unduhan"
    Page.Storage -> "Penyimpanan"
    Page.Monitor -> "Monitor Sistem"
    Page.Player -> "Pemutar"
    Page.Stats -> "Statistik tontonan"
    Page.Info -> "Info"
}

private fun trailOf(p: Page): List<Page> = generateSequence(p) { parentOf(it) }.toList().reversed()

private class Hit(val title: String, val summary: String, val page: Page)

/** Indeks pencarian Settings: judul, keterangan singkat, dan halaman tujuan. */
private val INDEX = listOf(
    Hit("Pengaturan Terminal", "Molina Terminal: debugging, I/O, tampilan", Page.Terminal),
    Hit("Debugging", "Log level, key logging, notifikasi crash", Page.Debugging),
    Hit("Log Level", "Off, Normal, Debug, Verbose", Page.Debugging),
    Hit("Crash Reporter", "Lihat, salin, atau hapus laporan crash", Page.CrashReports),
    Hit("Logcat Reporter", "Salurkan logcat aplikasi ke halaman Log", Page.Debugging),
    Hit("Terminal I/O", "Keyboard dan penyimpanan", Page.TerminalIO),
    Hit("Akses penyimpanan penuh", "Seluruh /sdcard terlihat di dalam Debian", Page.TerminalIO),
    Hit("Terminal View", "Terminal Margin Adjustment", Page.TerminalView),
    Hit("Tampilan Molina", "Ukuran font dan tema warna prompt", Page.Tampilan),
    Hit("Log", "Log aktivitas mpv, Terminal, dan sistem", Page.Log),
    Hit("Mesin cadangan (yt-dlp)", "TikTok, Facebook, dll. lewat Debian (default mati)", Page.Stream),
    Hit("Keamanan dan privasi", "Kebijakan URL, jaringan lokal (LAN), layar privat", Page.Network),
    Hit("Molina Shield", "Filter lalu lintas pemutar", Page.Network),
    Hit("Script execution", "Kelola dan jalankan skrip dari editor Code di Molina Terminal", Page.Scripts),
    Hit("Kirim ke Script Execution", "Skrip dari editor Code (menu titik tiga) tampil di Script execution", Page.Scripts),
    Hit("Tambah skrip", "Skrip umum baru; isinya ditulis di editor Code", Page.Scripts),
    Hit("Editor Code", "Pratinjau HTML: renderer native atau WebView", Page.Code),
    Hit("Pratinjau HTML", "WebView untuk halaman HTML (JavaScript dan jaringan mati)", Page.Code),
    Hit("Paket", "Pusat paket: pembaruan, paket Debian (apt), alat pasang, versi, hapus (Python 3, Git, FFmpeg, yt-dlp)", Page.Packages),
    Hit("Tambah alat", "Buat alat sendiri dengan skrip pasang, versi, hapus", Page.Packages),
    Hit("Komponen bundel", "Daftar komponen, versi, peran, dan jaminan keamanan (NewPipe, libmpv, PRoot, yt-dlp)", Page.Bundle),
    Hit("Keamanan komponen", "Ringkasan hash SHA-256 dan sumber dipin untuk komponen bundel", Page.Bundle),
    Hit("apt update", "Perbarui dan tingkatkan paket Debian (apt update && apt upgrade -y)", Page.Packages),
    Hit("apt upgrade", "Perbarui dan tingkatkan paket Debian (apt update && apt upgrade -y)", Page.Packages),
    Hit("Periksa versi alat", "Periksa semua versi Python 3, Git, FFmpeg di Paket > Alat", Page.Packages),
    Hit("Cadangan dan pemulihan", "Cadangkan atau pulihkan penanda video, snippet, dan setelan (folder Backup)", Page.System),
    Hit("Pulihkan dari Backup", "Pulihkan penanda video, snippet, dan setelan dari molina-backup.json", Page.System),
    Hit("Profil setelan", "Simpan setelan sebagai profil bernama (mis. Nonton, Dev, Hemat Baterai) dan ganti dengan satu ketukan", Page.System),
    Hit("Diagnostik sekali klik", "Bangun ZIP diagnostik (perangkat, versi mpv/yt-dlp, status PRoot, laporan bug, crash) dan bagikan", Page.Info),
    Hit("Terminal (sesi)", "Buka sesi Debian PRoot di Molina Terminal", Page.TerminalApp),
    Hit("Unduhan", "Unduh video atau audio dari URL: antrean, riwayat, ulangi yang gagal", Page.Downloads),
    Hit("Penyimpanan", "Ukuran Debian, cache, Recycle Bin, folder kerja, dan bersihkan ruang", Page.Storage),
    Hit("Bersihkan cache", "Hapus cache aplikasi dan sisa sementara (cache apt ada di Paket)", Page.Storage),
    Hit("Bersihkan cache apt", "Hapus cache .deb dan paket yatim di Debian (autoremove dan apt clean)", Page.Packages),
    Hit("Pusat Pembersihan Cerdas", "Estimasi ruang per kategori: cache, sisa unduhan Debian, laporan lama, file sementara, Recycle Bin", Page.Storage),
    Hit("Monitor Sistem", "Kecepatan unduh dan unggah, pemakaian CPU dan RAM secara langsung", Page.Monitor),
    Hit("CPU", "Pemakaian CPU seluruh proses Molina", Page.Monitor),
    Hit("RAM", "Pemakaian memori seluruh proses Molina", Page.Monitor),
    Hit("Kecepatan jaringan", "Unduh dan unggah per detik", Page.Monitor),
    Hit("Pembaruan paket", "Status pembaruan Debian, yt-dlp, NewPipe dan pemeriksaan otomatis (Wi-Fi, 24 jam)", Page.Packages),
    Hit("Pembaruan otomatis", "Perbarui lencana paket Debian dan yt-dlp lewat WorkManager", Page.Packages),
    Hit("Cadangan otomatis", "Cadangan gabungan harian ke folder Backup", Page.System),
    Hit("Unduhan masuk ke Cinema", "Unduhan selesai otomatis muncul di Terakhir diputar Molina Cinema", Page.Player),
    Hit("Statistik tontonan", "Waktu tonton per hari dan video paling sering diputar (lokal)", Page.Stats),
    Hit("Waktu tonton", "Total jam putar aktif, grafik 7 hari, judul teratas", Page.Stats),
    Hit("Pemutar", "Molina Cinema: ingat posisi putar, dekoder, sumber IPTV", Page.Player),
    Hit("Ingat posisi putar", "Lanjutkan video dari posisi terakhir", Page.Player),
    Hit("Dekoder perangkat keras", "MediaCodec untuk pemutar mpv", Page.Player),
    Hit("Mode TV", "Tile Mode TV di Beranda Cinema: jelajah berkas ala saluran TV", Page.Player),
    Hit("Sumber IPTV", "Daftar playlist saluran dan Folder IPTV", Page.Player),
    Hit("Laporan bug", "Ekspor log gabungan dan per kelompok ke folder Export", Page.Info),
    Hit("About", "Versi, perangkat, status, dan lisensi (kini di Sistem > Info)", Page.Info),
    Hit("Salin info debug", "Salin ringkasan versi dan status untuk laporan bug", Page.Info),
    Hit("Info", "Status Molina dan informasi dasar perangkat", Page.Info),
    Hit("Rilis Debian", "Versi Debian dan jumlah paket terpasang di Paket > Debian (apt)", Page.Packages),
    Hit("Pasang paket Starter", "Profil utilitas: ca-certificates, python3, python3-mutagen, openssh-client, yt-dlp di Paket > Debian (apt) [STARTER-YTDLP]", Page.Packages),
    Hit("Kelola rootfs Debian", "Backup, restore, dan reset rootfs Debian di Sistem", Page.System),
    Hit("Status Molina", "Ringkasan Debian, paket, yt-dlp, dan cadangan di Sistem > Info", Page.Info),
    Hit("Informasi perangkat", "Versi Molina Studio, Android, perangkat, arsitektur, dan RAM", Page.Info),
    Hit("Versi libmpv", "Versi Molina Terminal, Molina Cinema, libmpv, dan FFmpeg", Page.Info)
)

/**
 * Settings Hub bergaya Panel Kontrol Windows 7. Beranda hanya berisi ubin "Sistem";
 * halaman Sistem memuat Terminal, Log, Mesin cadangan, Keamanan, About, dan ringkasan perangkat. Halaman Terminal meniru
 * struktur Settings Termux (Debugging, Terminal I/O, Terminal View) dan menambah "Tampilan Molina",
 * Crash Reporter, serta Logcat Reporter. Menu Donate dan aplikasi pendamping Termux sengaja tidak ada.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    var page by rememberSaveable { mutableStateOf(Page.Home) }
    var query by rememberSaveable { mutableStateOf("") }
    var lastTile by rememberSaveable { mutableStateOf<Page?>(null) }
    var showRefresh by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    // Lepas fokus (dan tutup keyboard) setiap kali halaman Settings berganti.
    LaunchedEffect(page) { focus.clearFocus() }
    var apt by remember { mutableStateOf<AptJob?>(null) }
    var showBackup by remember { mutableStateOf(false) }
    var showRootfsAdmin by remember { mutableStateOf(false) } // [TERM-ROOTFS2]
    var showProfiles by remember { mutableStateOf(false) }
    var showDiag by remember { mutableStateOf(false) }
    val aptCtx = LocalContext.current
    remember { AptHistory.init(aptCtx) }
    remember { MolinaExplorer.attach(aptCtx) }
    val aptPending by AptHistory.pending.collectAsState() // [PAKET-PUSAT] aptLast pindah ke halaman Paket
    remember { com.molina.studio.automation.AutoPrefs.init(aptCtx) }
    val autoSt by com.molina.studio.automation.AutoPrefs.state.collectAsState()
    fun open(p: Page) { query = ""; page = p }
    BackHandler(enabled = query.isNotBlank() || page != Page.Home) {
        if (query.isNotBlank()) query = "" else page = parentOf(page) ?: Page.Home
    }
    LaunchedEffect(SettingsNav.openTerminal) {
        if (SettingsNav.openTerminal) {
            open(Page.Terminal)
            SettingsNav.openTerminal = false
        }
    }
    LaunchedEffect(SettingsNav.openTerminalApp) {
        if (SettingsNav.openTerminalApp) {
            open(Page.TerminalApp)
            SettingsNav.openTerminalApp = false
        }
    }
    LaunchedEffect(SettingsNav.openDownloads) {
        if (SettingsNav.openDownloads) {
            open(Page.Downloads)
            SettingsNav.openDownloads = false
        }
    }
    LaunchedEffect(SettingsNav.openStorage) {
        if (SettingsNav.openStorage) {
            open(Page.Storage)
            SettingsNav.openStorage = false
        }
    }
    // Penanda untuk lencana tab Settings: halaman Unduhan sedang dilihat.
    androidx.compose.runtime.DisposableEffect(page == Page.Downloads) {
        com.molina.studio.download.DownloadNav.visible = page == Page.Downloads
        if (page == Page.Downloads) com.molina.studio.download.DownloadNav.unseen = false
        onDispose { com.molina.studio.download.DownloadNav.visible = false }
    }

    val trail = trailOf(page)
    val searching = query.isNotBlank()
    Win7SettingsWindow(
        modifier = modifier,
        crumbs = trail.map { titleOf(it) },
        canUp = page != Page.Home,
        onUp = { open(parentOf(page) ?: Page.Home) },
        onCrumb = { i -> open(trail[i]) },
        query = query,
        onQuery = { query = it },
        dark = !searching && (page == Page.Log || page == Page.Tampilan),
        actions = { RefreshIconButton { showRefresh = true } },
        showHeader = searching || page != Page.TerminalApp
    ) {
        if (showRefresh) SystemRefreshDialog { showRefresh = false }
        apt?.let { job -> DebianAptDialog(job) { apt = null } }
        if (showBackup) BackupDialog { showBackup = false }
        if (showRootfsAdmin) RootfsAdminDialog { showRootfsAdmin = false } // [TERM-ROOTFS2]
        if (showProfiles) ProfileDialog { showProfiles = false }
        if (showDiag) DiagnosticsDialog { showDiag = false }
        when {
            searching -> SearchResults(query) { open(it) }
            page == Page.Log -> LogScreen(Modifier.fillMaxSize())
            page == Page.Downloads -> com.molina.studio.download.UnduhanScreen(Modifier.fillMaxSize())
            page == Page.TerminalApp -> com.molina.studio.terminal.TerminalScreen(
                Modifier.fillMaxSize(),
                onOpenSettings = { open(Page.Terminal) }
            )
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                W7Heading(if (page == Page.Home) "Sesuaikan setelan Molina" else titleOf(page))
                when (page) {
                    Page.Home -> {
                        // [INFO-PAGE] beranda: kartu Status Molina pindah ke Sistem > Info
                        W7CategoryTile(
                            icon = { W7IconMonitor(52.dp) },
                            title = "Sistem",
                            links = emptyList(),
                            selected = lastTile == Page.System,
                            onOpen = { lastTile = Page.System; open(Page.System) }
                        )
                        W7CategoryTile(
                            icon = { W7IconScriptBig(52.dp) },
                            title = "Script execution",
                            links = emptyList(),
                            selected = lastTile == Page.Scripts,
                            onOpen = { lastTile = Page.Scripts; open(Page.Scripts) }
                        )
                        W7CategoryTile(
                            icon = { W7IconPackageBig(52.dp) },
                            title = "Paket",
                            links = emptyList(),
                            selected = lastTile == Page.Packages,
                            badge = updateCount(autoSt, aptPending).let { if (it > 0) it.toString() else null },
                            badgeGold = true,
                            onOpen = { lastTile = Page.Packages; open(Page.Packages) }
                        )
                        W7CategoryTile(
                            icon = { W7IconTerminal() },
                            title = "Terminal",
                            links = emptyList(),
                            selected = lastTile == Page.TerminalApp,
                            onOpen = { lastTile = Page.TerminalApp; open(Page.TerminalApp) }
                        )
                        W7CategoryTile(
                            icon = { W7IconDownloadBig(52.dp) },
                            title = "Unduhan",
                            links = emptyList(),
                            selected = lastTile == Page.Downloads,
                            onOpen = { lastTile = Page.Downloads; open(Page.Downloads) }
                        )
                        W7CategoryTile(
                            icon = { W7IconPlayerBig(52.dp) },
                            title = "Pemutar",
                            links = emptyList(),
                            selected = lastTile == Page.Player,
                            onOpen = { lastTile = Page.Player; open(Page.Player) }
                        )
                        W7CategoryTile(
                            icon = { W7IconStatsBig(52.dp) },
                            title = "Statistik tontonan",
                            links = emptyList(),
                            selected = lastTile == Page.Stats,
                            onOpen = { lastTile = Page.Stats; open(Page.Stats) }
                        )
                    }
                    Page.System -> {
                        W7Link({ W7IconInfo() }, "Info", "Status Molina, versi, perangkat, dan diagnostik") { open(Page.Info) } // [INFO-PAGE]
                        W7Link({ W7IconTerminal() }, "Pengaturan Terminal", "Molina Terminal: debugging, I/O, tampilan") { open(Page.Terminal) }
                        W7Link({ W7IconTerminal() }, "Editor Code", "Pratinjau HTML: renderer native atau WebView") { open(Page.Code) }
                        W7Link({ W7IconShield() }, "Keamanan dan privasi", "Kebijakan URL, jaringan lokal (LAN), layar privat") { open(Page.Network) }
                        W7Link({ W7IconToolList() }, "Penyimpanan", "Ukuran Debian, cache, Recycle Bin, dan bersihkan ruang") { open(Page.Storage) }
                        Category("Cadangan dan profil") // [SETELAN-PUSAT]
                        W7Link({ W7IconToolList() }, "Cadangan dan pemulihan", "Penanda video, snippet, dan setelan dalam satu berkas Backup") { showBackup = true }
                        AutoBackupRows()
                        W7Link({ W7IconToolList() }, "Profil setelan", "Simpan dan ganti kumpulan setelan dengan satu ketukan") { showProfiles = true }
                        Category("Debian")
                        W7Link({ W7IconRootfs() }, "Kelola rootfs Debian", "Cadangkan, pulihkan, atau atur ulang seluruh rootfs (tar.gz)") { showRootfsAdmin = true } // [TERM-ROOTFS2]
                    }
                    Page.Info -> HubInfoPage( // [INFO-PAGE] rumah informasi diagnostik; gabungan About lama
                        onSystem = { open(Page.System) },
                        onPackages = { lastTile = Page.Packages; open(Page.Packages) },
                        onAutomation = { open(Page.System) }, // [SETELAN-PUSAT] cadangan kini di Sistem
                        onDiagnostics = { showDiag = true },
                        onBugReport = { BugReport.export(aptCtx) },
                        onLog = { open(Page.Log) },
                        onMonitor = { open(Page.Monitor) }
                    )
                    Page.Terminal -> {
                        SettingsItem("Debugging", "Preferences for debugging") { page = Page.Debugging }
                        SettingsItem("Terminal I/O", "Preferences for terminal I/O") { page = Page.TerminalIO }
                        SettingsItem("Terminal View", "Preferences for terminal view") { page = Page.TerminalView }
                        SettingsItem("Tampilan Molina", "Ukuran font dan tema warna prompt") { page = Page.Tampilan }
                    }
                    Page.Debugging -> DebuggingPage(
                        onOpenCrash = { page = Page.CrashReports },
                        onOpenLog = { page = Page.Log }
                    )
                    Page.TerminalIO -> TerminalIoPage()
                    Page.TerminalView -> TerminalViewPage()
                    Page.Tampilan -> TampilanPage()
                    Page.CrashReports -> CrashReportsPage()
                    Page.Log -> {}
                    Page.Downloads -> {}
                    Page.Stats -> WatchStatsPage()
                    Page.Player -> PlayerSettingsPage(onOpenCinema = { com.molina.studio.ShortcutNav.tab = 0 })
                    Page.Stream -> StreamEnginePage()
                    Page.Network -> NetworkSecurityPage()
                    Page.Scripts -> ScriptsPage()
                    Page.Code -> CodeSettingsPage()
                    Page.Packages -> PackagesPage(
                        onOpenYtdlp = { open(Page.Stream) },
                        onOpenBundle = { open(Page.Bundle) },
                        onApt = { apt = it }
                    )
                    Page.Bundle -> BundlePage(onOpenYtdlp = { open(Page.Stream) })
                    Page.Storage -> StoragePage(onOpenPackages = { open(Page.Packages) })
                    Page.Monitor -> SystemMonitorPage()
                    Page.TerminalApp -> {}
                }
            }
        }
    }
}

@Composable
private fun SearchResults(query: String, onOpen: (Page) -> Unit) {
    val q = query.trim()
    val hits = INDEX.filter { it.title.contains(q, ignoreCase = true) || it.summary.contains(q, ignoreCase = true) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        W7Heading(if (hits.isEmpty()) "Tidak ada hasil untuk \"$q\"" else "Hasil pencarian: ${hits.size}")
        hits.forEach { h ->
            val path = trailOf(h.page).joinToString(" \u203A ") { titleOf(it) }
            W7Link({ W7IconHit() }, h.title, h.summary + "\n" + path) { onOpen(h.page) }
        }
    }
}

// ---------- Debugging ----------

private val LOG_LEVELS = listOf("Off", "Normal", "Debug", "Verbose")

@Composable
private fun DebuggingPage(onOpenCrash: () -> Unit, onOpenLog: () -> Unit) {
    var showLevel by remember { mutableStateOf(false) }
    Category("Logging")
    SettingsItem("Log Level", LOG_LEVELS[TerminalPrefs.logLevel]) { showLevel = true }
    SwitchRow(
        "Terminal View Key Logging",
        if (TerminalPrefs.keyLogging) "Logcat logs will have entries for terminal view keys."
        else "Logs will not have entries for terminal view keys. (Default)",
        TerminalPrefs.keyLogging
    ) { TerminalPrefs.setKeyLogging(it) }
    SwitchRow(
        "Plugin Error Notifications",
        "Molina belum memiliki plugin; nilai disimpan untuk fase mpv dan Code.",
        TerminalPrefs.pluginErrorNotifications
    ) { TerminalPrefs.setPluginErrorNotifications(it) }
    SwitchRow(
        "Crash Report Notifications",
        if (TerminalPrefs.crashNotifications) "Show notifications for crash reports. (Default)"
        else "Disable notifications for crash reports.",
        TerminalPrefs.crashNotifications
    ) { TerminalPrefs.setCrashNotifications(it) }

    Category("Molina CLI")
    SwitchRow(
        "Akses clipboard dari CLI",
        "Izinkan perintah molina clip membaca dan menulis clipboard. Semua program di Debian bisa memakainya; biarkan mati bila tidak perlu.",
        TerminalPrefs.cliClipboard
    ) { TerminalPrefs.setCliClipboard(it) }

    Category("Transkrip Terminal")
    SwitchRow(
        "Samarkan token saat menyimpan transkrip",
        if (TerminalPrefs.redactTranscript) "URL hanya menyisakan skema dan host; token=, password=, dan sejenisnya diganti ***."
        else "Transkrip disimpan apa adanya (URL dan token tetap utuh). Folder /sdcard/Molina terbaca aplikasi lain. (Default)",
        TerminalPrefs.redactTranscript
    ) { TerminalPrefs.setRedactTranscript(it) }

    Category("Molina Reporter")
    SettingsItem("Crash Reporter", "Lihat, salin, atau hapus laporan crash", onOpenCrash)
    SwitchRow(
        "Logcat Reporter",
        "Salurkan logcat aplikasi ke halaman Log (sumber: logcat), digabung oleh LogGrouping.",
        TerminalPrefs.logcatReporter
    ) {
        TerminalPrefs.setLogcatReporter(it)
        LogcatReporter.setEnabled(it)
    }
    SettingsItem("Buka Log", "Tampilkan log terkumpul", onOpenLog)

    if (showLevel) {
        AlertDialog(
            onDismissRequest = { showLevel = false },
            confirmButton = { TextButton(onClick = { showLevel = false }) { Text("Tutup") } },
            title = { Text("Log Level") },
            text = {
                Column {
                    LOG_LEVELS.forEachIndexed { i, name ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                TerminalPrefs.setLogLevel(i); showLevel = false
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = TerminalPrefs.logLevel == i, onClick = {
                                TerminalPrefs.setLogLevel(i); showLevel = false
                            })
                            Text(name)
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun CrashReportsPage() {
    val ctx = LocalContext.current
    var files by remember { mutableStateOf(CrashReporter.list(ctx)) }
    var open by remember { mutableStateOf<File?>(null) }

    if (files.isEmpty()) {
        Text(
            "Belum ada laporan crash.", modifier = Modifier.padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
    files.forEach { f ->
        SettingsItem(CrashReporter.title(f), "${f.length()} byte") { open = f }
    }
    if (files.isNotEmpty()) {
        TextButton(onClick = { CrashReporter.clearAll(ctx); files = emptyList() }) {
            Text("Hapus semua")
        }
    }

    open?.let { f ->
        val text = remember(f) { CrashReporter.read(f) }
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(CrashReporter.title(f)) },
            text = {
                Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { copyText(ctx, text) }) { Text("Salin") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        CrashReporter.delete(f); files = CrashReporter.list(ctx); open = null
                    }) { Text("Hapus") }
                    TextButton(onClick = { open = null }) { Text("Tutup") }
                }
            }
        )
    }
}

private fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("molina-crash", text))
    Toast.makeText(ctx, "Laporan disalin", Toast.LENGTH_SHORT).show()
}

// ---------- Terminal I/O ----------

@Composable
private fun TerminalIoPage() {
    Category("Keyboard")
    SwitchRow(
        "Soft Keyboard Enabled",
        if (TerminalPrefs.softKeyboard) "Soft keyboard will be enabled. (Default)"
        else "Soft keyboard will be disabled.",
        TerminalPrefs.softKeyboard
    ) { TerminalPrefs.setSoftKeyboard(it) }
    SwitchRow(
        "Soft Keyboard Only If No Hardware",
        if (TerminalPrefs.softKeyboardOnlyIfNoHardware)
            "Soft keyboard will be enabled only if no hardware keyboard is connected."
        else "Soft keyboard will be enabled even if hardware keyboard is connected. (Default)",
        TerminalPrefs.softKeyboardOnlyIfNoHardware
    ) { TerminalPrefs.setSoftKeyboardOnlyIfNoHardware(it) }

    Category("Penyimpanan")
    SwitchRow(
        "Akses penyimpanan penuh",
        if (TerminalPrefs.fullStorageAccess)
            "Seluruh /sdcard terlihat di dalam Debian. Berlaku di sesi baru."
        else "Hanya /sdcard/Molina yang terlihat di dalam Debian. (Default) Berlaku di sesi baru.",
        TerminalPrefs.fullStorageAccess
    ) { TerminalPrefs.setFullStorageAccess(it) }
}

// ---------- Terminal View ----------

@Composable
private fun TerminalViewPage() {
    Category("View")
    SwitchRow(
        "Terminal Margin Adjustment",
        if (TerminalPrefs.marginAdjustment) "Terminal margin adjustment will be enabled. (Default)"
        else "Terminal margin adjustment will be disabled.",
        TerminalPrefs.marginAdjustment
    ) { TerminalPrefs.setMarginAdjustment(it) }
}

// ---------- Tampilan Molina ----------

@OptIn(ExperimentalMaterial3Api::class) // [SLIDER-THUMB] parameter thumb Slider masih eksperimental
@Composable
private fun TampilanPage() {
    val ctx = LocalContext.current
    val theme = TerminalPrefs.promptTheme
    val sp = TerminalPrefs.fontSp

    Category("Ukuran font")
    Text(
        "${sp}sp  (${TerminalPrefs.MIN_FONT_SP}\u2013${TerminalPrefs.MAX_FONT_SP}sp)",
        color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium
    )
    // [SLIDER-THUMB] thumb bawaan M3 setinggi 44dp (garis kuning terlalu panjang); dipendekkan jadi 24dp
    val thumbInteraction = remember { MutableInteractionSource() }
    Slider(
        value = sp.toFloat(),
        onValueChange = { TerminalPrefs.setFontSp(it.roundToInt()) },
        valueRange = TerminalPrefs.MIN_FONT_SP.toFloat()..TerminalPrefs.MAX_FONT_SP.toFloat(),
        steps = TerminalPrefs.MAX_FONT_SP - TerminalPrefs.MIN_FONT_SP - 1,
        interactionSource = thumbInteraction,
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = thumbInteraction,
                thumbSize = DpSize(4.dp, 24.dp)
            )
        }
    )
    Text(
        "user@localhost:~ $", fontFamily = FontFamily.Monospace, fontSize = sp.sp,
        fontWeight = FontWeight.Bold, color = theme.homeColor
    )
    Text(
        "Di terminal, cubit dua jari (pinch) juga mengubah ukuran ini, per 1sp.",
        modifier = Modifier.padding(top = 6.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        style = MaterialTheme.typography.bodySmall
    )

    Spacer(Modifier.height(12.dp))
    Category("Tema warna prompt")
    PromptTheme.entries.forEach { t ->
        Row(
            Modifier.fillMaxWidth().clickable { TerminalPrefs.setPromptTheme(ctx, t) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = t == theme, onClick = { TerminalPrefs.setPromptTheme(ctx, t) })
            Column {
                Text(t.label, color = MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "user@localhost:~ $", fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, color = t.homeColor
                    )
                    Text(
                        "user@localhost:/etc $", fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, color = t.otherColor
                    )
                }
            }
        }
    }
    Text(
        "Warna baru tampil pada prompt berikutnya (tekan Enter di terminal).",
        modifier = Modifier.padding(top = 6.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        style = MaterialTheme.typography.bodySmall
    )
}

// ---------- About: lihat AboutUi.kt ----------

// ---------- Komponen bersama ----------

@Composable
internal fun Category(title: String) {
    Text(
        title, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge
    )
}

@Composable
internal fun SettingsItem(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp)) {
        Text(
            title, color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            subtitle, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
internal fun SwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                title, color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                summary, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
