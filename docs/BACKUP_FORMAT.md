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
