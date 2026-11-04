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

## Pemanggil resmi: Molina Studio (MOLINA-H13-BRIDGE, tahap 5b)
- `VaultBridge` (app/.../settings) dipakai menu Penjelajah > Alat > "Simpan ke Vault".
- Sebelum mengirim: Vault harus terpasang, `checkSignatures` = SIGNATURE_MATCH, dan izin signature sudah diberikan.
- URI lewat FileProvider Studio (`ClipData` + `FLAG_GRANT_READ_URI_PERMISSION`), komponen eksplisit `.ImportEntry`.
- Hasil: Studio menghitung ulang SHA-256 berkas asli; hanya yang cocok (nama + ukuran + hash) ditawarkan untuk dihapus
  lewat dialog "Hapus permanen" yang sudah ada. Tanpa hasil atau tidak cocok: tidak ada yang dihapus.
- Berkas kunci/kredensial (SHARE-GUARD) sengaja BOLEH dikirim ke Vault karena tujuannya terenkripsi dan bertanda tangan sama.

## Code > Simpan ke Vault (MOLINA-H13-CODE, tahap 5c)
- Menu titik tiga Code mengirim berkas tab aktif lewat `VaultBridge` yang sama. Hanya untuk tab yang sudah tersimpan
  di disk dan tidak berstatus dirty (Vault membaca dari disk, bukan dari editor).
- Hasil hanya dilaporkan lewat Toast. Code tidak menghapus berkas asli.
