package com.molina.studio.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.molina.core.common.ShieldStats
import com.molina.studio.security.PrivacyPrefs
import com.molina.studio.security.ShieldPrefs
import com.molina.studio.stream.StreamEnginePrefs

/** Halaman "Keamanan jaringan": kebijakan URL untuk mpv dan yt-dlp. Jaringan dianggap tidak tepercaya. */
@Composable
internal fun NetworkSecurityPage() {
    val lan by StreamEnginePrefs.allowLan.collectAsState()
    val secure by PrivacyPrefs.secureScreen.collectAsState()
    val shieldOn by ShieldPrefs.enabled.collectAsState()
    val trackersOn by ShieldPrefs.blockTrackers.collectAsState()
    val refererOff by ShieldPrefs.stripReferer.collectAsState()
    val blockPl by StreamEnginePrefs.blockPlaylistStreams.collectAsState()
    val blocked by ShieldStats.blockedCount.collectAsState()
    val recent = remember(blocked) { ShieldStats.recent().takeLast(8).reversed() }
    // [SHIELD-CARD] kartu ringkasan perlindungan di puncak halaman
    val ctx = LocalContext.current
    val sig = remember { AppSignature.read(ctx) }
    val sum = ShieldSummary.build(
        ShieldSummary.Input(
            shield = shieldOn, trackers = trackersOn, lanAllowed = lan, secureScreen = secure,
            blockPlaylistRtmp = blockPl, debuggable = sig.debuggable,
            blocked = blocked.toInt(), fingerprint = sig.sha256,
        )
    )
    Category("Ringkasan perlindungan")
    SettingsItem(sum.title, sum.body) { // [SHIELD-REPORT]
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Molina Shield", sum.report))
        android.widget.Toast.makeText(ctx, "Laporan perlindungan disalin", android.widget.Toast.LENGTH_SHORT).show()
    }
    Category("Molina Shield")
    SwitchRow(
        "Aktifkan Molina Shield",
        "Semua lalu lintas http/https pemutar lewat filter lokal: redirect, segmen HLS, dan playlist ke alamat " +
            "privat atau loopback diblokir, termasuk DNS yang diarahkan ke alamat privat. Perubahan berlaku penuh " +
            "setelah Refresh Pemutar atau membuka ulang pemutar. Tidak mencakup rtmp/rtsp. Unduhan yt-dlp hanya diperiksa pada URL awal (redirect tidak tercakup).",
        shieldOn
    ) { ShieldPrefs.setEnabled(it) }
    SwitchRow(
        "Blokir pelacak dan iklan",
        "Menolak domain iklan, analitik, dan telemetri yang dipanggil oleh stream atau playlist.",
        trackersOn
    ) { ShieldPrefs.setBlockTrackers(it) }
    SwitchRow(
        "Hapus header Referer",
        "Mencegah server mengetahui halaman asal. Matikan bila channel IPTV tertentu menolak diputar.",
        refererOff
    ) { ShieldPrefs.setStripReferer(it) }
    SwitchRow(
        "Blokir rtmp di dalam playlist",
        "Playlist (m3u) tidak bisa menyuruh pemutar membuka rtmp ke alamat lain. Tautan rtmp yang Anda buka " +
            "langsung tetap jalan. Kanal rtmp di dalam daftar IPTV akan gagal diputar. rtsp belum tercakup. " +
            "Berlaku setelah Refresh Pemutar atau membuka ulang pemutar.",
        blockPl
    ) { StreamEnginePrefs.setBlockPlaylistStreams(it) } // [PL-STREAM-GUARD]
    SettingsItem(
        "Diblokir sesi ini: $blocked",
        if (recent.isEmpty()) "Belum ada permintaan yang diblokir. Hanya host dan alasan yang dicatat, tidak pernah URL lengkap."
        else recent.joinToString("\n") {
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it.atMs)) +
                "  " + it.host + " - " + it.reason
        }
    ) {}
    Category("Kebijakan URL")
    SettingsItem(
        "Daftar putih skema",
        "Hanya http, https, rtmp(s), rtsp, dan berkas lokal. concat:, edl://, fd://, data: dan sejenisnya " +
            "selalu ditolak, begitu pula alamat perangkat sendiri (127.x) dan metadata cloud (169.254.x)."
    ) {}
    SwitchRow(
        "Izinkan jaringan lokal (LAN)",
        "Untuk NAS, Jellyfin, DLNA (192.168.x.x, 10.x.x.x, nama .local). Default mati supaya tautan " +
            "jahat tidak bisa memakai aplikasi untuk memindai jaringan rumah Anda.",
        lan
    ) { StreamEnginePrefs.enableLan(it) }
    Category("Privasi layar")
    SwitchRow(
        "Layar privat (Terminal dan Pengaturan)",
        "Menyembunyikan isi layar dari tangkapan layar, perekam layar, dan daftar aplikasi terbaru, " +
            "serta menolak sentuhan saat tertutup overlay aplikasi lain. Matikan bila Anda perlu " +
            "screenshot atau memakai aplikasi filter cahaya biru. Pemutar video tidak terpengaruh.",
        secure
    ) { PrivacyPrefs.setSecureScreen(it) }
    Category("Catatan")
    SettingsItem(
        "Tautan http dan rtmp tidak terenkripsi",
        "Isinya bisa dibaca atau diubah siapa pun di jaringan yang sama. Gunakan https bila tersedia."
    ) {}
}
