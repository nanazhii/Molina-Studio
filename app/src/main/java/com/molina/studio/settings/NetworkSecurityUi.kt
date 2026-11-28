package com.molina.studio.settings

import com.molina.studio.ui.components.PackageDivider
import com.molina.studio.ui.components.PackageGroupCard
import com.molina.studio.ui.components.PackageInfoRow
import com.molina.studio.ui.components.PackageSectionHeader
import com.molina.studio.ui.components.PackageStatusCard
import com.molina.studio.ui.components.PackageSwitchRow
import com.molina.studio.ui.components.ShieldBadge // [SHIELD-BADGE]
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.molina.core.net.ShieldStats
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
    val blockInsecure by StreamEnginePrefs.blockInsecureStreams.collectAsState() // [IPTV-TRUST-BLOCK]
    val blocked by ShieldStats.blockedCount.collectAsState()
    val trackerBlocked by ShieldStats.trackerCount.collectAsState()
    val recent = remember(blocked) { ShieldStats.recent().takeLast(8).reversed() }
    // [SHIELD-CARD] kartu ringkasan perlindungan di puncak halaman
    val ctx = LocalContext.current
    val sig = remember { AppSignature.read(ctx) }
    val summaryInput = ShieldSummary.Input(
        shield = shieldOn, trackers = trackersOn, lanAllowed = lan, secureScreen = secure,
        blockPlaylistRtmp = blockPl, debuggable = sig.debuggable,
        blocked = blocked.toInt(), fingerprint = sig.sha256, trackersBlocked = trackerBlocked.toInt(),
    )
    val sum = ShieldSummary.build(summaryInput)
    PackageSectionHeader("Ringkasan perlindungan") // [KEAMANAN-KARTU]
    PackageStatusCard(
        sum.title, ShieldSummary.counts(summaryInput), null, subtitleMaxLines = 2,
        icon = { ShieldBadge() }, // [SHIELD-BADGE]
        onInfo = { // [INFO-SCREEN] [SHIELD-REPORT]
            SettingHelp.show(
                HelpTopic(
                    "Ringkasan perlindungan",
                    sum.body.substringBeforeLast("\nKetuk untuk"),
                    icon = { ShieldBadge() },
                    actionLabel = "SALIN LAPORAN",
                    onAction = {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Molina Shield", sum.report))
                        android.widget.Toast.makeText(ctx, "Laporan perlindungan disalin", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
            )
        }
    )
    Spacer(Modifier.height(16.dp))
    PackageSectionHeader("Molina Shield")
    PackageGroupCard {
        PackageSwitchRow(
            "Aktifkan Molina Shield",
            "Filter lokal untuk lalu lintas http/https pemutar.",
            shieldOn
        ) { ShieldPrefs.setEnabled(it) }
        PackageDivider()
        PackageSwitchRow(
            "Blokir pelacak dan iklan",
            "Menolak domain iklan, analitik, dan telemetri yang dipanggil oleh stream atau playlist.",
            trackersOn
        ) { ShieldPrefs.setBlockTrackers(it) }
        PackageDivider()
        PackageSwitchRow(
            "Hapus header Referer",
            "Mencegah server mengetahui halaman asal. Matikan bila channel IPTV tertentu menolak diputar.",
            refererOff
        ) { ShieldPrefs.setStripReferer(it) }
        PackageDivider()
        PackageSwitchRow(
            "Blokir rtmp di dalam playlist",
            "Playlist tidak boleh menyuruh pemutar membuka rtmp.",
            blockPl
        ) { StreamEnginePrefs.setBlockPlaylistStreams(it) } // [PL-STREAM-GUARD]
        PackageDivider()
        PackageSwitchRow(
            "Blokir stream tidak aman di IPTV",
            "Sembunyikan saluran IPTV yang ditandai TIDAK AMAN.",
            blockInsecure
        ) { StreamEnginePrefs.setBlockInsecureStreams(it) } // [IPTV-TRUST-BLOCK]
        PackageDivider()
        PackageInfoRow(
            "Diblokir sesi ini: $blocked",
            if (recent.isEmpty()) "Belum ada permintaan yang diblokir. Hanya host dan alasan yang dicatat, tidak pernah URL lengkap."
            else recent.joinToString("\n") {
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it.atMs)) +
                    "  " + it.host + " - " + it.reason
            }
        )
    }
    Spacer(Modifier.height(16.dp))
    PackageSectionHeader("Kebijakan URL")
    PackageGroupCard {
        PackageInfoRow(
            "Daftar putih skema",
            "Hanya skema tertentu yang diizinkan."
        )
        PackageDivider()
        PackageSwitchRow(
            "Izinkan jaringan lokal (LAN)",
            "Untuk NAS, Jellyfin, dan DLNA. Default mati.",
            lan
        ) { StreamEnginePrefs.enableLan(it) }
    }
    Spacer(Modifier.height(16.dp))
    PackageSectionHeader("Privasi layar")
    PackageGroupCard {
        PackageSwitchRow(
            "Layar privat (Terminal dan Pengaturan)",
            "Sembunyikan isi layar dari screenshot dan perekam.",
            secure
        ) { PrivacyPrefs.setSecureScreen(it) }
    }
    Spacer(Modifier.height(16.dp))
    PackageSectionHeader("Catatan")
    PackageGroupCard {
        PackageInfoRow(
            "Tautan http dan rtmp tidak terenkripsi",
            "Isinya bisa dibaca atau diubah siapa pun di jaringan yang sama. Gunakan https bila tersedia."
        )
    }
}
