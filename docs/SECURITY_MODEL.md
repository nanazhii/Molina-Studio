# Molina Vault: Model Ancaman dan Desain Kripto

## Aset
Isi entri (keystore, password, rahasia), vaultKey, master password, metadata (judul, jumlah entri).

## Desain kripto
- KDF: PBKDF2-HMAC-SHA256, 900.000 iterasi, salt 16 byte acak. Parser menolak iterasi < 600.000 (anti-downgrade) dan > 10.000.000 (anti-DoS).
- vaultKey: 256-bit acak (CSPRNG), dibungkus 2 lapis AES-256-GCM: (1) kunci dari master, (2) kunci Android Keystore perangkat. Header menjadi AAD kedua lapisan.
- Entri: subkunci HKDF-SHA256 per (jenis, id); AES-256-GCM, IV 96-bit acak; AAD = "jenis|id". Metadata dan isi dienkripsi terpisah.
- Penyimpanan: noBackupFilesDir, tulis atomik. Tanpa cloud, tanpa backup otomatis, tanpa izin internet.

## Matriks ancaman
| # | Ancaman | Kontrol | Risiko sisa |
|---|---------|---------|-------------|
| 1 | Exfiltrasi lewat jaringan oleh Vault | Tanpa INTERNET, network config kosong, verify-bunker.sh di CI | Nihil dari sisi Vault |
| 2 | Aplikasi lain (Terminal/PRoot) membaca data | UID terpisah, tanpa komponen exported, tanpa sharedUserId, tanpa jembatan | Perangkat root |
| 3 | Rantai pasok: CI, dependensi, kunci tanda tangan | Tanpa dependensi pihak ketiga; pin action GitHub ke SHA; GITHUB_TOKEN read-only; signing key di Secrets | Akun GitHub disusupi |
| 4 | Overlay / tapjacking | filterTouchesWhenObscured | Rendah |
| 5 | Screenshot, recents, screen record | FLAG_SECURE | Kamera eksternal |
| 6 | Clipboard disadap | Clipboard tidak dipakai | Nihil |
| 7 | Accessibility, keyboard, autofill jahat | Peringatan accessibility non-sistem; autofill dimatikan di field | Keyboard pihak ketiga (rencana: keypad aman) |
| 8 | Task hijacking | taskAffinity kosong, singleTask | Rendah |
| 9 | Backup adb / cloud | allowBackup=false, noBackupFilesDir | Nihil |
| 10 | Perangkat dicuri | Butuh kunci Keystore + master; tebak langsung dibatasi KDF + limiter | Master lemah + root |
| 11 | Brute force offline pada salinan file | Kunci Keystore tidak dapat diekstrak; tanpa itu brute force tidak berarti | Bug TEE/Keystore |
| 12 | Forensik sisa data | Terenkripsi; secure-delete best-effort | Wear-leveling flash |
| 13 | Dump memori | vaultKey dan CharArray di-wipe saat terkunci | SecretKeySpec dan String tidak bisa di-wipe |
| 14 | Root + hook (Frida) saat sesi terbuka | Tidak dapat dicegah penuh | Sesi terbuka bocor |
| 15 | Entri dimanipulasi / ditukar | GCM + AAD(id) | Penghapusan dan rollback versi lama tidak terdeteksi |
| 16 | Nonce reuse | Subkunci per entri + IV acak 96-bit | Nihil praktis |
| 17 | Downgrade KDF | Lantai iterasi di parser | Nihil |
| 18 | Master lemah | Kebijakan 14 karakter / 5 kata | Kelalaian pengguna |
| 19 | Master atau perangkat hilang | Tanpa pemulihan (by design); cadangan terenkripsi (P3) | Pengguna lupa mencadangkan |
| 20 | Rollback seluruh vault | Tidak ada penghitung monoton hardware di API 26 | Diterima |

## Di luar cakupan
Malware root aktif selama sesi terbuka; paksaan fisik terhadap pengguna.

## Roadmap
Manifest tersegel (deteksi hapus/ganti), rotasi vaultKey, Argon2id, keypad aman, cadangan terenkripsi (P3).
