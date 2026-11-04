package com.molina.studio.settings

import com.molina.studio.ui.components.PackageChoiceRow
import com.molina.studio.ui.components.PackageDivider
import com.molina.studio.ui.components.PackageGroupCard
import com.molina.studio.ui.components.PackageNote
import com.molina.studio.ui.components.PackageSectionHeader
import com.molina.studio.ui.components.PackageSliderRow
import com.molina.studio.ui.components.PackageStatusCard
import com.molina.studio.ui.components.PackageSwitchRow
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.log.LogScreen
import com.molina.studio.terminal.CrashReporter
import com.molina.studio.terminal.LogcatReporter
import com.molina.studio.terminal.PromptTheme
import com.molina.studio.terminal.BannerStyle
import com.molina.studio.terminal.TerminalLook
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
    Home, System, Terminal, Debugging, TerminalIO, TerminalView, Tampilan, CrashReports, Log, Stream, Network, Scripts, Packages, Bundle, Code, TerminalApp, Downloads, Storage, Monitor, Player, Stats, Info, Licenses, AboutMolina
}

private fun parentOf(p: Page): Page? = when (p) {
    Page.Home -> null
    Page.System, Page.Scripts, Page.Packages, Page.TerminalApp, Page.Downloads, Page.Player, Page.Stats -> Page.Home
    Page.Stream, Page.Bundle -> Page.Packages
    Page.Debugging, Page.TerminalIO, Page.TerminalView, Page.Tampilan -> Page.Terminal
    Page.CrashReports -> Page.Debugging
    Page.Log, Page.Monitor, Page.Licenses, Page.AboutMolina -> Page.Info // [INFO-PAGE]
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
    Page.Stream -> "Pengunduh tambahan (yt-dlp)"
    Page.Network -> "Keamanan dan privasi"
    Page.Scripts -> "Script execution"
    Page.Code -> "Editor Code"
    Page.Packages -> "Paket"
    Page.Bundle -> "Komponen bawaan Molina"
    Page.TerminalApp -> "Terminal"
    Page.Downloads -> "Unduhan"
    Page.Storage -> "Penyimpanan"
    Page.Monitor -> "Monitor Sistem"
    Page.Player -> "Pemutar"
    Page.Stats -> "Statistik tontonan"
    Page.Info -> "Info"
    Page.Licenses -> "Lisensi dan atribusi"
    Page.AboutMolina -> "Tentang Molina"
}

private fun trailOf(p: Page): List<Page> = generateSequence(p) { parentOf(it) }.toList().reversed()

private class Hit(val title: String, val summary: String, val page: Page)

/** Indeks pencarian Settings: judul, keterangan singkat, dan halaman tujuan. */
private val INDEX = listOf(
    Hit("Pengaturan Terminal", "Atur tampilan dan perilaku Molina Terminal", Page.Terminal),
    Hit("Debugging", "Log level, key logging, notifikasi crash", Page.Debugging),
    Hit("Log Level", "Off, Normal, Debug, Verbose", Page.Debugging),
    Hit("Crash Reporter", "Lihat, salin, atau hapus laporan crash", Page.CrashReports),
    Hit("Logcat Reporter", "Salurkan logcat aplikasi ke halaman Log", Page.Debugging),
    Hit("Terminal I/O", "Keyboard dan penyimpanan", Page.TerminalIO),
    Hit("Akses penyimpanan penuh", "Seluruh /sdcard terlihat di dalam Debian", Page.TerminalIO),
    Hit("Terminal View", "Penyesuaian margin terminal (Terminal Margin Adjustment)", Page.TerminalView),
    Hit("Tampilan Molina", "Ukuran font dan tema warna prompt", Page.Tampilan),
    Hit("Log", "Log aktivitas mpv, Terminal, dan sistem", Page.Log),
    Hit("Pengunduh tambahan (yt-dlp)", "Untuk TikTok, Facebook, dan situs lain yang tidak dikenali pemutar (bawaan: mati)", Page.Stream),
    Hit("Keamanan dan privasi", "Atur tautan yang boleh dibuka, akses jaringan rumah, dan layar privat", Page.Network),
    Hit("Molina Shield", "Filter lalu lintas pemutar", Page.Network),
    Hit("Script execution", "Kelola dan jalankan skrip dari editor Code di Molina Terminal", Page.Scripts),
    Hit("Kirim ke Script Execution", "Skrip dari editor Code (menu titik tiga) tampil di Script execution", Page.Scripts),
    Hit("Tambah skrip", "Skrip umum baru; isinya ditulis di editor Code", Page.Scripts),
    Hit("Editor Code", "Cara menampilkan pratinjau halaman web saat Anda mengedit kode", Page.Code),
    Hit("Pratinjau HTML", "WebView untuk halaman HTML (JavaScript dan jaringan mati)", Page.Code),
    Hit("Paket", "Pembaruan program Debian, alat tambahan (Python 3, Git, FFmpeg, yt-dlp): pasang, cek versi, hapus", Page.Packages),
    Hit("Tambah alat", "Daftarkan alat buatan sendiri beserta cara memasang, mengecek, dan menghapusnya", Page.Packages),
    Hit("Komponen bawaan Molina", "Daftar komponen bawaan Molina, versinya, fungsinya, dan keamanannya (NewPipe, libmpv, PRoot, yt-dlp)", Page.Bundle),
    Hit("Keamanan komponen", "Bukti keaslian komponen bawaan: sidik digital (SHA-256) dan sumber yang dikunci", Page.Bundle),
    Hit("apt update", "Cek lalu pasang pembaruan program Debian (dikenal juga sebagai apt update dan apt upgrade)", Page.Packages),
    Hit("apt upgrade", "Cek lalu pasang pembaruan program Debian (dikenal juga sebagai apt update dan apt upgrade)", Page.Packages),
    Hit("Periksa versi alat", "Cek versi semua alat sekaligus (Python 3, Git, FFmpeg) di Paket > Alat", Page.Packages),
    Hit("Cadangan dan pemulihan", "Simpan atau pulihkan penanda video, potongan perintah, dan setelan (folder Backup)", Page.System),
    Hit("Pulihkan dari Backup", "Pulihkan penanda video, snippet, dan setelan dari molina-backup.json", Page.System),
    Hit("Profil setelan", "Simpan setelan sebagai profil bernama (mis. Nonton, Dev, Hemat Baterai) dan ganti dengan satu ketukan", Page.System),
    Hit("Diagnostik dan laporan bug", "Salin info debug, diagnostik sekali klik (satu ZIP), dan laporan bug ke folder Export dalam satu tempat", Page.Info),
    Hit("Terminal (sesi)", "Buka layar perintah Debian di Molina Terminal", Page.TerminalApp),
    Hit("Unduhan", "Simpan video atau lagu dari tautan: daftar tunggu, riwayat, dan ulangi yang gagal", Page.Downloads),
    Hit("Penyimpanan", "Lihat pemakaian ruang (Debian, cache, Recycle Bin, folder kerja) dan bersihkan", Page.Storage),
    Hit("Bersihkan cache", "Hapus file sementara aplikasi (sisa program Debian dibersihkan lewat Paket)", Page.Storage),
    Hit("Bersihkan cache apt", "Hapus file unduhan lama dan program yang tidak terpakai di Debian", Page.Packages),
    Hit("Pusat Pembersihan Cerdas", "Estimasi ruang per kategori: cache, sisa unduhan Debian, laporan lama, file sementara, Recycle Bin", Page.Storage),
    Hit("Monitor Sistem", "Kecepatan internet serta pemakaian prosesor (CPU) dan memori (RAM) secara langsung", Page.Monitor),
    Hit("CPU", "Pemakaian CPU seluruh proses Molina", Page.Monitor),
    Hit("RAM", "Pemakaian memori seluruh proses Molina", Page.Monitor),
    Hit("Kecepatan jaringan", "Unduh dan unggah per detik", Page.Monitor),
    Hit("Pembaruan paket", "Status pembaruan Debian, yt-dlp, dan NewPipe, termasuk pengecekan otomatis (lewat Wi-Fi, tiap 24 jam)", Page.Packages),
    Hit("Pembaruan otomatis", "Cek pembaruan Debian dan yt-dlp otomatis di latar belakang", Page.Packages),
    Hit("Cadangan otomatis", "Cadangan gabungan harian ke folder Backup", Page.System),
    Hit("Unduhan masuk ke Cinema", "Unduhan selesai otomatis muncul di Terakhir diputar Molina Cinema", Page.Player),
    Hit("Statistik tontonan", "Waktu tonton per hari dan video paling sering diputar (lokal)", Page.Stats),
    Hit("Waktu tonton", "Total jam putar aktif, grafik 7 hari, judul teratas", Page.Stats),
    Hit("Pemutar", "Molina Cinema: ingat posisi putar, dekoder, sumber IPTV", Page.Player),
    Hit("Ingat posisi putar", "Lanjutkan video dari posisi terakhir", Page.Player),
    Hit("Dekoder perangkat keras", "MediaCodec untuk pemutar mpv", Page.Player),
    Hit("Mode TV", "Tile Mode TV di Beranda Cinema: jelajah berkas ala saluran TV", Page.Player),
    Hit("Sumber IPTV", "Daftar playlist saluran dan Folder IPTV", Page.Player),
    Hit("About", "Versi, perangkat, status, dan lisensi (kini di Sistem > Info)", Page.Info),
    Hit("Info", "Status Molina dan informasi dasar perangkat", Page.Info),
    Hit("Tentang Molina", "Apa itu Molina: fitur, privasi, dan kebutuhan sistem", Page.AboutMolina),
    Hit("Lisensi dan atribusi", "Lisensi Molina dan komponen pihak ketiga, teks lengkap bisa dibaca offline", Page.Licenses),
    Hit("Kode sumber", "Alamat repo resmi Molina dan lisensi GPL", Page.Licenses),
    Hit("Rilis Debian", "Versi Debian dan jumlah program yang terpasang", Page.Packages),
    Hit("Pasang paket dasar (Starter)", "Perangkat dasar sekali ketuk: Python, SSH, pengunduh video (yt-dlp), dan sertifikat keamanan, di Paket > Program Debian", Page.Packages),
    Hit("Kelola Debian (Linux di Molina)", "Cadangkan, pulihkan, atau kembalikan Debian ke kondisi awal (rootfs)", Page.Packages),
    Hit("Status Molina", "Ringkasan Debian, paket, yt-dlp, dan cadangan di Sistem > Info", Page.Info),
    Hit("Informasi perangkat", "Versi aplikasi, Android, tipe HP, dan memori (RAM)", Page.Info),
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
        if (showDiag) DiagnosticsDialog(
            onManageDebian = { showDiag = false; showRootfsAdmin = true },
            onViewLog = { showDiag = false; com.molina.studio.log.LogNav.request("cinema"); open(Page.Log) }
        ) { showDiag = false }
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
                        PackageStatusCard( // [BERANDA-KARTU]
                            "Info", "Kondisi Molina, versi aplikasi, info HP, dan pemeriksaan masalah", null,
                            onClick = { open(Page.Info) }, icon = { W7IconInfo() } // [INFO-PAGE]
                        )
                        Spacer(Modifier.height(8.dp))
                        PackageStatusCard(
                            "Pengaturan Terminal", "Atur tampilan dan perilaku Molina Terminal", null,
                            onClick = { open(Page.Terminal) }, icon = { W7IconTerminal() }
                        )
                        Spacer(Modifier.height(8.dp))
                        PackageStatusCard(
                            "Editor Code", "Cara menampilkan pratinjau halaman web saat Anda mengedit kode", null,
                            onClick = { open(Page.Code) }, icon = { W7IconTerminal() }
                        )
                        Spacer(Modifier.height(8.dp))
                        PackageStatusCard(
                            "Keamanan dan privasi", "Atur tautan yang boleh dibuka, akses jaringan rumah, dan layar privat", null,
                            onClick = { open(Page.Network) }, icon = { W7IconShield() }
                        )
                        Spacer(Modifier.height(8.dp))
                        PackageStatusCard(
                            "Penyimpanan", "Lihat pemakaian ruang dan bersihkan file yang tidak perlu", null,
                            onClick = { open(Page.Storage) }, icon = { W7IconToolList() }
                        )

                        Spacer(Modifier.height(16.dp))
                        PackageSectionHeader("Cadangan dan profil") // [SETELAN-PUSAT]
                        PackageStatusCard(
                            "Cadangan dan pemulihan", "Simpan dan pulihkan penanda video, potongan perintah, dan setelan dalam satu file", null,
                            onClick = { showBackup = true }, icon = { W7IconToolList() }
                        )
                        Spacer(Modifier.height(8.dp))
                        AutoBackupRows()
                        Spacer(Modifier.height(8.dp))
                        PackageStatusCard(
                            "Profil setelan", "Simpan dan ganti kumpulan setelan dengan satu ketukan", null,
                            onClick = { showProfiles = true }, icon = { W7IconToolList() }
                        )

                        Spacer(Modifier.height(16.dp))
                        PackageSectionHeader("Linux Debian di Molina")
                        PackageStatusCard(
                            "Kelola Debian (Linux di Molina)", "Cadangkan, pulihkan, atau atur ulang Debian di Paket > Program Debian", null,
                            onClick = { open(Page.Packages) }, icon = { W7IconRootfs() } // [DEBIAN-PUSAT]
                        )
                    }
                    Page.Info -> HubInfoPage( // [INFO-PAGE] rumah informasi diagnostik; gabungan About lama
                        onSystem = { open(Page.System) },
                        onPackages = { lastTile = Page.Packages; open(Page.Packages) },
                        onAutomation = { open(Page.System) }, // [SETELAN-PUSAT] cadangan kini di Sistem
                        onDiagnostics = { showDiag = true },
                        onLog = { open(Page.Log) },
                        onMonitor = { open(Page.Monitor) },
                        onAbout = { open(Page.AboutMolina) },
                        onLicenses = { open(Page.Licenses) }
                    )
                    Page.Terminal -> {
                        PackageStatusCard("Debugging", "Log level, key logging, dan notifikasi crash", null, onClick = { page = Page.Debugging }, icon = { W7IconDebug() }) // [LOKAL-ID]
                        Spacer(Modifier.height(8.dp))
                        PackageStatusCard("Terminal I/O", "Keyboard layar dan akses penyimpanan", null, onClick = { page = Page.TerminalIO }, icon = { W7IconIo() })
                        Spacer(Modifier.height(8.dp))
                        PackageStatusCard("Terminal View", "Penyesuaian margin terminal", null, onClick = { page = Page.TerminalView }, icon = { W7IconTerminal() })
                        Spacer(Modifier.height(8.dp))
                        PackageStatusCard("Tampilan Molina", "Ukuran font dan tema warna prompt", null, onClick = { page = Page.Tampilan }, icon = { W7IconAa() })
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
                        onManageDebian = { showRootfsAdmin = true }, // [DEBIAN-PUSAT]
                        onApt = { apt = it }
                    )
                    Page.Bundle -> BundlePage(onOpenPackages = { open(Page.Packages) })
                    Page.Storage -> StoragePage(onOpenPackages = { open(Page.Packages) })
                    Page.Monitor -> SystemMonitorPage()
                    Page.Licenses -> LicensesPage()
                    Page.AboutMolina -> AboutMolinaPage(onLicenses = { open(Page.Licenses) })
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
            PackageStatusCard(
                h.title, h.summary + "\n" + path, null,
                onClick = { onOpen(h.page) }, subtitleMaxLines = 4, icon = { W7IconHit() }
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------- Debugging ----------

private val LOG_LEVELS = listOf("Off", "Normal", "Debug", "Verbose")

// [LOG-LEVEL-DESKRIPSI] sesuai MolinaLog: e/w/i di Normal, d di Debug, v di Verbose
private val LOG_LEVEL_DESC = listOf(
    "Tidak ada log terminal ke logcat",
    "Error, peringatan, dan info (Default)",
    "Normal ditambah pesan debug",
    "Debug ditambah pesan terperinci (paling banyak)"
)

@Composable
private fun DebuggingPage(onOpenCrash: () -> Unit, onOpenLog: () -> Unit) {
    var showLevel by remember { mutableStateOf(false) }
    PackageSectionHeader("Logging") // [TERMINAL-KARTU]
    PackageStatusCard("Log Level", LOG_LEVELS[TerminalPrefs.logLevel] + " \u2013 " + LOG_LEVEL_DESC[TerminalPrefs.logLevel], null, onClick = { showLevel = true })
    Spacer(Modifier.height(8.dp))
    PackageGroupCard {
        PackageSwitchRow(
            "Key logging tampilan terminal",
            if (TerminalPrefs.keyLogging) "Logcat mencatat tombol yang ditekan di tampilan terminal."
            else "Logcat tidak mencatat tombol di tampilan terminal. (Default)",
            TerminalPrefs.keyLogging
        ) { TerminalPrefs.setKeyLogging(it) }
        PackageDivider()
        PackageSwitchRow(
            "Notifikasi error plugin",
            "Molina belum memiliki plugin; nilai disimpan untuk fase mpv dan Code.",
            TerminalPrefs.pluginErrorNotifications
        ) { TerminalPrefs.setPluginErrorNotifications(it) }
        PackageDivider()
        PackageSwitchRow(
            "Notifikasi laporan crash",
            if (TerminalPrefs.crashNotifications) "Tampilkan notifikasi saat ada laporan crash. (Default)"
            else "Notifikasi laporan crash dimatikan.",
            TerminalPrefs.crashNotifications
        ) { TerminalPrefs.setCrashNotifications(it) }
    }

    Spacer(Modifier.height(16.dp))
    PackageSectionHeader("Molina CLI")
    PackageGroupCard {
        PackageSwitchRow(
            "Akses clipboard dari CLI",
            "Izinkan perintah molina clip membaca dan menulis clipboard. Semua program di Debian bisa memakainya; biarkan mati bila tidak perlu.",
            TerminalPrefs.cliClipboard
        ) { TerminalPrefs.setCliClipboard(it) }
    }

    Spacer(Modifier.height(16.dp))
    PackageSectionHeader("Transkrip Terminal")
    PackageGroupCard {
        PackageSwitchRow(
            "Samarkan token saat menyimpan transkrip",
            if (TerminalPrefs.redactTranscript) "URL hanya menyisakan skema dan host; token=, password=, dan sejenisnya diganti ***."
            else "Transkrip disimpan apa adanya (URL dan token tetap utuh). Folder /sdcard/Molina terbaca aplikasi lain. (Default)",
            TerminalPrefs.redactTranscript
        ) { TerminalPrefs.setRedactTranscript(it) }
    }

    Spacer(Modifier.height(16.dp))
    PackageSectionHeader("Molina Reporter")
    PackageStatusCard("Crash Reporter", "Lihat, salin, atau hapus laporan crash", null, onClick = onOpenCrash)
    Spacer(Modifier.height(8.dp))
    PackageGroupCard {
        PackageSwitchRow(
            "Logcat Reporter",
            "Salurkan logcat aplikasi ke halaman Log (sumber: logcat), digabung oleh LogGrouping.",
            TerminalPrefs.logcatReporter
        ) {
            TerminalPrefs.setLogcatReporter(it)
            LogcatReporter.setEnabled(it)
        }
    }
    Spacer(Modifier.height(8.dp))
    PackageStatusCard("Buka Log", "Tampilkan log terkumpul", null, onClick = onOpenLog)

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
                            Column {
                                Text(name)
                                Text(
                                    LOG_LEVEL_DESC[i], color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
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
        PackageNote("Belum ada laporan crash.")
    }
    files.forEachIndexed { idx, f ->
        if (idx > 0) Spacer(Modifier.height(8.dp))
        PackageStatusCard(CrashReporter.title(f), "${f.length()} byte", null, onClick = { open = f })
    }
    if (files.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
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
    PackageSectionHeader("Keyboard") // [TERMINAL-KARTU]
    PackageGroupCard {
        PackageSwitchRow(
            "Keyboard layar aktif",
            if (TerminalPrefs.softKeyboard) "Keyboard layar akan diaktifkan. (Default)"
            else "Keyboard layar akan dimatikan.",
            TerminalPrefs.softKeyboard
        ) { TerminalPrefs.setSoftKeyboard(it) }
        PackageDivider()
        PackageSwitchRow(
            "Keyboard layar hanya tanpa keyboard fisik",
            if (TerminalPrefs.softKeyboardOnlyIfNoHardware)
                "Keyboard layar hanya aktif bila tidak ada keyboard fisik terhubung."
            else "Keyboard layar tetap aktif walau keyboard fisik terhubung. (Default)",
            TerminalPrefs.softKeyboardOnlyIfNoHardware
        ) { TerminalPrefs.setSoftKeyboardOnlyIfNoHardware(it) }
    }

    Spacer(Modifier.height(16.dp))
    PackageSectionHeader("Penyimpanan")
    PackageGroupCard {
        PackageSwitchRow(
            "Akses penyimpanan penuh",
            if (TerminalPrefs.fullStorageAccess)
                "Seluruh /sdcard terlihat di dalam Debian. Berlaku di sesi baru."
            else "Hanya /sdcard/Molina yang terlihat di dalam Debian. (Default) Berlaku di sesi baru.",
            TerminalPrefs.fullStorageAccess
        ) { TerminalPrefs.setFullStorageAccess(it) }
    }
}

// ---------- Terminal View ----------

@Composable
private fun TerminalViewPage() {
    PackageSectionHeader("View") // [TERMINAL-KARTU]
    PackageGroupCard {
        PackageSwitchRow(
            "Penyesuaian margin terminal",
            if (TerminalPrefs.marginAdjustment) "Penyesuaian margin terminal akan diaktifkan. (Default)"
            else "Penyesuaian margin terminal akan dimatikan.",
            TerminalPrefs.marginAdjustment
        ) { TerminalPrefs.setMarginAdjustment(it) }
    }
}

// ---------- Tampilan Molina ----------

@Composable
private fun TampilanPage() {
    val ctx = LocalContext.current
    val theme = TerminalPrefs.promptTheme
    val sp = TerminalPrefs.fontSp

    PackageSectionHeader("Ukuran font") // [TERMINAL-KARTU]
    PackageGroupCard {
        PackageSliderRow(
            title = "Ukuran",
            valueText = "${sp}sp  (${TerminalPrefs.MIN_FONT_SP}\u2013${TerminalPrefs.MAX_FONT_SP}sp)",
            value = sp.toFloat(),
            onValueChange = { TerminalPrefs.setFontSp(it.roundToInt()) },
            valueRange = TerminalPrefs.MIN_FONT_SP.toFloat()..TerminalPrefs.MAX_FONT_SP.toFloat(),
            steps = TerminalPrefs.MAX_FONT_SP - TerminalPrefs.MIN_FONT_SP - 1,
            footer = {
                Text(
                    "user@localhost:~ $", fontFamily = FontFamily.Monospace, fontSize = sp.sp,
                    fontWeight = FontWeight.Bold, color = theme.homeColor
                )
            }
        )
    }
    PackageNote("Di terminal, cubit dua jari (pinch) juga mengubah ukuran ini, per 1sp.")

    Spacer(Modifier.height(16.dp))
    PackageSectionHeader("Skema warna terminal")
    PackageGroupCard {
        TerminalLook.entries.forEachIndexed { i, l ->
            if (i > 0) PackageDivider()
            PackageChoiceRow(
                title = l.label,
                selected = l == TerminalPrefs.look,
                onSelect = { TerminalPrefs.setLook(l) },
                preview = {
                    Row {
                        Text(
                            "user@localhost:~ ", fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = androidx.compose.ui.graphics.Color(l.fg)
                        )
                        Text(
                            "\u2588", fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = androidx.compose.ui.graphics.Color(l.cursor)
                        )
                    }
                }
            )
        }
    }
    PackageNote("Skema warna berlaku langsung pada sesi yang terbuka.")

    Spacer(Modifier.height(16.dp))
    PackageSectionHeader("Banner sesi")
    PackageGroupCard {
        BannerStyle.entries.forEachIndexed { i, b ->
            if (i > 0) PackageDivider()
            PackageChoiceRow(
                title = b.label,
                selected = b == TerminalPrefs.bannerStyle,
                onSelect = { TerminalPrefs.setBannerStyle(b) }
            )
        }
    }
    PackageNote("Tampil di sesi baru dan saat tombol CLEAR. Mati = sesi langsung ke prompt.")

    Spacer(Modifier.height(16.dp))
    PackageSectionHeader("Tema warna prompt")
    PackageGroupCard {
        PromptTheme.entries.forEachIndexed { i, pt ->
            if (i > 0) PackageDivider()
            PackageChoiceRow(
                title = pt.label,
                selected = pt == theme,
                onSelect = { TerminalPrefs.setPromptTheme(ctx, pt) },
                preview = {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "user@localhost:~ $", fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, color = pt.homeColor
                        )
                        Text(
                            "user@localhost:/etc $", fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, color = pt.otherColor
                        )
                    }
                }
            )
        }
    }
    PackageNote("Warna baru tampil pada prompt berikutnya (tekan Enter di terminal).")
}

// ---------- About: lihat AboutUi.kt ----------

// ---------- Komponen bersama ----------

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
