package com.molina.studio.settings

import com.molina.studio.ui.MolinaTheme
import com.molina.studio.ui.components.PackageDivider
import com.molina.studio.ui.components.PackageGroupCard
import com.molina.studio.ui.components.PackageInfoRow
import com.molina.studio.ui.components.PackageSectionHeader
import com.molina.studio.ui.components.PackageStatusCard
import com.molina.studio.ui.components.PackageSwitchRow
import com.molina.studio.ui.components.PackageChoiceRow
import com.molina.studio.ui.components.PackageNote
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Column
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
import com.molina.core.system.MolinaDir
import com.molina.studio.AppPaths
import com.molina.studio.cinema.CinemaActions
import com.molina.cinema.PerfTier
import com.molina.cinema.PerfTierPolicy
import com.molina.cinema.SpectrumSwitch
import com.molina.studio.cinema.AudioOnlyBridge
import com.molina.studio.cinema.HwdecBridge
import com.molina.studio.cinema.IptvSources
import com.molina.studio.cinema.LanguageBridge
import com.molina.studio.cinema.PlaybackBridge
import com.molina.studio.cinema.ResumeBridge
import com.molina.studio.cinema.SearchThumbPrefs
import com.molina.studio.cinema.TvMode
import com.molina.studio.stream.StreamEnginePrefs
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.geometry.Rect

/** Ikon ubin Pemutar di beranda Settings: panel Aero biru dengan segitiga putar. */
@Composable
internal fun W7IconPlayerBig(box: Dp) {
    Canvas(Modifier.size(box)) {
        val w = size.width
        val h = size.height
        drawRoundRect(Color(0xFF444444), Offset(w * 0.10f, h * 0.06f), Size(w * 0.80f, h * 0.88f), CornerRadius(w * 0.08f))
        drawRoundRect(
            Brush.verticalGradient(listOf(MolinaTheme.AeroBlueTop, MolinaTheme.AeroBlueBottom)),
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class) // [T25]
@Composable
internal fun PlayerSettingsPage(onOpenCinema: () -> Unit) {
    val ctx = LocalContext.current
    var resume by remember { mutableStateOf(ResumeBridge.enabled(ctx)) }
    var hw by remember { mutableStateOf(HwdecBridge.enabled(ctx)) }
    var audioOnly by remember { mutableStateOf(AudioOnlyBridge.enabled(ctx)) } // [PREF-AUDIO-UI]
    var langAudio by remember { mutableStateOf(LanguageBridge.audio(ctx)) } // [PREF-LANG]
    var langSub by remember { mutableStateOf(LanguageBridge.subtitle(ctx)) }
    var editLang by remember { mutableStateOf<Int?>(null) } // 0 = suara, 1 = subtitle
    var bgMode by remember { mutableStateOf(PlaybackBridge.backgroundPlay(ctx)) } // [PREF-CENTRAL]
    var bgVideoOff by remember { mutableStateOf(PlaybackBridge.bgVideoOff(ctx)) }
    var newIntent by remember { mutableStateOf(PlaybackBridge.newIntentReplace(ctx)) }
    var perfMode by remember { mutableStateOf(PerfTierPolicy.mode(ctx)) } // [PERF-MODE]
    var spectrum by remember(perfMode) { mutableStateOf(SpectrumSwitch.enabled(ctx)) } // [PREF-SPECTRUM] [SPECTRUM-HEMAT] ikut mode Performa selama belum diatur sendiri
    var tvMode by remember { mutableStateOf(TvMode.enabled(ctx)) }
    var thumbs by remember(perfMode) { mutableStateOf(SearchThumbPrefs.enabled(ctx)) } // [RESULTS-THUMB-TOGGLE] [PERF-SYNC] ikut mode Performa selama belum diatur sendiri
    var sources by remember { mutableStateOf(IptvSources.load(ctx).filter { !IptvSources.isBundled(it.url) }) } // [IPTV-BUNDLED] hanya sumber buatan pengguna
    var removing by remember { mutableStateOf<IptvSources.Source?>(null) }
    val iptvPath = remember { runCatching { AppPaths.get(ctx).dir(MolinaDir.Iptv).path }.getOrDefault("") }

    // [PEMUTAR-KARTU] state yang dibaca Flow diangkat ke atas supaya isi kartu hanya berisi baris
    val lockId by StreamEnginePrefs.lockIndonesian.collectAsState()
    val origTitle by StreamEnginePrefs.originalTitles.collectAsState()
    // [SETELAN-PUSAT] pindah dari halaman Cadangan dan otomasi
    remember { com.molina.studio.automation.AutoPrefs.init(ctx) }
    val autoSt by com.molina.studio.automation.AutoPrefs.state.collectAsState()

    // [T25] Dari dialog chip Hemat di pemutar: gulir agar bagian Performa (header + 4 pilihan) terlihat.
    val perfRequester = remember { BringIntoViewRequester() }
    androidx.compose.runtime.LaunchedEffect(SettingsNav.scrollToPerf) {
        if (SettingsNav.scrollToPerf) {
            androidx.compose.runtime.withFrameNanos { } // tunggu tata letak pertama selesai
            perfRequester.bringIntoView(Rect(0f, 0f, 1f, 420f * ctx.resources.displayMetrics.density))
            SettingsNav.scrollToPerf = false
        }
    }

    PackageSectionHeader("Pemutaran")
    PackageGroupCard {
        PackageSwitchRow("Ingat posisi putar", "Lanjutkan video dari posisi terakhir saat dibuka lagi.", resume) { // [INFO-CENTRAL]
            resume = it
            ResumeBridge.setEnabled(ctx, it)
        }
        PackageDivider()
        PackageSwitchRow(
            "Dekoder perangkat keras",
            "MediaCodec. Matikan bila video hijau atau patah-patah. Berlaku pada video berikutnya yang dibuka.",
            hw
        ) {
            hw = it
            HwdecBridge.setEnabled(ctx, it)
        }
        PackageDivider()
        PackageSwitchRow( // [PREF-AUDIO-UI]
            "Audio saja untuk video",
            "Putar suaranya saja, tanpa gambar.",
            audioOnly
        ) {
            audioOnly = it
            AudioOnlyBridge.setEnabled(ctx, it)
        }
        PackageDivider()
        PackageSwitchRow( // [PREF-SPECTRUM]
            "Visualizer spektrum",
            "Batang spektrum mengikuti musik.",
            spectrum
        ) {
            spectrum = it
            SpectrumSwitch.setEnabled(ctx, it)
        }
    }

    Spacer(Modifier.height(16.dp))
    PackageSectionHeader("Bahasa") // [PREF-LANG-GROUP] [PREF-LANG] [AUDIO-LANG]
    PackageStatusCard("Suara file lokal", langAudio.ifEmpty { "Ikuti pilihan bawaan file" }, null, onClick = { editLang = 0 }) // [INFO-CENTRAL]
    Spacer(Modifier.height(8.dp))
    PackageStatusCard("Subtitle file lokal", langSub.ifEmpty { "Ikuti pilihan bawaan file" }, null, onClick = { editLang = 1 })
    Spacer(Modifier.height(8.dp))
    val audioLang by StreamEnginePrefs.audioLang.collectAsState()
    PackageGroupCard {
        PackageInfoRow("Suara YouTube", "Bahasa trek audio untuk video YouTube. Tombol bahasa di pemutar memakai setelan yang sama.") // [INFO-CENTRAL]
        PackageDivider()
        val base = listOf(
            "" to "Asli (bawaan video)", "id" to "Indonesia", "en" to "Inggris",
            "ja" to "Jepang", "ko" to "Korea", "ar" to "Arab", "es" to "Spanyol", "hi" to "Hindi",
        )
        val rows = if (audioLang.isEmpty() || base.any { it.first == audioLang }) base else base + (audioLang to
            (java.util.Locale.forLanguageTag(audioLang).getDisplayLanguage(java.util.Locale.forLanguageTag("id"))
                .ifBlank { audioLang }.replaceFirstChar { it.uppercase() } + " (bahasa sistem)"))
        rows.forEachIndexed { i, (code, label) ->
            if (i > 0) PackageDivider()
            PackageChoiceRow(
                label, audioLang == code, { StreamEnginePrefs.setAudioLang(code) },
                if (code.isEmpty()) "Ikuti trek audio asli video (sering berbahasa Inggris). Mati = perilaku lama." else null
            )
        }
    }
    PackageNote("Berlaku pada video berikutnya yang dibuka. Suara dan subtitle file lokal dipilih otomatis bila file punya beberapa trek. " +
        "Suara YouTube: awalnya Indonesia; bila bahasa pilihan tidak ada, dipakai trek asli. Bahasa selain asli diputar sebagai audio terpisah; resolusi Otomatis maks 720p, tanpa batas di Wi-Fi.")

    editLang?.let { which ->
        LanguageDialog(
            title = if (which == 0) "Suara file lokal" else "Subtitle file lokal",
            message = "Isi dengan kode bahasa 2 atau 3 huruf, misalnya id (Indonesia), en (Inggris), ja (Jepang). " +
                "Boleh lebih dari satu, pisahkan dengan koma, misalnya id,en. Kosongkan untuk mengikuti bawaan file.",
            initial = if (which == 0) langAudio else langSub,
            onSave = { v ->
                val n = LanguageBridge.normalize(v)
                if (which == 0) { langAudio = n; LanguageBridge.setAudio(ctx, n) } else { langSub = n; LanguageBridge.setSubtitle(ctx, n) }
                editLang = null
            },
            onDismiss = { editLang = null }
        )
    }

    Spacer(Modifier.height(16.dp))
    PackageSectionHeader("Pemutaran di latar") // [PREF-CENTRAL]
    PackageGroupCard {
        PackageInfoRow( // [INFO-CENTRAL]
            "Mode pemutaran di latar", "Apa yang tetap terdengar setelah Anda keluar dari aplikasi."
        )
        PackageDivider()
        listOf(
            Triple(PlaybackBridge.MODE_NEVER, "Tidak pernah", "Pemutaran berhenti saat Anda keluar dari aplikasi."),
            Triple(PlaybackBridge.MODE_AUDIO_ONLY, "Hanya file audio", "Suara file audio tetap terdengar setelah Anda keluar dari aplikasi."),
            Triple(PlaybackBridge.MODE_ALWAYS, "Selalu (video juga)", "Suara video juga tetap terdengar setelah Anda keluar dari aplikasi."),
        ).forEachIndexed { i, (mode, label, sub) ->
            if (i > 0) PackageDivider()
            PackageChoiceRow(label, bgMode == mode, { bgMode = mode; PlaybackBridge.setBackgroundPlay(ctx, mode) }, sub)
        }
        PackageDivider()
        PackageSwitchRow(
            "Hemat data di latar",
            "Di latar, putar suaranya saja.",
            bgVideoOff
        ) {
            bgVideoOff = it
            PlaybackBridge.setBgVideoOff(ctx, it)
        }
        PackageDivider()
        PackageSwitchRow(
            "Putar file baru langsung",
            "Buka file baru saat pemutar di latar.",
            newIntent
        ) {
            newIntent = it
            PlaybackBridge.setNewIntentReplace(ctx, it)
        }
    }
    PackageNote("Berlaku saat pemutar dibuka berikutnya.")

    Spacer(Modifier.height(16.dp))
    Box(Modifier.bringIntoViewRequester(perfRequester)) { PackageSectionHeader("Performa") } // [PERF-MODE] [T25]
    PackageGroupCard {
        PackageInfoRow( // [INFO-CENTRAL]
            "Mode performa", "Menyesuaikan cache, efek visual, dan thread dekoder dengan perangkat."
        )
        PackageDivider()
        val detected = remember { PerfTierPolicy.detected(ctx) }
        val detLabel = when (detected) { PerfTier.LOW -> "Hemat"; PerfTier.MID -> "Seimbang"; PerfTier.HIGH -> "Maksimal" }
        val rows = listOf(
            Triple("auto", "Otomatis", "Disesuaikan dengan RAM perangkat. Terdeteksi: $detLabel."),
            Triple("low", "Hemat", "Untuk RAM kecil: cache kecil, efek visual dimatikan, stream YouTube maks ${PerfTierPolicy.LOW_HEIGHT_CAP}p; sumber lain tetap kualitas asli."), // [PERF-480] [PERF-SCOPE]
            Triple("balanced", "Seimbang", "Cache sedang dan thread dekoder dibatasi."),
            Triple("high", "Maksimal", "Tanpa pembatasan. Cocok untuk perangkat kelas atas."),
        )
        rows.forEachIndexed { i, (key, label, sub) ->
            if (i > 0) PackageDivider()
            PackageChoiceRow(label, perfMode == key, { perfMode = key; PerfTierPolicy.setMode(ctx, key) }, sub)
        }
    }
    PackageNote("Berlaku pada video berikutnya yang dibuka. Mode Hemat membuat video lebih lancar dan hemat memori di perangkat lawas. " +
        "Untuk lebih hemat lagi, aktifkan \"Audio saja untuk video\" di bagian Pemutaran.")

    Spacer(Modifier.height(16.dp))
    PackageSectionHeader("Pencarian YouTube")
    PackageGroupCard {
        PackageSwitchRow(
            "Kunci hasil ke bahasa Indonesia",
            "Utamakan judul berbahasa Indonesia.",
            lockId
        ) { StreamEnginePrefs.setLockIndonesian(it) }
        PackageDivider()
        PackageSwitchRow(
            "Judul asli YouTube",
            "Judul persis dari pengunggah.",
            origTitle
        ) { StreamEnginePrefs.setOriginalTitles(it) }
        PackageDivider()
        PackageSwitchRow(
            "Tampilkan thumbnail",
            "Gambar kecil di hasil pencarian.",
            thumbs
        ) {
            thumbs = it
            SearchThumbPrefs.setEnabled(ctx, it)
        }
    }

    Spacer(Modifier.height(16.dp))
    PackageSectionHeader("Beranda Cinema")
    PackageGroupCard {
        PackageSwitchRow(
            "Mode TV",
            "Tambah tile Mode TV di Beranda: jelajah berkas ala saluran TV dengan kartu poster dan panel info.",
            tvMode
        ) {
            tvMode = it
            TvMode.setEnabled(ctx, it)
        }
        PackageDivider()
        PackageSwitchRow(
            "Unduhan masuk ke Cinema",
            "Unduhan selesai muncul di Terakhir diputar.",
            autoSt.addToCinema
        ) { com.molina.studio.automation.AutoPrefs.setAddToCinema(ctx, it) }
    }

    Spacer(Modifier.height(16.dp))
    PackageSectionHeader("Sumber IPTV")
    if (sources.isEmpty()) {
        PackageGroupCard {
            PackageInfoRow("Belum ada sumber playlist", "Tambahkan dari dialog Saluran di Molina Cinema.")
        }
    } else {
        sources.forEachIndexed { idx, s ->
            if (idx > 0) Spacer(Modifier.height(8.dp))
            PackageStatusCard(s.name, "Ketuk untuk menghapus dari daftar", null, onClick = { removing = s })
        }
    }
    Spacer(Modifier.height(8.dp))
    PackageGroupCard {
        PackageInfoRow("Folder IPTV", "$iptvPath\nSalin berkas .m3u atau .m3u8 ke folder ini.")
    }

    Spacer(Modifier.height(16.dp))
    PackageSectionHeader("Pemutar")
    PackageStatusCard(
        "Setelan pemutar lengkap", "Video, antarmuka, gestur, dan lanjutan", null,
        onClick = {
            if (!CinemaActions.openSettings(ctx)) {
                Toast.makeText(ctx, "Setelan pemutar tidak bisa dibuka", Toast.LENGTH_SHORT).show()
            }
        }
    )
    Spacer(Modifier.height(8.dp))
    PackageStatusCard("Buka Molina Cinema", "Pindah ke tab Cinema", null, onClick = onOpenCinema)

    removing?.let { s ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Hapus sumber IPTV?") },
            text = { Text(s.name) },
            confirmButton = {
                TextButton(onClick = {
                    IptvSources.remove(ctx, s.url)
                    sources = IptvSources.load(ctx).filter { !IptvSources.isBundled(it.url) } // [IPTV-BUNDLED]
                    removing = null
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Batal") } }
        )
    }
}

/** [PREF-LANG] Dialog isian kode bahasa (suara/subtitle utama). */
@Composable
private fun LanguageDialog(title: String, message: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Simpan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}
