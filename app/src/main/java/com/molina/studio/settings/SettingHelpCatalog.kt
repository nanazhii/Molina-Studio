package com.molina.studio.settings

import com.molina.studio.packages.StarterProfile
import com.molina.studio.ui.components.ShieldBadge

/**
 * [INFO-CATALOG] Satu-satunya tempat teks keterangan (i) setelan. Baris UI cukup memakai
 * `onInfo = { SettingHelp.show(HelpKey.X) }` atau `HelpKey.X.topic` untuk dialog.
 * Menambah keterangan baru: tambah konstanta di [HelpKey] lalu cabangnya di [SettingHelpCatalog.of];
 * `when` yang lengkap membuat kompilator menolak kunci tanpa teks.
 * Keterangan dinamis (isi bergantung data saat berjalan) tetap dibuat di tempatnya.
 * [HelpKey.group] dipakai halaman Keterangan setelan (Sistem > Info) untuk pengelompokan.
 */
internal enum class HelpKey(val group: String) {
    // Pemutar, Stream, dan Unduhan
    PLAYER_AUDIO_ONLY("Pemutar"),
    PLAYER_SPECTRUM("Pemutar"),
    PLAYER_BG_DATA_SAVER("Pemutar"),
    PLAYER_NEW_INTENT_REPLACE("Pemutar"),
    STREAM_LOCK_INDONESIAN("Pemutar"),
    STREAM_ORIGINAL_TITLES("Pemutar"),
    STREAM_THUMBNAILS("Pemutar"),
    DOWNLOAD_ADD_TO_CINEMA("Pemutar"),
    // Jaringan dan Keamanan
    SHIELD_ENABLED("Jaringan dan Keamanan"),
    NET_BLOCK_PLAYLIST_RTMP("Jaringan dan Keamanan"),
    NET_BLOCK_INSECURE_IPTV("Jaringan dan Keamanan"),
    NET_SCHEME_ALLOWLIST("Jaringan dan Keamanan"),
    NET_ALLOW_LAN("Jaringan dan Keamanan"),
    PRIVACY_SECURE_SCREEN("Jaringan dan Keamanan"),
    // Otomasi
    AUTO_UPDATE_CHECK("Otomasi"),
    AUTO_TOOL_VERSION_CHECK("Otomasi"),
    AUTO_DAILY_BACKUP("Otomasi"),
    // Code
    CODE_NATIVE_RENDERER("Editor Code"),
    CODE_HTML_WEBVIEW("Editor Code"),
    CODE_OFFLINE("Editor Code"),
    CODE_SCRIPT_CHECK("Editor Code"),
    CODE_SHEBANG_HINT("Editor Code"),
    // yt-dlp
    STREAM_YTDLP_ENABLE("Paket"),
    STREAM_YTDLP_MANAGE("Paket"),
    // Debian apt
    APT_UPDATE_UPGRADE("Paket"),
    APT_CLEANUP("Paket"),
    APT_INSTALL_STARTER("Paket"),
    // Dialog
    CLEANUP_CENTER("Pembersihan dan Diagnostik"),
    DIAGNOSTICS("Pembersihan dan Diagnostik"),
    // [INFO-CENTRAL] baris Pemutar dan Paket yang sebelumnya belum punya (i)
    PLAYER_RESUME("Pemutar"),
    PLAYER_HWDEC("Pemutar"),
    PLAYER_TV_MODE("Pemutar"),
    PLAYER_YT_AUDIO_LANG("Pemutar"),
    PLAYER_LOCAL_LANG("Pemutar"),
    PLAYER_BG_MODE("Pemutar"),
    PLAYER_PERF_MODE("Pemutar"),
    PACKAGES_MANAGE_DEBIAN("Paket"),
    PACKAGES_EXTRA_TOOLS("Paket"),
    PACKAGES_BUNDLE("Paket"),
    // Debugging
    LOG_FILE_WRITE("Debugging dan Log"),
    // [INFO-DEBUG] baris halaman Debugging yang sebelumnya belum punya keterangan
    DEBUG_LOG_LEVEL("Debugging dan Log"),
    DEBUG_KEY_LOGGING("Debugging dan Log"),
    DEBUG_PLUGIN_ERRORS("Debugging dan Log"),
    DEBUG_CRASH_NOTIF("Debugging dan Log"),
    DEBUG_CLI_CLIPBOARD("Debugging dan Log"),
    DEBUG_REDACT_TRANSCRIPT("Debugging dan Log"),
    DEBUG_LOGCAT_REPORTER("Debugging dan Log"),
    DEBUG_LOGCAT_ZIP("Debugging dan Log"),
    DEBUG_MPV_LOG_FILE("Debugging dan Log"),
    DEBUG_MPV_LOG_LEVEL("Debugging dan Log"),
    // [INFO-TERMINAL] Terminal I/O, Terminal View, Tampilan Molina, Monitor
    IO_SOFT_KEYBOARD("Terminal I/O"),
    IO_SOFT_KEYBOARD_NO_HW("Terminal I/O"),
    IO_FULL_STORAGE("Terminal I/O"),
    IO_PASTE_SHAPE("Terminal I/O"),
    VIEW_MARGIN("Terminal View"),
    VIEW_COMPACT_MONITOR("Terminal View"),
    LOOK_FONT_SIZE("Tampilan Molina"),
    LOOK_COLOR_SCHEME("Tampilan Molina"),
    LOOK_BANNER("Tampilan Molina"),
    LOOK_PROMPT_THEME("Tampilan Molina"),
    MONITOR_RGB_FRAME("Monitor Sistem"),
}

internal val HelpKey.topic: HelpTopic get() = SettingHelpCatalog.of(this)

internal object SettingHelpCatalog {
    fun of(key: HelpKey): HelpTopic = when (key) {
        // ---- Pemutar, Stream, dan Unduhan
        HelpKey.PLAYER_AUDIO_ONLY -> HelpTopic(
            "Audio saja untuk video",
            what = "Video diputar suaranya saja, tanpa gambar: hemat kuota, baterai, dan memori di perangkat lemah.",
            ifOff = "Video tampil lagi.",
            whenApplies = "Saat pemutar dibuka berikutnya.",
        )
        HelpKey.PLAYER_SPECTRUM -> HelpTopic(
            "Visualizer spektrum",
            what = "Batang spektrum frekuensi yang bergerak mengikuti musik pada layar Sedang Diputar. Tombol Visualizer di pemutar memakai setelan yang sama.",
            ifOff = "Menghemat CPU dan baterai di perangkat lemah.",
            whenApplies = "Pada lagu berikutnya yang dibuka.",
            note = "Bawaannya mati pada mode Performa Hemat sampai Anda mengaturnya sendiri.",
        )
        HelpKey.PLAYER_BG_DATA_SAVER -> HelpTopic(
            "Hemat data di latar",
            what = "Saat pemutaran lanjut di latar, hentikan pemuatan video dan putar suaranya saja.",
            note = "Video kembali saat pemutar dibuka lagi.",
        )
        HelpKey.PLAYER_NEW_INTENT_REPLACE -> HelpTopic(
            "Putar file baru langsung",
            what = "Saat Anda membuka file baru ketika pemutar masih berjalan di latar belakang, file itu langsung diputar menggantikan daftar putar.",
            ifOff = "File ditambahkan di akhir daftar.",
        )
        HelpKey.STREAM_LOCK_INDONESIAN -> HelpTopic(
            "Kunci hasil ke bahasa Indonesia",
            what = "Utamakan judul berbahasa Indonesia dan sembunyikan judul yang jelas berbahasa Inggris/asing.",
            note = "Bila hasilnya terlalu sedikit, semuanya tetap ditampilkan.",
        )
        HelpKey.STREAM_ORIGINAL_TITLES -> HelpTopic(
            "Judul asli YouTube",
            what = "Pakai judul persis seperti yang ditulis pengunggah, bukan terjemahan otomatis YouTube.",
            note = "Pencarian jadi beberapa detik lebih lambat karena judul tiap hasil diambil ulang.",
        )
        HelpKey.STREAM_THUMBNAILS -> HelpTopic(
            "Tampilkan thumbnail",
            what = "Gambar kecil pada hasil pencarian diambil dari server gambar YouTube.",
            ifOff = "Tidak ada permintaan gambar dan durasi tampil sebagai teks. Hemat data dan privasi.",
        )
        HelpKey.DOWNLOAD_ADD_TO_CINEMA -> HelpTopic(
            "Unduhan masuk ke Cinema",
            what = "Setelah unduhan selesai, video atau audio yang berhasil otomatis muncul di Terakhir diputar Molina Cinema agar bisa diputar dengan satu ketukan.",
        )
        // ---- Jaringan dan Keamanan
        HelpKey.SHIELD_ENABLED -> HelpTopic(
            "Molina Shield",
            icon = { ShieldBadge() },
            what = "Semua lalu lintas http/https pemutar lewat filter lokal: redirect, segmen HLS, dan playlist ke alamat privat atau loopback diblokir, termasuk DNS yang diarahkan ke alamat privat.",
            ifOff = "Filter lokal tidak dipakai, sehingga lalu lintas pemutar tidak lagi disaring oleh Molina Shield.",
            whenApplies = "Berlaku penuh setelah Refresh Pemutar atau membuka ulang pemutar.",
            note = "Tidak mencakup rtmp/rtsp. Unduhan yt-dlp hanya diperiksa pada URL awal (redirect tidak tercakup).",
        )
        HelpKey.NET_BLOCK_PLAYLIST_RTMP -> HelpTopic(
            "Blokir rtmp di dalam playlist",
            what = "Playlist (m3u) tidak bisa menyuruh pemutar membuka rtmp ke alamat lain.",
            ifOff = "Kanal rtmp di dalam daftar IPTV tidak lagi diblokir.",
            whenApplies = "Setelah Refresh Pemutar atau membuka ulang pemutar.",
            note = "Bila diaktifkan, kanal rtmp di dalam daftar IPTV akan gagal diputar. Tautan rtmp yang Anda buka langsung tetap jalan. rtsp belum tercakup.",
        )
        HelpKey.NET_BLOCK_INSECURE_IPTV -> HelpTopic(
            "Blokir stream tidak aman di IPTV",
            what = "Menyembunyikan dan menolak saluran IPTV yang memakai http tanpa enkripsi, alamat IP mentah, atau kredensial di URL (tanda TIDAK AMAN).",
            ifOff = "Saluran bertanda TIDAK AMAN tidak lagi disembunyikan atau ditolak.",
            whenApplies = "Saat daftar saluran dibuka ulang.",
            note = "Banyak saluran daerah memakai alamat seperti ini, jadi daftar bisa jauh lebih pendek. Tautan yang Anda buka langsung tidak terpengaruh.",
        )
        HelpKey.NET_SCHEME_ALLOWLIST -> HelpTopic(
            "Daftar putih skema",
            what = "Hanya http, https, rtmp(s), rtsp, dan berkas lokal yang diizinkan.",
            note = "concat:, edl://, fd://, data: dan sejenisnya selalu ditolak, begitu pula alamat perangkat sendiri (127.x) dan metadata cloud (169.254.x).",
        )
        HelpKey.NET_ALLOW_LAN -> HelpTopic(
            "Izinkan jaringan lokal (LAN)",
            what = "Mengizinkan akses ke jaringan rumah untuk NAS, Jellyfin, DLNA (192.168.x.x, 10.x.x.x, nama .local).",
            ifOff = "Jaringan lokal ditolak. Ini bawaan supaya tautan jahat tidak bisa memakai aplikasi untuk memindai jaringan rumah Anda.",
        )
        HelpKey.PRIVACY_SECURE_SCREEN -> HelpTopic(
            "Layar privat",
            what = "Menyembunyikan isi layar dari tangkapan layar, perekam layar, dan daftar aplikasi terbaru, serta menolak sentuhan saat tertutup overlay aplikasi lain.",
            ifOff = "Screenshot dan aplikasi filter cahaya biru bisa dipakai. Matikan bila Anda memerlukannya.",
            note = "Berlaku untuk Terminal dan Pengaturan. Pemutar video tidak terpengaruh.",
        )
        // ---- Otomasi
        HelpKey.AUTO_UPDATE_CHECK -> HelpTopic(
            "Cek pembaruan otomatis",
            what = "Memperbarui angka pembaruan program Debian dan yt-dlp di menu Paket tanpa membuka dialog.",
            whenApplies = "Paling sering sekali per 24 jam dan hanya lewat Wi-Fi.",
            note = "Tanpa notifikasi dan tidak memasang apa pun.",
        )
        HelpKey.AUTO_TOOL_VERSION_CHECK -> HelpTopic(
            "Periksa versi alat otomatis",
            what = "Memperbarui versi dan tanggal di kartu alat Paket.",
            whenApplies = "Sekitar sekali per 24 jam dan saat Paket dibuka.",
            note = "Hanya membaca versi: tanpa jaringan dan tanpa memasang apa pun. Tidak dijalankan bila baterai lemah.",
        )
        HelpKey.AUTO_DAILY_BACKUP -> HelpTopic(
            "Cadangan gabungan harian",
            what = "Menyimpan penanda video, snippet Terminal, dan setelan ke folder Backup.",
            whenApplies = "Sekali per hari.",
            note = "Salinan sebelumnya dijaga sebagai molina-backup.prev.json.",
        )
        // ---- Code
        HelpKey.CODE_NATIVE_RENDERER -> HelpTopic(
            "Renderer native (default)",
            what = "Judul, paragraf, daftar, tabel, kutipan, blok kode, dan tautan digambar langsung oleh aplikasi.",
            note = "CSS, JavaScript, dan gambar tidak diproses. Ringan dan tidak memuat apa pun dari jaringan.",
        )
        HelpKey.CODE_HTML_WEBVIEW -> HelpTopic(
            "Pakai WebView untuk HTML",
            what = "Menampilkan halaman HTML dengan tampilan penuh (CSS inline dan gambar data:). Cocok bila renderer native terlalu sederhana.",
            ifOff = "Berkas HTML ditampilkan dengan renderer native.",
            whenApplies = "Untuk berkas .html berikutnya yang dibuka dengan Play.",
            note = "JavaScript, jaringan, dan akses berkas tetap dimatikan, dan tautan dibuka di peramban.",
        )
        HelpKey.CODE_OFFLINE -> HelpTopic(
            "Editor, Penjelajah, dan Pratinjau bekerja offline",
            what = "Kode editor, Penjelajah, dan Pratinjau tidak membuka koneksi internet. Riwayat dan snapshot proyek tersimpan di perangkat.",
            note = "Pratinjau HTML selalu tanpa JavaScript dan tanpa jaringan, dan ini dijaga otomatis saat build. Skrip yang Anda jalankan di Debian memakai jaringan perangkat seperti biasa.",
        )
        HelpKey.CODE_SCRIPT_CHECK -> HelpTopic(
            "Pemeriksaan skrip (molina-run)",
            what = "Pemeriksaan skrip yang berlaku untuk Alt+R di nano: pembungkus heredoc tidak valid, EOF hilang, CRLF, BOM, dan shebang yang tidak cocok dengan ekstensi.",
        )
        HelpKey.CODE_SHEBANG_HINT -> HelpTopic(
            "Tips shebang dan izin eksekusi",
            what = "Sakelarnya kini ada di Settings > Script execution, bersama penanda amber per skrip.",
            note = "Berlaku juga untuk tips di nano dan saat skrip dijalankan.",
        )
        // ---- yt-dlp
        HelpKey.STREAM_YTDLP_ENABLE -> HelpTopic(
            "Aktifkan pengunduh tambahan (yt-dlp)",
            what = "Untuk TikTok, Facebook, dan situs lain yang tidak dikenali NewPipe.",
            note = "Satu resolusi memulai Debian dan Python (memori bisa naik ratusan MB sesaat), jadi bawaannya mati.",
        )
        HelpKey.STREAM_YTDLP_MANAGE -> HelpTopic(
            "Pasang, perbarui, versi, dan hapus",
            what = "Ada di kartu yt-dlp pada halaman Paket (Alat tambahan di Debian), seragam dengan Python 3 dan FFmpeg. Pembaruan juga ikut tombol Perbarui semua.",
        )
        // ---- Debian apt
        HelpKey.APT_UPDATE_UPGRADE -> HelpTopic(
            "Perbarui semua program",
            what = "Mengecek program terbaru di Debian, lalu memperbarui semuanya tanpa bertanya lagi.",
            whenApplies = "Pembaruan hanya berjalan jika pengecekan berhasil.",
            note = "Butuh internet, bisa memakai banyak kuota dan memakan beberapa menit. Bila yt-dlp punya rilis baru, ikut diperbarui setelahnya.",
        )
        HelpKey.APT_CLEANUP -> HelpTopic(
            "Bersihkan sisa program",
            what = "Menghapus program yang tidak lagi dibutuhkan dan sisa file unduhan lama.",
            note = "Aman dijalankan kapan saja dan menambah ruang kosong.",
        )
        HelpKey.APT_INSTALL_STARTER -> HelpTopic(
            "Paket dasar (Starter)",
            what = "Memasang perangkat dasar pilihan: ${StarterProfile.DISPLAY.joinToString(", ")} (dari sumber resmi Debian dan rilis resmi yt-dlp; keasliannya diperiksa otomatis).",
            whenApplies = "Sebelum memasang, seluruh program Debian diperbarui lebih dulu agar versinya mutakhir. yt-dlp dipasang otomatis setelahnya bila belum ada.",
            note = "Butuh internet dan ruang kosong minimal 1 GB; bisa memakan kuota dan beberapa menit. Yang sudah terpasang dilewati. ffmpeg tidak ikut; tambahkan dari Setelan > Paket > Alat bila perlu.",
        )
        // ---- Dialog
        // [INFO-DIALOG] Keterangan umum Pusat Pembersihan Cerdas; rincian tiap kategori lewat (i) di barisnya.
        HelpKey.CLEANUP_CENTER -> HelpTopic(
            "Pusat Pembersihan Cerdas",
            what = "Pilih kategori, lihat estimasi ruang, lalu bersihkan sekaligus: cache, sisa unduhan, laporan lama, file sementara.",
            note = "Rootfs Debian itu sendiri tidak pernah disentuh. Ketuk (i) di tiap baris untuk melihat apa yang dihapus.",
        )
        // [INFO-DIALOG] Keterangan Diagnostik dan laporan bug, dibuka lewat ikon (i) di judul dialog.
        HelpKey.DIAGNOSTICS -> HelpTopic(
            "Diagnostik dan laporan bug",
            what = "Satu pintu untuk bahan laporan masalah: pemeriksaan kesehatan, salin info debug, laporan bug (.txt), dan ZIP diagnostik. " +
                "Tombol utama membuat satu ZIP berisi ringkasan perangkat dan versi, hasil pemeriksaan, laporan bug gabungan, dan laporan crash, lalu langsung dibagikan.",
            whenApplies = "Pemeriksaan berjalan otomatis saat dialog dibuka. Tekan Periksa ulang untuk mengulang.",
            note = "URL hanya memuat skema dan host, token disamarkan.\n\n" +
                "Setiap laporan punya ID (MOL-tanggal-jam-kode) yang sama di nama berkas dan isinya. ZIP hanya 3 yang terbaru dipertahankan di folder Export.",
        )
        // ---- Debugging
        HelpKey.LOG_FILE_WRITE -> HelpTopic(
            "Simpan logcat ke berkas",
            what = "Logcat aplikasi ditulis ke folder Molina/Logs/Logcat sebagai logcat.log, bergulir 1 MB x 4 berkas. " +
                "Berguna untuk laporan bug karena isinya tetap ada setelah aplikasi ditutup.",
            ifOff = "Tidak ada berkas logcat yang ditulis dan laporan post-mortem (setelah crash) tidak dibuat. Halaman Log di aplikasi tetap bekerja.",
            note = "URL dan token disamarkan karena folder Molina terbaca aplikasi lain. Berkas lama tidak dihapus saat dimatikan; ukuran dan tombol Hapus berkas log ada di bawah saklar.",
        )
        // ---- [INFO-DEBUG] Debugging: baris yang sebelumnya belum punya keterangan
        HelpKey.DEBUG_LOG_LEVEL -> HelpTopic(
            "Log Level",
            what = "Seberapa banyak log terminal yang dikirim ke logcat: Off, Normal (error, peringatan, info), Debug (ditambah pesan debug), atau Verbose (paling terperinci).",
            ifOff = "Off berarti tidak ada log terminal ke logcat.",
            note = "Debug dan Verbose memperbanyak isi log. Naikkan hanya saat melacak masalah.",
        )
        HelpKey.DEBUG_KEY_LOGGING -> HelpTopic(
            "Key logging tampilan terminal",
            what = "Logcat mencatat tombol yang ditekan di tampilan terminal. Berguna untuk melacak masalah input keyboard.",
            ifOff = "Tombol tidak dicatat di logcat. (Default)",
            note = "Matikan saat mengetik data sensitif, karena log bisa ikut dibagikan sebagai laporan bug.",
        )
        HelpKey.DEBUG_PLUGIN_ERRORS -> HelpTopic(
            "Notifikasi error plugin",
            what = "Molina belum memiliki plugin; nilai ini disimpan untuk fase mpv dan Code.",
        )
        HelpKey.DEBUG_CRASH_NOTIF -> HelpTopic(
            "Notifikasi laporan crash",
            what = "Menampilkan notifikasi saat Molina mencatat laporan crash baru.",
            ifOff = "Tidak ada notifikasi. Laporan tetap bisa dilihat di Crash Reporter.",
        )
        HelpKey.DEBUG_CLI_CLIPBOARD -> HelpTopic(
            "Akses clipboard dari CLI",
            what = "Mengizinkan perintah molina clip membaca dan menulis clipboard Android dari Debian.",
            ifOff = "Perintah molina clip tidak bisa mengakses clipboard.",
            note = "Semua program di Debian bisa memakainya, jadi biarkan mati bila tidak perlu.",
        )
        HelpKey.DEBUG_REDACT_TRANSCRIPT -> HelpTopic(
            "Samarkan token saat menyimpan transkrip",
            what = "Saat transkrip terminal disimpan, URL hanya menyisakan skema dan host, dan token=, password=, serta sejenisnya diganti ***.",
            ifOff = "Transkrip disimpan apa adanya, URL dan token tetap utuh. (Default)",
            note = "Folder /sdcard/Molina terbaca aplikasi lain.",
        )
        HelpKey.DEBUG_LOGCAT_REPORTER -> HelpTopic(
            "Logcat Reporter",
            what = "Menyalurkan logcat aplikasi ke halaman Log (sumber: logcat), digabung oleh LogGrouping.",
            ifOff = "Halaman Log tidak menerima logcat aplikasi.",
        )
        HelpKey.DEBUG_LOGCAT_ZIP -> HelpTopic(
            "Sertakan logcat di ZIP diagnostik",
            what = "logcat.log dan post-mortem terbaru ikut dimasukkan ke ZIP laporan bug. (Default)",
            ifOff = "ZIP diagnostik tidak memuat logcat. Berkas lokal tetap ada.",
            note = "Butuh Simpan logcat ke berkas dalam keadaan menyala.",
        )
        HelpKey.DEBUG_MPV_LOG_FILE -> HelpTopic(
            "Simpan log mpv ke berkas",
            what = "Pesan mesin mpv ditulis ke Molina/Logs/Mpv/mpv.log, bergulir 1 MB x 3 berkas. Formatnya sama dengan logcat sehingga waktunya bisa disejajarkan. (Default)",
            ifOff = "Log mesin mpv tidak ditulis ke berkas.",
            note = "URL dan token disamarkan karena folder Molina terbaca aplikasi lain.",
        )
        HelpKey.DEBUG_MPV_LOG_LEVEL -> HelpTopic(
            "Tingkat detail log mpv",
            what = "Batas minimum pesan mpv yang ditulis ke mpv.log. Ketuk untuk berganti: Error, Warn, Info, Verbose. Bawaan: Warn.",
            note = "Verbose cepat membesar; berkas dibatasi 1 MB x 3.",
        )
        // ---- [INFO-TERMINAL] Terminal I/O
        HelpKey.IO_SOFT_KEYBOARD -> HelpTopic(
            "Keyboard layar aktif",
            what = "Mengaktifkan keyboard layar untuk mengetik di terminal. (Default)",
            ifOff = "Keyboard layar dimatikan; mengetik lewat keyboard fisik.",
        )
        HelpKey.IO_SOFT_KEYBOARD_NO_HW -> HelpTopic(
            "Keyboard layar hanya tanpa keyboard fisik",
            what = "Keyboard layar hanya aktif bila tidak ada keyboard fisik yang terhubung.",
            ifOff = "Keyboard layar tetap aktif walau keyboard fisik terhubung. (Default)",
        )
        HelpKey.IO_FULL_STORAGE -> HelpTopic(
            "Akses penyimpanan penuh",
            what = "Seluruh /sdcard terlihat di dalam Debian.",
            ifOff = "Hanya /sdcard/Molina yang terlihat di dalam Debian. (Default)",
            whenApplies = "Di sesi terminal baru.",
        )
        HelpKey.IO_PASTE_SHAPE -> HelpTopic(
            "Deteksi bentuk paste",
            what = "Kode Python telanjang atau heredoc tanpa penutup ditawari pembungkus python3 sebelum ditempel. (Default)",
            ifOff = "Teks ditempel apa adanya tanpa pemeriksaan bentuk.",
        )
        // ---- [INFO-TERMINAL] Terminal View
        HelpKey.VIEW_MARGIN -> HelpTopic(
            "Penyesuaian margin terminal",
            what = "Menyesuaikan margin tampilan terminal (Terminal Margin Adjustment). (Default)",
            ifOff = "Penyesuaian margin dimatikan.",
        )
        HelpKey.VIEW_COMPACT_MONITOR -> HelpTopic(
            "Monitor ringkas",
            what = "Mematikan grafik di belakang chip CPU/RAM pada header terminal dan mempersempit chip. Cocok untuk layar kecil.",
            ifOff = "Chip CPU/RAM menampilkan grafik 24 detik. (Default)",
            note = "Pada layar lebih sempit dari 340 dp, mode ringkas selalu aktif otomatis dan saklar ini tidak mengubah tampilan.",
        )
        // ---- [INFO-TERMINAL] Tampilan Molina
        HelpKey.LOOK_FONT_SIZE -> HelpTopic(
            "Ukuran font",
            what = "Ukuran huruf terminal dalam sp. Contoh prompt di bawah penggeser mengikuti ukuran yang dipilih.",
            note = "Di terminal, cubit dua jari (pinch) juga mengubah ukuran ini, per 1sp.",
        )
        HelpKey.LOOK_COLOR_SCHEME -> HelpTopic(
            "Skema warna terminal",
            what = "Warna teks, latar, dan kursor terminal: Noir Emerald, Cinema Violet, atau Amber CRT.",
            whenApplies = "Langsung pada sesi yang terbuka.",
        )
        HelpKey.LOOK_BANNER -> HelpTopic(
            "Banner sesi",
            what = "Banner ringkas (bingkai) yang tampil di awal sesi terminal.",
            ifOff = "Sesi langsung ke prompt.",
            whenApplies = "Di sesi baru dan saat tombol CLEAR ditekan.",
        )
        HelpKey.LOOK_PROMPT_THEME -> HelpTopic(
            "Tema warna prompt",
            what = "Warna prompt terminal untuk direktori home dan direktori lain.",
            whenApplies = "Pada prompt berikutnya (tekan Enter di terminal).",
        )
        // ---- [INFO-TERMINAL] Monitor Sistem
        HelpKey.MONITOR_RGB_FRAME -> HelpTopic(
            "Animasi frame RGB",
            what = "Bingkai berwarna yang bergerak. Saat menyala, tiap halaman bisa dimatikan sendiri di bawah saklar.",
            ifOff = "Bingkai menjadi emas-biru statis dan lebih hemat baterai. (Default)",
        )
        // ---- [INFO-CENTRAL] Pemutar: baris yang sebelumnya belum punya (i)
        HelpKey.PLAYER_RESUME -> HelpTopic(
            "Ingat posisi putar",
            what = "Video dilanjutkan dari posisi terakhir saat dibuka lagi.",
            ifOff = "Video selalu mulai dari awal.",
        )
        HelpKey.PLAYER_HWDEC -> HelpTopic(
            "Dekoder perangkat keras",
            what = "Memakai dekoder perangkat keras (MediaCodec) untuk memutar video.",
            ifOff = "Video didekode lewat perangkat lunak. Matikan bila video hijau atau patah-patah.",
            whenApplies = "Pada video berikutnya yang dibuka.",
        )
        HelpKey.PLAYER_TV_MODE -> HelpTopic(
            "Mode TV",
            what = "Menambah tile Mode TV di Beranda Cinema: jelajah berkas ala saluran TV dengan kartu poster dan panel info.",
            ifOff = "Tile Mode TV tidak tampil di Beranda.",
        )
        HelpKey.PLAYER_YT_AUDIO_LANG -> HelpTopic(
            "Suara YouTube",
            what = "Bahasa trek audio untuk video YouTube. Tombol bahasa di pemutar memakai setelan yang sama.",
            note = "Pilihan Asli mengikuti trek audio asli video (sering berbahasa Inggris).",
        )
        HelpKey.PLAYER_LOCAL_LANG -> HelpTopic(
            "Suara dan subtitle file lokal",
            what = "Kode bahasa untuk trek suara dan subtitle utama pada file lokal, mis. id atau en.",
            ifOff = "Kosong berarti mengikuti pilihan bawaan file.",
        )
        HelpKey.PLAYER_BG_MODE -> HelpTopic(
            "Pemutaran di latar",
            what = "Menentukan apa yang tetap terdengar setelah Anda keluar dari aplikasi: tidak pernah, hanya file audio, atau selalu (suara video juga).",
            whenApplies = "Saat pemutar dibuka berikutnya.",
            note = "Hemat data di latar dan Putar file baru langsung ada tepat di bawah pilihan ini.",
        )
        HelpKey.PLAYER_PERF_MODE -> HelpTopic(
            "Performa",
            what = "Menyesuaikan beban pemutar dengan perangkat. Otomatis mengikuti RAM. Hemat memakai cache kecil, mematikan efek visual, dan membatasi tinggi stream YouTube. " +
                "Seimbang memakai cache sedang dan membatasi thread dekoder. Maksimal tanpa pembatasan, cocok untuk perangkat kelas atas.",
            whenApplies = "Pada video berikutnya yang dibuka.",
            note = "Mode Hemat membuat video lebih lancar dan hemat memori di perangkat lawas. Untuk lebih hemat lagi, aktifkan Audio saja untuk video.",
        )
        // ---- [INFO-CENTRAL] Paket
        HelpKey.PACKAGES_MANAGE_DEBIAN -> HelpTopic(
            "Kelola Debian (Linux di Molina)",
            what = "Pusat pengelolaan Debian: cadangkan, pulihkan, atau kembalikan Debian ke kondisi awal (rootfs).",
            note = "Cadangkan dulu sebelum mengembalikan Debian ke kondisi awal.",
        )
        HelpKey.PACKAGES_EXTRA_TOOLS -> HelpTopic(
            "Alat tambahan di Debian",
            what = "Daftar alat di Debian beserta versi terpasang. Tiap alat punya skrip pasang, cek versi, dan hapus yang bisa diubah, dan Anda bisa menambah alat sendiri lewat + TAMBAH ALAT.",
            whenApplies = "Memeriksa versi dan memasang alat memakai internet.",
        )
        HelpKey.PACKAGES_BUNDLE -> HelpTopic(
            "Komponen bawaan Molina",
            what = "Komponen pihak ketiga yang dibundel di dalam aplikasi, beserta status verifikasinya: hash yang dipatok dan komponen wajib yang sudah terverifikasi.",
        )
    }
}
