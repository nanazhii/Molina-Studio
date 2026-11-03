# Kontrak impor lintas aplikasi ke Molina Vault (MOLINA-H13-INTENT)

Vault tetap **tanpa izin jaringan**. Pintu impor hanya untuk aplikasi Molina yang bertanda tangan sama.

## Pintu masuk
- `activity-alias` `com.molina.vault/.ImportEntry` (target: `MainActivity`).
- Dilindungi izin `com.molina.vault.permission.IMPORT` (`protectionLevel="signature"`), ditegakkan sistem Android.
  Pemanggil wajib mendeklarasikan `<uses-permission android:name="com.molina.vault.permission.IMPORT" />`
  dan dibangun dengan keystore yang sama (secret `MOLINA_KEYSTORE_B64`).
- Aksi: `com.molina.vault.action.IMPORT`. Peluncuran biasa lewat launcher tidak terpengaruh.

## Permintaan (pemanggil)
- `startActivityForResult` dengan komponen eksplisit `com.molina.vault/com.molina.vault.ImportEntry`.
- `ClipData` berisi URI `content://` (mis. FileProvider) dan `FLAG_GRANT_READ_URI_PERMISSION`. URI `file://` ditolak.
- Maksimum 500 URI diterima; 100 berkas per impor diproses (sisanya dilaporkan).

## Perilaku Vault
1. Vault terkunci: pengguna membuka dengan master password dulu.
2. Layar konfirmasi menampilkan jumlah, nama (5 pertama), total ukuran, dan ruang kosong. Tidak ada impor tanpa persetujuan.
3. Berkas <= 1 MiB disimpan v2, di atasnya streaming v3. Batas 256 MB per berkas.
4. Permintaan dibatalkan otomatis bila Vault ditinggalkan sebelum konfirmasi (fail-closed).

## Hasil (setResult RESULT_OK)
- `com.molina.vault.extra.SAVED_NAMES` : ArrayList<String>
- `com.molina.vault.extra.SAVED_SHA256`: ArrayList<String> (hex 64 karakter, sejajar dengan nama)
- `com.molina.vault.extra.SAVED_SIZES` : long[]
Hanya berkas yang benar-benar tersimpan. Pemanggil **wajib** menghitung SHA-256 berkas aslinya dan mencocokkan
sebelum menawarkan "hapus asli". Tanpa hasil atau tidak cocok: jangan hapus.
