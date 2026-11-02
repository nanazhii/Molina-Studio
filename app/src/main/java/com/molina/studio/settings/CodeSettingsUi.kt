package com.molina.studio.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.molina.studio.code.CodePrefs

/** Halaman "Editor Code": perilaku pratinjau (tombol Play) di editor kode. */
@Composable
internal fun CodeSettingsPage() {
    val webView by CodePrefs.htmlWebView.collectAsState()
    Category("Pratinjau HTML")
    SettingsItem(
        "Renderer native (default)",
        "Judul, paragraf, daftar, tabel, kutipan, blok kode, dan tautan digambar langsung oleh aplikasi. " +
            "CSS, JavaScript, dan gambar tidak diproses. Ringan dan tidak memuat apa pun dari jaringan."
    ) {}
    SwitchRow(
        "Pakai WebView untuk HTML",
        "Menampilkan halaman HTML dengan tampilan penuh (CSS inline dan gambar data:). JavaScript, jaringan, " +
            "dan akses berkas tetap dimatikan, dan tautan dibuka di peramban. Cocok bila renderer native " +
            "terlalu sederhana. Berlaku untuk berkas .html berikutnya yang dibuka dengan Play.",
        webView
    ) { CodePrefs.setHtmlWebView(it) }
}
