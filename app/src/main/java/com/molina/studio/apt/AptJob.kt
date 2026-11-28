package com.molina.studio.apt

import com.molina.studio.packages.StarterProfile

/** Pintasan perawatan Debian di Settings > Sistem. Dijalankan lewat ToolRunDialog di Debian PRoot. */
internal enum class AptJob(val title: String, val command: String, val timeoutMs: Long, val note: String) {
    UpdateUpgrade(
        "Perbarui semua program",
        "apt update -o APT::Sandbox::User=root && " +
            "apt upgrade -y -o APT::Sandbox::User=root -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-confdef",
        50 * 60_000L,
        "Mengecek program terbaru di Debian, lalu memperbarui semuanya tanpa bertanya lagi. " +
            "Butuh internet, bisa memakai banyak kuota dan memakan beberapa menit. " +
            "Pembaruan hanya berjalan jika pengecekan berhasil. Bila yt-dlp punya rilis baru, ikut diperbarui setelahnya."
    ),
    CheckUpdates(
        "Cek pembaruan program",
        "apt update -o APT::Sandbox::User=root && (apt list --upgradable 2>/dev/null | grep -c 'upgradable from' || true)",
        10 * 60_000L,
        "Mengecek ada berapa program yang bisa diperbarui. Tidak ada yang dipasang atau diubah."
    ),
    CleanUp(
        "Bersihkan sisa program yang tidak terpakai",
        "apt-get autoremove -y -o APT::Sandbox::User=root && apt-get clean",
        10 * 60_000L,
        "Menghapus program yang tidak lagi dibutuhkan dan sisa file unduhan lama. Aman dijalankan kapan saja dan menambah ruang kosong."
    ),
    InstallStarter(
        "Pasang paket dasar (Starter)",
        StarterProfile.SCRIPT,
        60 * 60_000L, // [STARTER-UPGRADE] ikut apt upgrade, jadi lebih lama
        "Memasang perangkat dasar pilihan: ${StarterProfile.DISPLAY.joinToString(", ")} (dari sumber resmi Debian dan rilis resmi yt-dlp; keasliannya diperiksa otomatis). " +
            "Sebelum memasang, seluruh program Debian diperbarui lebih dulu agar versinya mutakhir (bisa memakan kuota dan beberapa menit). " +
            "Butuh internet dan ruang kosong minimal 1 GB; yang sudah terpasang dilewati. " +
            "yt-dlp dipasang otomatis setelahnya bila belum ada. ffmpeg tidak ikut; tambahkan dari Setelan > Paket > Alat bila perlu."
    )
}
