package com.molina.studio.settings

import com.molina.studio.ui.components.PackageDivider
import com.molina.studio.ui.components.PackageGroupCard
import com.molina.studio.ui.components.PackageInfoRow
import com.molina.studio.ui.components.PackageSectionHeader
import com.molina.studio.ui.components.PackageSwitchRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.molina.studio.code.CodeInbox
import com.molina.studio.code.CodePrefs
import com.molina.studio.code.RunCheckPrefs
import com.molina.studio.ui.components.PackageChoiceRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/** Halaman "Editor Code": perilaku pratinjau (tombol Play) di editor kode. */
@Composable
internal fun CodeSettingsPage() {
    val webView by CodePrefs.htmlWebView.collectAsState()
    PackageSectionHeader("Pratinjau HTML") // [CODE-KARTU]
    PackageGroupCard {
        PackageInfoRow(
            "Renderer native (default)",
            "Judul, paragraf, daftar, tabel, kutipan, blok kode, dan tautan digambar langsung oleh aplikasi. " +
                "CSS, JavaScript, dan gambar tidak diproses. Ringan dan tidak memuat apa pun dari jaringan."
        )
        PackageDivider()
        PackageSwitchRow(
            "Pakai WebView untuk HTML",
            "Menampilkan halaman HTML dengan tampilan penuh (CSS inline dan gambar data:). JavaScript, jaringan, " +
                "dan akses berkas tetap dimatikan, dan tautan dibuka di peramban. Cocok bila renderer native " +
                "terlalu sederhana. Berlaku untuk berkas .html berikutnya yang dibuka dengan Play.",
            webView
        ) { CodePrefs.setHtmlWebView(it) }
    }

    // [CODE-ROLES] Jalankan di Terminal: dialog, pemeriksaan molina-run, urungkan
    val ctx = LocalContext.current
    val session = CodeInbox.session
    PackageSectionHeader("Jalankan di Terminal")
    PackageGroupCard {
        var ask by remember { mutableStateOf(session?.runDialogAsks ?: true) }
        PackageSwitchRow(
            "Tanya dulu sebelum jalankan",
            "Tampilkan dialog nama berkas. Bila mati, tab yang sudah berupa berkas langsung dijalankan; tab baru tetap " +
                "meminta nama.",
            ask
        ) { ask = it; session?.runDialogAsks = it }
        PackageDivider()
        var check by remember { mutableStateOf(RunCheckPrefs.get(ctx)) }
        PackageInfoRow(
            "Pemeriksaan skrip (molina-run)",
            "Berlaku untuk Jalankan di Terminal dan Alt+R di nano: pembungkus heredoc tidak valid, EOF hilang, CRLF, BOM."
        )
        listOf(
            Triple("ask", "Tanya dulu (bawaan)", "Tampil dialog dengan pilihan perbaiki, apa adanya, edit, atau batal."),
            Triple("fix", "Perbaiki otomatis", "Perbaikan sementara tanpa bertanya; berkas asli tidak diubah."),
            Triple("no", "Apa adanya", "Jalankan tanpa perbaikan; hanya tampil peringatan.")
        ).forEach { (key, title, sub) ->
            PackageChoiceRow(title, check == key, { if (RunCheckPrefs.set(ctx, key)) check = key }, sub)
        }
        PackageDivider()
        val canUndo = session?.terminalRunUndoReady == true
        Box(Modifier.clickable(enabled = canUndo) { session?.undoTerminalRun() }) {
            PackageInfoRow(
                "Urungkan jalankan terakhir",
                if (canUndo) "Ketuk untuk memulihkan berkas yang ditimpa Jalankan di Terminal terakhir."
                else "Tidak ada yang bisa diurungkan."
            )
        }
    }
}
