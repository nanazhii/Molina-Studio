# HANDOFF: Refactor Molina-Studio ke modular ketat
Diperbarui: 18 Nov 2026. HEAD remote saat dokumen ditulis: 760ecfd.

## 1. Cara kerja (WAJIB)
- Bahasa Indonesia, profesional, teknis, langsung ke solusi.
- Repo resmi: https://github.com/nanazhii/Molina-Studio (clone/pull dulu setiap butuh source; clone dengan nama berstrip terbukti jalan).
- Path perangkat: /sdcard/Molina-Studio (Molina Terminal), /storage/emulated/0/Molina_Studio (Android). Perangkat uji Android 8.0 (API 26), arm64-only.
- Build HANYA lewat GitHub Actions CI. Sandbox Claude tidak punya Android SDK/kotlinc/Maven, jadi kode TIDAK bisa dikompilasi lokal; yang bisa dijalankan lokal hanya gerbang Python (module_gate, pkg_graph, dup_gate). Selalu jujur bahwa kompilasi diverifikasi CI.
- Modifikasi file lewat skrip Python 3 here-doc (python3 << 'PYEOF'), siap-copy, IDEMPOTENT (cek dulu sebelum menulis), dan uji dua kali di klon sebelum diberikan ke pengguna.
- Commit: Conventional Commits. Perubahan menyentuh Gradle/CI/kode: TANPA [skip ci]. Hanya dokumentasi: [skip ci]. progres.md ada di root repo (dilacak git).
- Setiap akhir tugas wajib: (a) pemeriksaan dampak (Gradle, Manifest, JNI mpv), (b) snippet pembaruan progres.md, (c) saran proaktif branding/fungsionalitas bila relevan.
- Pola kerja per langkah: pull klon, telaah data nyata (grep, pkg_graph, dup_gate), tulis skrip, uji dari keadaan bersih lalu jalan kedua, serahkan skrip + perintah commit, tunggu hasil CI pengguna.

## 2. Tujuan dan prinsip (dari pengguna)
Repo baru dengan arsitektur awal berantakan. Target: modular ketat agar suatu hari tiap modul bisa dipisah menjadi aplikasi sendiri (mis. Molina Cinema, Molina Terminal) tanpa bergantung pada modul lain.
1. Mudah dipelihara jangka panjang.
2. Perbaikan di satu modul tidak menyentuh modul lain.
3. Hal yang dipakai bersama lewat kontrak API atau core terpusat, tidak tersebar.
4. Tidak ada god object / spaghetti code.
5. Bug di satu modul tidak perlu menyentuh modul lain karena semua lewat kontrak.
6. Kode bersama dipusatkan di satu tempat; update cukup sekali.
Ukuran keberhasilan: modul fitur disalin ke proyek kosong bersama core/* harus ter-build dan jalan sendiri.

## 3. Aturan dan gerbang CI (di .github/workflows/build.yml)
- scripts/module_gate.py: lapisan app > feature > engine > core. core hanya core; feature hanya core (tidak ada feature->feature atau feature->engine); engine ke core + ENGINE_LINKS. Utang lama dicatat di KNOWN_DEBT.
- scripts/pkg_graph.py --max-cycles N: siklus dua arah antar paket DI DALAM app (hanya membaca import com.molina.studio.<paket>). Ratchet: turunkan N setiap siklus berkurang.
- scripts/dup_gate.py --max-blocks B --max-literals L: duplikasi blok kode (8 baris identik) dan literal lintas unit (modul Gradle; untuk app, paket tingkat atas). Ratchet SSOT. Deterministik (md5).
- Gerbang lain yang sudah ada: offline_gate, bundle_gate, secret_gate, telemetry_gate, dan tes JVM (scripts/run_jvm_tests.sh, butuh kotlinc, jalan di workflow jvm-tests).

## 4. Angka gerbang di HEAD 760ecfd (remote)
- module_gate: OK, 15 modul, 21 dependensi, 1 utang dikenal (feature:code -> feature:preview; lihat 5a).
- pkg_graph: 12 siklus (ratchet 12): automation<->packages, automation<->settings, cinema<->download, cinema<->settings, cinema<->system, cinema<->terminal, code<->settings, code<->system, code<->terminal, log<->settings, settings<->terminal, stream<->system.
- dup_gate: blok=6, literal=91 (ratchet 6/91).
- app/ masih 186 berkas Kotlin di paket: cinema 48, settings 46, terminal 39, code 11, download 8, stream 7, system 5, automation 5, cmd 4, packages 3, log 3 (BugReport, LogScreen, LogNav), (root) 7.
- Hub terburuk: settings mengimpor terminal:24, cinema:23, code:19, stream:13, packages:12, automation:10, system:5, log:3. terminal mengimpor cinema:19, settings:6, code:3. system mengimpor stream:2, terminal:2, cinema:1, code:1.

## 5. Peta modul Gradle sekarang
app, core:{api, common, exec, log, security, storage, ui}, feature:{code, mpv, preview}, engine:newpipe, vault, terminal-emulator, terminal-view. Modul native/JNI mpv ada di feature:mpv dan folder native/.
- core:api: HANYA kontrak (interface + data). Berisi MolinaServices, TerminalGateway, RootfsState. TerminalGateway punya: rootfsDir(), refreshRootfs(), rootfsState, workspaceDir, fullStorageAccess, activeFullAccess(), hasActiveSession(), isFullscreenProgramActive(), send(), paste() (PasteResult), pasteRaw() (tempel tanpa cek layar penuh, perilaku asli Penjelajah), newSession(dir, nama), maxSessions, selectByStartDir(). Pemakaian: MolinaServices.terminal.
- core:log (baru, langkah 3a): LogBus, LogEntry, LogServer, LogClient, LogFileWriter, LogFilter, LogFormat, LogcatConfig, ReportId (kini publik), AnrWatchdog; paket com.molina.core.log. LogFileWriter.attach(context, MolinaPaths) (MolinaPaths disuntik dari AppPaths di MainActivity).
- core:exec: JVM murni. Berisi ProotHostEnv (langkah 4a): satu-satunya pembangun env HOST untuk libproot.so (map/asArray/tmpDir, konstanta PROOT_LIB, LOADER_LIB, TMP_DIR_NAME). Ada tes scripts/core-tests/ProotHostEnvTests.kt + run_proot_env.sh.
- feature:preview: pratinjau mandiri (molina preview, nano Alt+P). Dipakai app lewat com.molina.studio.preview.{PreviewHost, PreviewNav, ExplorerPreview}. Salinan logika render dari Code SENGAJA (mandiri tanpa CodeSession).

## 6. Riwayat selesai (CI hijau)
- 2b-3a: paket code memakai TerminalGateway.
- 2b-3b (135d93c): settings memakai TerminalGateway untuk rootfs/sesi/folder kerja (+pasteRaw).
- 3a (51e3ef4): infrastruktur log pindah ke :core:log (siklus 13 -> 12).
- dup_gate (45006b2): gerbang SSOT di CI.
- 4a (760ecfd): ProotHostEnv menggantikan env PRoot di 5 tempat (DebianArchive, ProotScriptExecutor, HealthCheck, YtdlpRunner, TerminalSessions) + 2 pembanding nama folder; ratchet pkg_graph 12, dup_gate 6/91.

## 7. Sedang menunggu pengguna
- 5a: hapus dependensi mati feature:code -> feature:preview (tidak ada impor com.molina.preview atau com.molina.studio.preview di feature/code) dan kosongkan KNOWN_DEBT di module_gate. Skrip sudah diberikan; hasil CI belum dikabarkan. Setelah hijau: module_gate jadi 20 dependensi, 0 utang.

## 8. Fase berikutnya (urutan usulan)
- 5b: modul :core:preview untuk render Markdown/JSON/HTML bersama (PreviewRender, MdUi, PreviewContent, tampilan) dipakai feature:code (PreviewPane/PreviewState) dan feature:preview (PreviewViews/PreviewModel). Melunasi duplikasi 88 jendela. Risiko kompilasi: ubah visibilitas internal -> publik, warna/dimensi (CodeColors/CodeDimens vs PreviewColors/PreviewDimens) perlu dibuat konsisten. Tidak boleh feature -> feature.
- 3b: pecah paket system. Bagian murni (AndroidProcessOps, TaskMonitor, BtopMonitor, MpvControl; hanya impor android.*) ke :core:system. SystemRefresh (mengenal cinema.MemoryRefresh, code.ProotScriptExecutor, stream.StreamCache/YtdlpRunner, terminal.TerminalService/TerminalSessions) tetap di app sampai ada kontrak. Pemakai luar: AndroidProcessOps(3), TaskActions(2), MpvControl(2), TaskMonitor, SystemRefresh, BtopSnapshot, BtopMonitor. Verifikasi dulu bahwa keempat berkas itu benar-benar bebas dependensi app.
- Putus terminal -> cinema (19 referensi): kontrak MediaGateway di core:api (pola TerminalGateway), implementasi di cinema, wiring di app (composition root). Menghapus siklus cinema<->terminal.
- 2c SettingsSection: kontrak di core:api; setiap fitur memiliki halaman setelannya; settings hanya menjadi host. Pemakaian TerminalPrefs di settings ada 75, plus CrashReporter, TerminalLook, SystemStats, PromptTheme, BannerStyle, RootfsAdmin. Memutus paling banyak siklus (automation, cinema, code, log, terminal dengan settings).
- BugReport/LogScreen (masih di app/log): BugReport bergantung settings.{AptHistory, BackupManager, HealthCheck, hubStatusRows}, terminal.{CrashReporter, RootfsManager}, automation.AutoPrefs. Balik arah: kontrak "kontributor laporan" di core:api, tiap fitur menyumbang bagiannya; lalu BugReport/LogScreen pindah ke :feature:log. Menghapus siklus log<->settings.
- Pecah paket app menjadi modul Gradle (urutan: cinema bersama feature:mpv, terminal, stream, download, automation, cmd, packages; paket code di app, 11 berkas, digabung ke feature:code). Akhirnya app = shell tipis (Manifest, wiring, navigasi). Menyentuh settings.gradle.kts, build.gradle.kts per modul, AndroidManifest.xml (activity/service pindah), dan JNI mpv (.so, CMake, abiFilters arm64) untuk cinema.
- Gerbang tambahan: api_surface_gate (snapshot simbol publik core/api; kontrak berubah harus sengaja), tes kontrak per modul dengan fake dari core/api, uji build tiap modul sendirian.
- Kontrak aman-gagal: MolinaServices punya implementasi no-op default; batas kontrak dibungkus Result/runCatching; deklarasi internal secara default di modul fitur; antar-modul pakai implementation, bukan api.
- Pembersihan SSOT sisa (dup_gate): literal molina_ui (5 modul), molina_open_tab (4), "/sdcard/", "yyyyMMdd-HHmmss", "libproot.so" (ganti dengan ProotHostEnv.PROOT_LIB). Blok duplikat sisa: Win7Aero (core:ui) vs CodeBackOrb (feature:code), FileOps (cinema) vs TrashBin (core:common), DebianArchive vs ProotScriptExecutor, HealthCheck vs YtdlpRunner.

## 9. Catatan dan jebakan
- pkg_graph hanya mengenali import com.molina.studio.<paket> di app/src/main/java/com/molina/studio. Pencarian dependensi wajib mencakup com.molina.studio.preview (paket ini ada di feature/preview, bukan di app) dan com.molina.core.*.
- Anomali yang perlu diverifikasi: core/ui berisi berkas berpaket com.molina.studio.cinema (mis. Win7Aero.kt). Nama paket tidak sejalan dengan modul; pertimbangkan rename ke com.molina.core.ui dan cek bentrok.
- Tiap langkah dimulai dengan git pull dan memastikan commit sebelumnya benar-benar ada di remote (pernah terjadi commit lokal belum ter-push).
- Setelah sebuah langkah hijau, ketatkan ratchet (pkg_graph --max-cycles, dup_gate --max-blocks/--max-literals) di build.yml pada langkah berikutnya.
- Hindari mengubah perilaku saat refactor; penggantian harus mekanis dan isi env/teks Toast tetap sama. Bila perilaku berbeda tak terhindarkan, tambahkan jalur terpisah di kontrak (contoh: pasteRaw).

## 10. Saran branding / produk (proaktif)
- Siapkan token tema di core:ui (warna aksen, tipografi) dengan satu aksen per produk supaya Molina Cinema (misal emas hangat di latar gelap ala home theater) dan Molina Terminal bisa beridentitas visual berbeda tanpa menyentuh logika.
- LogBus di core:log bisa menjadi satu-satunya jalur pelacakan aktivitas pemutaran mpv: Cinema mengirim event lewat LogSink tanpa mengenal layar log.

## 11. Template progres.md (lokal)
Tambahkan baris seperti: "  - [ ] <kode langkah> (menunggu CI): <ringkasan>" dan ubah menjadi "[x] ... CI hijau" saat hijau. Skrip pembaruan dibuat idempotent.
