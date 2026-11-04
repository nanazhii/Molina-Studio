# Molina Vault: Model Ancaman dan Desain Kripto

## Aset
Isi entri (keystore, password, rahasia), vaultKey, master password, metadata (judul, jumlah entri).

## Desain kripto
- KDF: PBKDF2-HMAC-SHA256, 900.000 iterasi, salt 16 byte acak. Parser menolak iterasi < 600.000 (anti-downgrade) dan > 10.000.000 (anti-DoS).
- vaultKey: 256-bit acak (CSPRNG), dibungkus 2 lapis AES-256-GCM: (1) kunci dari master, (2) kunci Android Keystore perangkat. Header menjadi AAD kedua lapisan.
- Entri: subkunci HKDF-SHA256 per (jenis, id); AES-256-GCM, IV 96-bit acak; AAD = "jenis|id". Metadata dan isi dienkripsi terpisah. Folder dan tag (opsional) ikut di dalam metadata terenkripsi; record lama tanpa field itu tetap valid dan format .mvault tidak berubah.
- Penyimpanan: noBackupFilesDir, tulis atomik. Tanpa cloud, tanpa backup otomatis, tanpa izin internet. Pengaturan non-rahasia (auto-lock, waktu cadangan terakhir, jumlah perubahan sejak cadangan) disimpan di shared_prefs; tidak memuat isi, judul, atau jumlah entri.

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
| 21 | Berkas .mvault disalin lalu diserang offline | Passphrase acak 120 bit atau buatan sendiri >= 24 karakter ASCII; PBKDF2 2.000.000 iterasi; HMAC trailer | PBKDF2 tidak memory-hard; passphrase buatan sendiri yang lemah |
| 22 | Clipboard pada kolom rahasia dan multi-baris | Seleksi, salin, dan tempel dimatikan di kolom rahasia; peringatan di form | Keyboard pihak ketiga dan riwayat clipboard milik keyboard |
| 23 | Ekspor plaintext ke penyimpanan bersama | Peringatan eksplisit sebelum ekspor | Aplikasi lain dengan izin storage (rencana: ekspor terenkripsi sebagai default) |
| 24 | Limiter dikelabui lewat jam atau reboot | Jam monoton (elapsedRealtime) + BOOT_COUNT; reboot mengulang tunda penuh | Root menghapus data limiter |
| 25 | Android 8 tanpa patch keamanan terbaru | Peringatan umur patch (> 365 hari) di layar unlock | Celah root publik; jangan simpan rahasia bernilai tertinggi di perangkat ini |
| 26 | Kunci Keystore hanya perangkat lunak | Peringatan via KeyInfo.isInsideSecureHardware | Root dapat mengekstrak kunci; perlindungan bergantung pada master |
| 27 | Accessibility service non-sistem | Unlock diblokir; dicek ulang saat rahasia ditampilkan | Layanan yang diaktifkan setelah rahasia tampil; keyboard jahat |
| 28 | Master lama bocor setelah ganti master | Ganti master merotasi vaultKey dan mengenkripsi ulang semua record (staging, baca-ulang, penanda COMMIT, pulih otomatis bila terhenti); UI memperingatkan soal cadangan lama | Cadangan .mvault lama dan salinan lama vault tetap terbuka dengan kunci lama; record yang sudah rusak dilewati |
| 29 | Record rusak atau diubah tanpa disadari | Gudang > Periksa integritas: dekripsi dan verifikasi segel GCM semua record serta SHA-256 dan chunk blob; waktu pemeriksaan lolos terakhir dicatat | Berkas yang dihapus seluruhnya dan rollback ke versi lama yang sah tidak terdeteksi (ditangani manifest, lihat #30) |
| 30 | Record dihapus, ditukar versi lama, disambung (meta lama + data baru), atau diselipkan di luar Vault | Manifest tersegel (manifest.mvm): terenkripsi kunci turunan vaultKey; per record id, tag SHA-256 (meta, IV/tag data, panjang), judul; diperbarui tiap tulis dengan jurnal pending; diperiksa sekali per sesi dan oleh Periksa integritas | Rollback seluruh folder bersama manifest hanya terdeteksi bila shared_prefs tidak ikut diputar balik (root dapat memutar keduanya); entri yang hilang tidak bisa dipulihkan, hanya dilaporkan |

## Manifest tersegel (MOLINA-H15-MANIFEST)
- `manifest.mvm` = "MVMF" | versi 1 | AES-256-GCM(kunci turunan vaultKey, AAD "manifest|vault"). Isi: penghitung, lalu per record `id | SHA-256 tag | judul`.
- Tag = SHA-256(panjang berkas, versi, meta tersegel, small tersegel v3 atau IV data v2, 16 byte terakhir). Hanya kepala dan ekor berkas yang dibaca.
- Pembaruan bersifat inkremental per id (bukan pemindaian ulang), jadi kerusakan pada id lain tidak pernah "dibersihkan" oleh penulisan yang sah. Manifest yang rusak, hilang, atau tampak diputar balik tidak ditulis ulang sampai pengguna menerima.
- Jangkar anti-rollback: penghitung manifest juga disimpan di shared_prefs (di luar folder vault). Penghitung manifest < jangkar berarti folder dikembalikan ke versi lama. Jangkar ditulis setelah manifest, sehingga crash di antaranya tidak memicu alarm palsu.
- Jurnal pending: id dicatat (commit sinkron) sebelum menulis dan dihapus sesudah manifest diperbarui. Bila proses mati di antaranya, pemeriksaan berikutnya memulihkan id itu saja.
- Rotasi master membangun manifest dengan kunci baru di staging dan memindahkannya bersama record (satu COMMIT). Pemulihan cadangan tidak membawa manifest; vault hasil pemulihan diadopsi saat dibuka pertama kali.
- Batas yang diakui: vault lama tanpa manifest diadopsi apa adanya sekali (tidak ada pengetahuan sebelumnya); root yang memutar balik folder dan shared_prefs sekaligus tidak terdeteksi; IO yang gagal setelah tulis sah memunculkan alarm palsu langka.

## Di luar cakupan
Malware root aktif selama sesi terbuka; paksaan fisik terhadap pengguna.

## Roadmap
Keypad aman. Argon2id ditolak (tanpa dependensi pihak ketiga; PBKDF2 tetap dengan kebijakan passphrase).
