package com.molina.studio.settings

import com.molina.studio.ui.components.PackageDivider
import com.molina.studio.ui.components.PackageGroupCard
import com.molina.studio.ui.components.PackageInfoRow
import com.molina.studio.ui.components.PackageSectionHeader
import com.molina.studio.ui.components.PackageSwitchRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.molina.studio.code.CodePrefs
import com.molina.studio.code.RunCheckPrefs
import com.molina.studio.ui.components.PackageChoiceRow
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

    // [CODE-OFFLINE] Penjelasan privasi (tanpa sakelar: kunci offline tidak boleh dimatikan dari Setelan)
    PackageSectionHeader("Privasi")
    PackageGroupCard {
        PackageInfoRow(
            "Editor, Penjelajah, dan Pratinjau bekerja offline",
            "Kode editor, Penjelajah, dan Pratinjau tidak membuka koneksi internet. Riwayat dan snapshot " +
                "proyek tersimpan di perangkat. Pratinjau HTML selalu tanpa JavaScript dan tanpa jaringan, " +
                "dan ini dijaga otomatis saat build. Skrip yang Anda jalankan di Debian memakai jaringan " +
                "perangkat seperti biasa."
        )
    }

    // [CODE-ROLES] Pemeriksaan skrip molina-run
    val ctx = LocalContext.current
    PackageSectionHeader("Pemeriksaan skrip")
    PackageGroupCard {
        var check by remember { mutableStateOf(RunCheckPrefs.get(ctx)) }
        PackageInfoRow(
            "Pemeriksaan skrip (molina-run)",
            "Berlaku untuk Alt+R di nano: pembungkus heredoc tidak valid, EOF hilang, CRLF, BOM, dan shebang yang tidak cocok dengan ekstensi."
        )
        listOf(
            Triple("ask", "Tanya dulu (bawaan)", "Tampil dialog dengan pilihan perbaiki, apa adanya, edit, atau batal."),
            Triple("fix", "Perbaiki otomatis", "Perbaikan sementara tanpa bertanya; berkas asli tidak diubah."),
            Triple("no", "Apa adanya", "Jalankan tanpa perbaikan; hanya tampil peringatan.")
        ).forEach { (key, title, sub) ->
            PackageChoiceRow(title, check == key, { if (RunCheckPrefs.set(ctx, key)) check = key }, sub)
        }
        PackageDivider()
        PackageInfoRow( // [SCRIPT-HINT] sakelar dipindah ke halaman Script Execution
            "Tips shebang dan izin eksekusi",
            "Sakelarnya kini ada di Settings > Script execution, bersama penanda amber per skrip. Berlaku juga untuk tips " +
                "di nano dan saat skrip dijalankan."
        )
    }
}
