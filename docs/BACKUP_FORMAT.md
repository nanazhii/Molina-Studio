# Format cadangan `.mvault` (Molina Vault)

Cadangan dibuat dari sesi terbuka lewat SAF `ACTION_CREATE_DOCUMENT`; pemulihan hanya ke vault kosong.

```
header(29)  = "MVBK"(4) | versi=1(1) | iterasi(4, big-endian) | salt(16) | jumlahRecord(4)
wrapped(60) = iv(12) || AES-256-GCM[PBKDF2(passphrase cadangan)]( vaultKey ), AAD = header
record*     = id(36, ASCII UUID) | panjang(4) | isi rec_<id>.mvr apa adanya
trailer(32) = HMAC-SHA256 atas semua byte sebelumnya; kunci = HMAC(vaultKey, "molina-vault/backup-mac/v1")
```

- KDF: PBKDF2-HMAC-SHA256, target 2.000.000 iterasi (cadangan lama dengan >=600.000 tetap bisa dipulihkan); parser menolak <600.000 (anti-downgrade) dan >10.000.000 (anti-DoS).
- Tanpa lapisan Keystore, sehingga bisa dibuka di perangkat lain. Pertahanan = passphrase cadangan + KDF.
- Record tidak dienkripsi ulang: sudah tersegel dengan subkunci HKDF dan AAD yang memuat id.
- Trailer HMAC mendeteksi record dihapus, ditukar, ditambah, atau berkas terpotong (GCM per record saja tidak).
- Passphrase cadangan wajib berbeda dari master password (diperiksa dengan membuka header vault).
- Pemulihan: staging, verifikasi HMAC dan tiap record, pindah, lalu `vault.mvlt` ditulis terakhir dengan master BARU.
- Batas diakui: cadangan lama yang sah tetap bisa dipulihkan (rollback tidak terdeteksi); tanpa merge; tanpa Recovery Key (P3b).
- Ganti master merotasi vaultKey. Cadangan lama tetap memuat vaultKey lamanya dan tetap bisa dipulihkan dengan passphrase-nya; buat cadangan baru setelah ganti master.

## Berkas besar (record v3) dan streaming (MOLINA-H12-BACKUP)

- Format cadangan TIDAK berubah (tetap versi 1, byte identik). Yang berubah hanya batas: `MAX_RECORD_BYTES` naik dari 33 MiB menjadi 258 MiB (256 MiB blob + kepala record v3 + tag per chunk; panjang record tetap `Int`, jadi tetap di bawah 2 GiB).
- Ekspor dan pemulihan berjalan streaming dengan buffer 64 KB; HMAC diperbarui per blok. Memori tidak bergantung ukuran berkas.
- Kompatibilitas: cadangan yang memuat record > 33 MiB ditolak oleh build lama ("Ukuran record tidak wajar") dan record v3 tidak dikenali build sebelum tahap 2b. Cadangan tanpa record besar tetap terbaca di semua build.
- Opsi "database saja": `export(includeLarge = false)` melewati record v3; jumlahnya dilaporkan di `ExportResult.omitted`. `estimateBytes()` memberi ukuran cadangan sebelum ditulis.
- Pemulihan: cek ruang kosong per record sebelum menulis ke staging (`InsufficientSpaceException`); setelah HMAC lolos, record v3 diperiksa penuh (setiap chunk). Record v3 yang rusak dilewati dan dihitung pada nilai kembalian `restore()`; bila tidak ada yang bisa dipulihkan, seluruh pemulihan dibatalkan.
- Ekspor yang gagal di tengah meninggalkan berkas setengah jadi di tujuan: pemanggil wajib menghapusnya (SAF: `DocumentsContract.deleteDocument`).

## Manifest tersegel (MOLINA-H15-MANIFEST)
- `manifest.mvm` TIDAK ikut cadangan dan format `.mvault` tidak berubah. Vault hasil pemulihan membuat manifest baru dari record yang dipulihkan saat dibuka pertama kali (adopsi).
