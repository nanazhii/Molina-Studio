package com.molina.studio.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.molina.studio.ui.MolinaTheme

/**
 * [ABOUT-MOLINA] Settings > Sistem > Info > Tentang Molina: penjelasan singkat untuk pengguna awam
 * (bukan dokumentasi teknis). Detail versi dan perangkat tetap di halaman Info.
 */
@Composable
internal fun AboutMolinaPage(onLicenses: () -> Unit) {
    Text(
        "IDE mobile dengan multimedia dalam satu aplikasi Android.",
        color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium
    )
    AboutSection(
        "Apa itu Molina?",
        "Molina Studio adalah aplikasi Android gratis dan bebas (open source) yang menggabungkan dua hal: IDE mobile " +
            "untuk menulis dan menjalankan kode (terminal Debian Linux, editor, dan pengelola paket) serta pemutar media bergaya home theater untuk menonton dan mendengarkan. Semuanya berjalan " +
            "langsung di ponsel Anda, tanpa perlu root."
    )
    AboutSection(
        "Molina Terminal",
        "Terminal dengan lingkungan Debian Linux. Pasang alat seperti Python, JDK, dan FFmpeg, jalankan skrip, lalu edit " +
            "kode di editor bawaan (Code) yang punya sorotan sintaks dan pratinjau HTML. Tersedia juga cadangan setelan, " +
            "Recycle Bin, dan penjelajah berkas."
    )
    AboutSection(
        "Molina Cinema",
        "Pemutar video dan audio dengan tampilan home theater. Bisa melanjutkan dari posisi terakhir, memakai dekoder " +
            "perangkat keras, memutar playlist IPTV, dan punya Mode TV untuk menjelajah berkas ala saluran TV. Video atau " +
            "audio dari tautan bisa diunduh lewat menu Unduhan."
    )
    AboutSection(
        "Privasi",
        "Molina tidak memakai iklan, pelacak, atau layanan analitik. Statistik tontonan dan riwayat tersimpan di perangkat " +
            "Anda. Koneksi internet hanya dipakai untuk hal yang Anda jalankan sendiri, seperti memutar atau mengunduh " +
            "dari tautan dan memperbarui paket Debian."
    )
    AboutSection(
        "Data Anda",
        "Berkas Molina berada di folder Molina di penyimpanan internal (dari dalam Debian terlihat sebagai /sdcard/Molina): " +
            "unduhan, cadangan, laporan crash, dan log. Data rahasia disimpan terpisah di penyimpanan privat aplikasi."
    )
    AboutSection("Kebutuhan", "Android 8.0 (Oreo) atau lebih baru, dengan prosesor 64-bit (arm64).")
    AboutSection(
        "Pengembang",
        "Dikembangkan oleh Molina Studio (nanazhii). Perangkat lunak bebas di bawah GPL-3.0-or-later, tanpa jaminan apa pun."
    )
    Spacer(Modifier.height(10.dp))
    W7Link({ W7IconToolList() }, "Lisensi dan atribusi", "Lisensi Molina dan komponen pihak ketiga, bisa dibaca tanpa internet") { onLicenses() }
}

@Composable
private fun AboutSection(title: String, body: String) {
    Text(
        title, color = MolinaTheme.Gold, style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
    )
    Text(
        body, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        style = MaterialTheme.typography.bodyMedium
    )
}
