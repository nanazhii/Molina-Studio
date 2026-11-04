# Komponen UI Molina (`ui/components`)

Pustaka kartu bersama untuk halaman Setelan, Paket, Terminal, dan Info. Satu bahasa visual: sudut 10dp, border 1,5dp
(`MolinaTheme.PanelBorder`), latar `MolinaTheme.Explorer.Card`, judul 15sp tebal, subjudul 12sp redup.
Sumber: `app/src/main/java/com/molina/studio/ui/components/PackageCards.kt` (semua `internal`).
Warna selalu dari token `MolinaTheme` (lihat `docs/branding/panduan-warna.md`), bukan hex.

## Komponen

| Komponen | Fungsi |
|---|---|
| `PackageSectionHeader(title, trailing?, state)` | Header seksi; `trailing` ringkasan status di kanan. |
| `PackageStatusCard(title, subtitle, state, badge?, onClick?, subtitleMaxLines = 3, icon?)` | Kartu tunggal. `state = null` tanpa strip status; `onClick` non-null menampilkan penanda `▸` dan menjadikan kartu bisa diketuk; `icon` ikon opsional di kiri. Judul dibatasi satu baris (elipsis). |
| `PackageGroupCard { ... }` | Satu kartu berisi beberapa baris. Pisahkan baris dengan `PackageDivider()`. |
| `PackageInfoRow(title, value)` | Baris baca-saja: judul tebal, nilai redup. |
| `PackageSwitchRow(title, summary, checked, onChange)` | Baris sakelar; seluruh baris bisa diketuk. |
| `PackageChoiceRow(title, selected, onSelect, subtitle?, preview?)` | Pilihan tunggal (radio); `preview` untuk cuplikan visual (mis. contoh prompt berwarna). |
| `PackageSliderRow(title, valueText, value, onValueChange, valueRange, steps = 0, footer?)` | Slider dengan nilai di kanan judul; `footer` untuk pratinjau langsung. |
| `PackageNote(text)` | Catatan redup di bawah kartu grup. |
| `PackageActionsBlock(running, updates, lastCheck, updateNote, onCheck, onUpdateAll)` | Kapsul PERIKSA SEKARANG dan PERBARUI SEMUA plus catatan. |
| `ActionChip(label, color, onClick)` | Tombol aksi kapsul beroutline. |
| `MolinaBadge(text, aero = false, gold = false)` | Lencana jumlah/status pada kartu. |
| `statusStripColor(detected, checking, checked)` | Warna strip: terdeteksi, belum diperiksa, atau memeriksa (berdenyut). |

## Status (`PkgState`)

`Unchecked`, `Current`, `Outdated`, `Checking`. Strip kiri kartu: `Outdated` memakai `MolinaTheme.Warn`; lainnya lewat
`statusStripColor`. Status selalu disertai teks (bukan warna saja).

## Registry status komponen (`settings/ManagedComponents.kt`)

Satu sumber status untuk komponen terkelola: `ManagedComponents.debian / ytdlp / newpipe / bundle`
menghasilkan `ManagedComponent(id, title, subtitle, state, badge)`. Dipakai oleh Pembaruan, Alat, Hub, dan Bundle.
**Jangan menghitung `PkgState` manual di Composable lain.**

## Ikon kartu (`settings/Win7SettingsUi.kt`, `slot icon`)

`W7IconTerminal`, `W7IconLog`, `W7IconInfo`, `W7IconYtdlp`, `W7IconHit`, `W7IconShield`, serta `W7IconDebug`,
`W7IconIo`, `W7IconAa` (lencana Terminal; warna dari `MolinaTheme.Link` dan `MolinaTheme.Muted`). Ikon besar beranda
(`W7Icon*Big`) dipakai oleh `W7CategoryTile`, bukan oleh kartu.

## Aturan pakai

- Daftar yang sebagian kartunya ber-ikon harus ber-ikon semuanya, supaya rata.
- Rumah tunggal fungsi: Debian di Paket > Program Debian, yt-dlp di Paket > Alat, cadangan dan profil di Sistem.
  Halaman lain hanya menaut atau menampilkan status baca-saja.
- Ikon dan lencana baru memakai token `MolinaTheme`; jangan menambah hex baru.
- Dikecualikan dari kartu: `SystemMonitorUi` (gaya kaca dengan meter; `SwitchRow` lama tetap untuk sakelar RGB) dan
  beranda Settings (`W7CategoryTile`). Keduanya identitas sengaja, bukan utang.
- Animasi RGB: `RgbFrame(animate, spot)` bergerak hanya bila `animate`, sakelar utama `GlassPrefs.rgb` (bawaan mati), dan
  sakelar lokasi `GlassPrefs.spotOn(spot)` (bawaan menyala) aktif. Lokasi: `RgbSpot.Cinema` (animate = `live || busy`),
  `Unduhan` (`run.running`), `Monitor`. Sakelar ada di Sistem > Monitor; kunci `rgb_spot_*` ikut cadangan gabungan.
