package com.molina.studio.settings

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.molina.core.common.MolinaDir
import com.molina.studio.AppPaths
import com.molina.studio.cinema.CinemaActions
import com.molina.studio.cinema.HwdecBridge
import com.molina.studio.cinema.IptvSources
import com.molina.studio.cinema.ResumeBridge
import com.molina.studio.cinema.TvMode
import com.molina.studio.stream.StreamEnginePrefs

/** Ikon ubin Pemutar di beranda Settings: panel Aero biru dengan segitiga putar. */
@Composable
internal fun W7IconPlayerBig(box: Dp) {
    Canvas(Modifier.size(box)) {
        val w = size.width
        val h = size.height
        drawRoundRect(Color(0xFF444444), Offset(w * 0.10f, h * 0.06f), Size(w * 0.80f, h * 0.88f), CornerRadius(w * 0.08f))
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFF8FC4EE), Color(0xFF2D6DB5))),
            Offset(w * 0.15f, h * 0.11f), Size(w * 0.70f, h * 0.78f), CornerRadius(w * 0.05f)
        )
        val tri = Path().apply {
            moveTo(w * 0.40f, h * 0.30f)
            lineTo(w * 0.40f, h * 0.70f)
            lineTo(w * 0.72f, h * 0.50f)
            close()
        }
        drawPath(tri, Color.White)
    }
}

/** Halaman Pemutar: pemutaran (resume, dekoder), sumber IPTV, dan pintasan ke setelan lengkap pemutar. */
@Composable
internal fun PlayerSettingsPage(onOpenCinema: () -> Unit) {
    val ctx = LocalContext.current
    var resume by remember { mutableStateOf(ResumeBridge.enabled(ctx)) }
    var hw by remember { mutableStateOf(HwdecBridge.enabled(ctx)) }
    var tvMode by remember { mutableStateOf(TvMode.enabled(ctx)) }
    var sources by remember { mutableStateOf(IptvSources.load(ctx)) }
    var removing by remember { mutableStateOf<IptvSources.Source?>(null) }
    val iptvPath = remember { runCatching { AppPaths.get(ctx).dir(MolinaDir.Iptv).path }.getOrDefault("") }

    Category("Pemutaran")
    SwitchRow("Ingat posisi putar", "Lanjutkan video dari posisi terakhir saat dibuka lagi.", resume) {
        resume = it
        ResumeBridge.setEnabled(ctx, it)
    }
    SwitchRow(
        "Dekoder perangkat keras",
        "MediaCodec. Matikan bila video hijau atau patah-patah. Berlaku pada video berikutnya yang dibuka.",
        hw
    ) {
        hw = it
        HwdecBridge.setEnabled(ctx, it)
    }

    Category("Pencarian YouTube")
    val lockId by StreamEnginePrefs.lockIndonesian.collectAsState()
    SwitchRow(
        "Kunci hasil ke bahasa Indonesia",
        "Utamakan judul berbahasa Indonesia dan sembunyikan judul yang jelas berbahasa Inggris/asing. "
            + "Bila hasilnya terlalu sedikit, semuanya tetap ditampilkan.",
        lockId
    ) { StreamEnginePrefs.setLockIndonesian(it) }
    val origTitle by StreamEnginePrefs.originalTitles.collectAsState()
    SwitchRow(
        "Judul asli YouTube",
        "Pakai judul persis seperti yang ditulis pengunggah, bukan terjemahan otomatis YouTube. "
            + "Pencarian jadi beberapa detik lebih lambat karena judul tiap hasil diambil ulang.",
        origTitle
    ) { StreamEnginePrefs.setOriginalTitles(it) }

    Category("Beranda Cinema")
    SwitchRow(
        "Mode TV",
        "Tambah tile Mode TV di Beranda: jelajah berkas ala saluran TV dengan kartu poster dan panel info.",
        tvMode
    ) {
        tvMode = it
        TvMode.setEnabled(ctx, it)
    }
    // [SETELAN-PUSAT] pindah dari halaman Cadangan dan otomasi
    remember { com.molina.studio.automation.AutoPrefs.init(ctx) }
    val autoSt by com.molina.studio.automation.AutoPrefs.state.collectAsState()
    SwitchRow(
        "Unduhan masuk ke Cinema",
        "Setelah unduhan selesai, video atau audio yang berhasil otomatis muncul di Terakhir diputar Molina Cinema " +
            "agar bisa diputar dengan satu ketukan.",
        autoSt.addToCinema
    ) { com.molina.studio.automation.AutoPrefs.setAddToCinema(ctx, it) }

    Category("Sumber IPTV")
    if (sources.isEmpty()) {
        SettingsItem("Belum ada sumber playlist", "Tambahkan dari dialog Saluran di Molina Cinema.") {}
    } else {
        sources.forEach { s -> SettingsItem(s.name, "Ketuk untuk menghapus dari daftar") { removing = s } }
    }
    SettingsItem("Folder IPTV", "$iptvPath\nSalin berkas .m3u atau .m3u8 ke folder ini.") {}

    Category("Pemutar")
    SettingsItem("Setelan pemutar lengkap", "Video, antarmuka, gestur, dan lanjutan") {
        if (!CinemaActions.openSettings(ctx)) {
            Toast.makeText(ctx, "Setelan pemutar tidak bisa dibuka", Toast.LENGTH_SHORT).show()
        }
    }
    SettingsItem("Buka Molina Cinema", "Pindah ke tab Cinema", onOpenCinema)

    removing?.let { s ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Hapus sumber IPTV?") },
            text = { Text(s.name) },
            confirmButton = {
                TextButton(onClick = {
                    IptvSources.remove(ctx, s.url)
                    sources = IptvSources.load(ctx)
                    removing = null
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Batal") } }
        )
    }
}
