# Panduan Warna Molina

Satu sumber kebenaran untuk warna merek dan status di seluruh Molina-Studio (Terminal, Cinema, Code).
Nilai di tabel diambil dari kode; bila berbeda, kodenya yang benar dan dokumen ini perlu diperbarui.

## Prinsip

1. **Satu aksen merek: hijau zamrud.** Selain itu hanya netral dingin, warna status, dan warna identitas modul.
2. **Token dinamai berdasarkan peran, bukan warna** (`Brand`, bukan `Green`), supaya penggantian warna cukup mengubah nilai.
3. **Status tidak boleh memakai warna merek.** `Good` sengaja beda hue dari `Brand` (94° vs 145°).
4. **Peringatan selalu amber**, tidak pernah hijau.
5. **Status selalu disertai ikon atau teks**, bukan warna saja.

## Warna inti (`MolinaTheme`, `app/.../ui/MolinaTheme.kt`)

| Token | Hex | Kontras di latar | Peran |
|---|---|---|---|
| `Brand` | `#2ECC71` | 9,4:1 | Aksen merek: tombol utama, fokus, pil sesi, notifikasi layanan, prompt path |
| `Good` | `#9BE564` | 13,0:1 | Status berhasil/OK |
| `Warn` | `#FFD54F` | 14,0:1 | Status peringatan |
| `Bad` | `#FF8A80` | 8,7:1 | Status gagal/kesalahan |
| `Cinema` | `#B388FF` | 7,4:1 | Identitas Molina Cinema (ikon putar, log mpv, tab) |
| `NetDown` / `NetUp` | `#82B1FF` / `#FF8A80` | 9,1 / 8,7 | Kecepatan jaringan di bilah sesi |
| `Muted` | `#9AA0A6` | 7,5:1 | Teks sekunder |
| `OnBackground` | `#EDEDED` | 16,9:1 | Teks utama |
| `Background` | `#0A0A0F` | - | Latar app dan navigasi bawah |
| `CardBg` / `CardLine` | `#14161B` / `#2A2D35` | - | Kartu dan garis pembatas |

Kontras dihitung terhadap `Background` (`#0A0A0F`); target minimal 4,5:1 untuk teks.

## Editor Code (`CodeColors`, `feature/code/.../CodeColors.kt`)

| Token | Hex | Peran |
|---|---|---|
| `Brand` | `#2ECC71` | Kursor, tautan, seleksi, tombol aktif, rail toolbar |
| `BrandSoft` | `#7BDCA0` | Judul pratinjau, kode inline, lencana tab |
| `Warning` | `#FFD54F` | Peringatan di Problems (= `MolinaTheme.Warn`, bukan aksen merek) |
| `Danger` | `#E5484D` | Kesalahan, aksi destruktif |
| `Clean` | `#3FB950` | Indikator berkas belum berubah |
| `FolderYellow` | `#E9B949` | Folder dan penanda JSON (kuning konvensional) |
| `Capsule` / `ActiveLine` | `#133B25` / `#0E2A1A` | Kapsul aktif / baris aktif (hijau tua) |
| `IconDefault` | `#C4CBD3` | Ikon dan label netral (12,1:1) |
| `IconDim` / `IconDisabled` | `#767D86` / `#474B52` | Ikon redup / nonaktif (4,7:1 / dekoratif) |
| `EditorBg` / `Surface1..2` | `#000000` / `#14161B`..`#181B21` | Permukaan editor |

Warna sintaks (`Json*`, `Ext*`) dan nomor baris terakota adalah identitas editor, bukan aksen merek; jangan diubah mengikuti `Brand`.

## Navigasi bawah (`MainActivity`)

| Tab | Hex | Catatan |
|---|---|---|
| Cinema | `#B388FF` | Ungu |
| Berkas | `#5C9DFF` | Biru |
| Editor | `#2ECC71` | Brand |
| Pengaturan | `#B8C2CC` | Abu dingin terang |

## Terminal

- ANSI truecolor dibentuk dari konstanta: `MolinaTheme.BRAND_SGR` (`38;2;46;204;113`) dan `GOOD_SGR` (`38;2;155;229;100`). Nilai ARGB dan SGR harus selalu sinkron.
- Prompt Noir (default): path = Brand, user = putih tebal `237;237;237`, branch git = ungu `179;136;255`.
- Slot ANSI "yellow" di `TerminalColorScheme.java` sengaja tetap kuning agar keluaran `ls` dan git tidak berubah; hanya kursor default Noir yang memakai Brand.
- ID preset tersimpan (`noir_gold`, `cinema_gold`) tidak boleh diganti; hanya nama enum dan label yang boleh.

## Aturan pakai

**Lakukan**
- Pakai `Brand` untuk satu aksi utama per layar.
- Pakai `Good`/`Warn`/`Bad` hanya untuk status, dengan ikon atau teks pendamping.
- Teks di atas `Brand` memakai hitam (`#000000`, kontras 10:1).

**Jangan**
- Memakai `Brand` untuk status OK, atau `Good` untuk aksen merek.
- Menaruh dua aksen hijau berdampingan (misalnya `Brand` dan `Good` dalam satu baris).
- Menulis hex langsung di layar; tambahkan atau pakai token.

## Cara mengganti warna merek

1. Ubah `BRAND_ARGB` dan `BRAND_SGR` di `MolinaTheme.kt`, dan `Brand`/`BrandSoft` di `CodeColors.kt`.
2. Sinkronkan literal di luar token (tidak bisa mereferensikan token):
   - `feature/mpv/src/main/res/` (`values/colors.xml`, `drawable/bg_wmp_*.xml`, `bg_seek_preview.xml`, `layout/player.xml`)
   - `feature/mpv/.../MPVActivity.kt` (penanda A-B) dan `MarkedSeekBar.kt`
   - `terminal-view/.../TerminalView.java` (`HIT_COLOR`) dan `terminal-emulator/.../TerminalColorScheme.java` (kursor)
   - `app/src/main/res/drawable/ic_cinema_launcher_foreground.xml`, `ic_cinema_orb.xml`, `ic_shortcut_code_foreground.xml`
   - `MainActivity.kt` (tab Editor), `MolinaLinkSheet.kt`, `CinemaWin7Home.kt` (alfa)
3. Geser turunan gelap (`Capsule`, `ActiveLine`, `ButtonBrown`, tint pratinjau, `bg_channel_selected`) ke hue yang sama.
4. Pastikan `Good` tetap berjarak minimal ~40° hue dari `Brand`.
5. Cek `grep -rniE "<hex lama>"` di `*.kt`, `*.java`, `*.xml` sampai kosong, lalu build via CI.

## Utang desain yang diketahui

- Merah punya dua peran yang disengaja: `Bad` `#FF8A80` (teks/ikon status, app) dan `Danger` `#E5484D` (aksi destruktif, editor). `MenuDanger` dan `KeyRed` (`#FF5252`) masih terpisah; satukan ke `Danger` bila tampilan menu dan tombol boleh disesuaikan.
- Hijau status di luar token: `CodeColors.Clean` `#3FB950` (indikator berkas) dan `KeyGreen` `#00E676` (tombol COPY/PASTE).
- `vault` memakai amber `#FFB300` sendiri (khusus peringatan) dan belum memakai token ini.
- Aksen biru `Accent` privat di `CinemaResults.kt` dan `W7D.Accent` belum dipetakan ke token.
- Lencana ikon lama di `Win7SettingsUi.kt` (`W7IconLog`, `W7IconYtdlp`, `W7IconInfo`, `W7IconHit`, serta `W7IconToolList` di `PackageScriptUi.kt`) dan sparkline `SystemMonitorUi` masih memakai hex langsung; petakan ke token bila tampilannya boleh disesuaikan. Lencana baru (`W7IconDebug`, `W7IconIo`, `W7IconAa`, `W7IconSysInfo`, `W7IconCode`) dan `W7IconShield` sudah memakai token. [LENCANA-HEX]
