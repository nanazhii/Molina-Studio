# Molina Studio

Ekosistem aplikasi Android Molina:

- **Molina Terminal**: terminal dengan lingkungan Debian (PRoot), manajemen paket, dan editor kode.
- **Molina Player / Molina Cinema**: pemutar media berbasis mpv dengan antarmuka bergaya home theater.

Target perangkat: Android 8.0 (API 26) ke atas, arm64.

## Struktur folder di penyimpanan internal

Data aplikasi berada di satu folder, `/storage/emulated/0/Molina/` (terlihat di Debian sebagai `/sdcard/Molina`):

| Subfolder | Isi |
|---|---|
| `Workspace/` | proyek dan berkas kerja (folder awal editor Code) |
| `Scripts/` | skrip bawaan yang terlihat dari Debian |
| `Downloads/` | unduhan |
| `Media/Video`, `Media/Audio`, `Media/IPTV` | media |
| `Documents/`, `Documents/Export/` | dokumen dan hasil ekspor |
| `Backup/` | cadangan |
| `Logs/Terminal/`, `Crash/` | log dan laporan crash |

Rahasia hanya disimpan di penyimpanan privat aplikasi, bukan di folder ini.

## Build

Build resmi dijalankan lewat GitHub Actions (lihat `.github/workflows/`).

## Lisensi

GPL-3.0-or-later. Lihat `LICENSE` dan `THIRD_PARTY_NOTICES.md`.
