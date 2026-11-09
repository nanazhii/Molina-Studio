# Third-party notices

- `terminal-emulator/` dan `terminal-view/`: disalin dari https://github.com/termux/termux-app
  tag v0.118.0 (commit 6e2689f55295fa444be8ac8592c527c2c5ef3253). Berasal dari
  Android Terminal Emulator (Apache-2.0) dengan modifikasi Termux. Lisensi terminal-emulator dan terminal-view adalah Apache-2.0; salinannya ada di folder masing-masing.
  Paket Java diganti ke `com.molina.terminal` / `com.molina.terminal.view` dan library native ke `libmolina_pty` (rebranding); atribusi, lisensi, dan kode asal dipertahankan.

- `app/src/main/jniLibs/arm64-v8a/libproot*.so`: PRoot prebuilt dari https://github.com/green-green-avk/build-proot-android (commit 01f83b88, paket proot-android-aarch64.tar.gz). PRoot berlisensi GPL-2.0-or-later.
- Rootfs Debian trixie-slim arm64 diunduh saat runtime dari https://github.com/debuerreotype/docker-debian-artifacts (commit 9c4818e).

- `app/src/main/assets/fonts/`: JetBrains Mono (JetBrainsMono-Regular.ttf, https://github.com/JetBrains/JetBrainsMono, SIL OFL 1.1, salinan lisensi OFL-JetBrainsMono.txt). Dipakai sebagai font Terminal, tanpa modifikasi. OFL mengizinkan penyertaan dan distribusi bersama aplikasi.

- Arsip di Penjelajah (`app/`): `org.apache.commons:commons-compress:1.27.1` (dan dependensinya commons-io, commons-lang3, commons-codec), `net.lingala.zip4j:zip4j:2.11.5` — semuanya Apache-2.0 dan dari Maven Central, tanpa modifikasi. `org.tukaani:xz:1.10` (XZ for Java, https://tukaani.org/xz/java.html, lisensi 0BSD/domain publik) dipakai untuk tar.xz, xz, dan 7z (LZMA2). Format RAR/unrar TIDAK disertakan (syarat lisensi khusus); format langka direncanakan lewat `7z` di Debian (p7zip-full, dipasang pengguna sendiri). [ARCHIVE1]

- `feature/mpv` (libmpv.so): dibangun CI dari buildscripts mpv-android (MIT, salinan `feature/mpv/LICENSE-mpv-android`). Komponen: mpv (GPL-2.0-or-later), FFmpeg (dibangun dengan --enable-gpl --enable-version3, efektif GPL-3.0-or-later), dav1d (BSD-2-Clause), libass (ISC), libplacebo (LGPL-2.1-or-later). Versi dan commit dipin di `native/prebuilt.env` dan `native/pins.env`. [LICENSE-AUDIT]
- NewPipeExtractor (TeamNewPipe): GPL-3.0-or-later, dipakai tanpa modifikasi lewat JitPack, hash dipin di `scripts/newpipe/pins.sha256`.
- Lisensi kode Molina: GPL-3.0-or-later (lihat `LICENSE`).
- `app/src/main/jniLibs/arm64-v8a/lib7zz.so`: 7-Zip 26.05 (Igor Pavlov), biner statis `7zzs` dari rilis resmi https://github.com/ip7z/7zip (7z2605-linux-arm64.tar.xz), DIMODIFIKASI [7ZIP-SECCOMP]: tiga instruksi `svc #0` untuk `set_robust_list` diganti `nop` (filter seccomp Android 8 memblokir syscall itu), skrip di `scripts/sevenzip/patch_7zzs.py`; hash dipin di `scripts/sevenzip/pins.sha256`. Lisensi: GNU LGPL, sebagian BSD 2/3-clause, dan sebagian LGPL dengan "unRAR license restriction" (dekoder RAR; tidak boleh dipakai membuat kompresor RAR). Teks lisensi dari paket resmi disertakan di `app/src/main/assets/licenses/7zip-License.txt`. [7ZIP-BUNDLE]
