# Molina Studio

**IDE mobile dengan multimedia untuk Android.** Tulis dan jalankan kode di terminal Linux, lalu tonton atau dengarkan media, semuanya dalam satu aplikasi. Gratis, bebas, dan tanpa iklan.

## Apa itu Molina?

Molina adalah IDE mobile yang sekaligus menjadi pusat multimedia. Di satu sisi ada ruang kerja untuk pemrogram: terminal Debian Linux, editor kode, dan pengelola paket. Di sisi lain ada ruang hiburan: pemutar video dan audio bergaya home theater serta pengunduh dari tautan.

Anda bisa menulis skrip, menjalankannya, memasang alat tambahan, lalu pindah menonton video tanpa meninggalkan aplikasi. Semuanya berjalan langsung di ponsel Android Anda, tanpa perlu root.

## Isi aplikasi

| Bagian | Fungsinya |
|---|---|
| **Terminal** | Lingkungan Debian Linux lengkap di ponsel |
| **Code** | Editor untuk menulis, memeriksa, dan menjalankan skrip |
| **Cinema** | Pemutar video dan audio bergaya home theater |
| **Unduhan** | Simpan video atau audio dari tautan |
| **Paket** | Pasang dan perbarui program di Debian |
| **Pengaturan** | Penyimpanan, cadangan, keamanan, dan info sistem |

## Molina Terminal

- Terminal dengan lingkungan Debian Linux, berjalan tanpa root.
- Pasang alat seperti Python, JDK, dan FFmpeg dari menu Paket. Paket dasar (Starter) bisa dipasang sekali ketuk.
- Cadangkan, pulihkan, atau kembalikan Debian ke kondisi awal.
- Cadangan setelan, Recycle Bin, dan penjelajah berkas.

## Molina Code

- Editor dengan sorotan sintaks untuk Python, Shell, Kotlin, Java, Lua, JSON, XML, HTML, dan Markdown.
- Jalankan skrip sh, bash, dan Python langsung di Debian.
- Pratinjau HTML, Markdown, dan JSON.
- Pemeriksa kode: JSON dan XML bawaan, serta Python (pyflakes) dan Shell (shellcheck) bila terpasang di Debian.
- Pemformat JSON dan XML.
- Dukungan language server (LSP) untuk Python.
- Pencarian dalam berkas, palet perintah, dan riwayat perubahan dengan perbandingan (diff).

## Molina Cinema

- Pemutar video dan audio dengan tampilan home theater.
- Melanjutkan tontonan dari posisi terakhir dan mendukung dekoder perangkat keras.
- Gestur sentuh untuk mengatur suara, kecerahan, dan maju atau mundur.
- Pengatur waktu tidur dan pemutaran di latar belakang.
- Playlist IPTV dan Mode TV untuk menjelajah berkas ala saluran TV.
- Statistik tontonan yang tersimpan di perangkat.

## Unduhan

- Unduh video atau audio dari tautan, dengan daftar tunggu, riwayat, dan pilihan mengulang yang gagal.
- Pilih kualitas, subtitle, dan sampul, serta batasi unduhan hanya lewat Wi-Fi.
- Hasil unduhan otomatis muncul di Molina Cinema.

## Contoh alur kerja

1. Tulis skrip Python di Molina Code, lalu jalankan di Debian.
2. Pasang FFmpeg dari menu Paket untuk mengolah media.
3. Unduh video dari tautan, lalu putar di Molina Cinema.

## Memulai

1. Pasang APK yang dibangun otomatis oleh GitHub Actions (tab Actions di repositori ini).
2. Saat pertama dibuka, Molina memasang Debian. Siapkan koneksi internet dan ruang kosong yang cukup.
3. Buka Pengaturan > Paket untuk memasang alat yang Anda butuhkan.

## Privasi

Molina tidak memakai iklan, pelacak, atau layanan analitik. Riwayat dan statistik tontonan tersimpan di perangkat
Anda. Koneksi internet hanya dipakai untuk hal yang Anda jalankan sendiri, seperti memutar atau mengunduh dari tautan
dan memperbarui paket Debian.

## Kebutuhan

Android 8.0 (Oreo) atau lebih baru, dengan prosesor 64-bit (arm64).

## Struktur proyek

- `app`: aplikasi utama, termasuk Pengaturan, Unduhan, dan Paket.
- `terminal-emulator` dan `terminal-view`: mesin dan tampilan terminal.
- `feature/code`: editor Molina Code.
- `feature/mpv`: pemutar Molina Cinema.
- `engine/newpipe`: mesin pengenal tautan video.
- `core/common` dan `core/storage`: kode bersama dan penyimpanan.
- `native` dan `scripts`: komponen native (mpv, FFmpeg) dan skrip pemeriksaan build.

Proyek dibangun lewat GitHub Actions (lihat `.github/workflows`).

## Melaporkan masalah

Buka Pengaturan > Sistem > Info > Laporan bug untuk membuat berkas log, lalu lampirkan saat membuka isu di GitHub.

## Lisensi

Molina Studio adalah perangkat lunak bebas di bawah GPL-3.0-or-later (lihat `LICENSE`), tanpa jaminan apa pun.
Komponen pihak ketiga yang dipakai beserta lisensinya tercantum di `THIRD_PARTY_NOTICES.md` dan di dalam aplikasi:
Settings > Sistem > Info > Lisensi dan atribusi (teks lisensi bisa dibaca tanpa internet).
