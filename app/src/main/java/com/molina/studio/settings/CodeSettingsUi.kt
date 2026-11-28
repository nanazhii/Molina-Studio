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
            "Digambar langsung oleh aplikasi, ringan."
        )
        PackageDivider()
        PackageSwitchRow(
            "Pakai WebView untuk HTML",
            "Tampilan HTML penuh (CSS inline dan gambar data:).",
            webView
        ) { CodePrefs.setHtmlWebView(it) }
    }

    // [CODE-OFFLINE] Penjelasan privasi (tanpa sakelar: kunci offline tidak boleh dimatikan dari Setelan)
    PackageSectionHeader("Privasi")
    PackageGroupCard {
        PackageInfoRow(
            "Editor, Penjelajah, dan Pratinjau bekerja offline",
            "Tidak membuka koneksi internet."
        )
    }

    // [CODE-ROLES] Pemeriksaan skrip molina-run
    val ctx = LocalContext.current
    PackageSectionHeader("Pemeriksaan skrip")
    PackageGroupCard {
        var check by remember { mutableStateOf(RunCheckPrefs.get(ctx)) }
        PackageInfoRow(
            "Pemeriksaan skrip (molina-run)",
            "Berlaku untuk Alt+R di nano."
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
            "Sakelar ada di Settings > Script execution."
        )
    }
}
