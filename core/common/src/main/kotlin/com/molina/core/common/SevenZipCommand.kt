package com.molina.core.common

/**
 * [ARCHIVE3] Perintah `7z` (paket Debian `7zip`) di Debian PRoot. Murni (tanpa Android) supaya bisa diuji JVM.
 *
 * Aturan yang diverifikasi terhadap 7-Zip 23.01 asli:
 * - `-spd` WAJIB: tanpa itu nama arsip seperti `a*b.7z` diperluas jadi beberapa arsip.
 * - `--` mencegah nama berawalan `-` dibaca sebagai opsi; jalur selalu absolut (`/sdcard/...`).
 * - `-o<dir>` ditulis tanpa spasi (`-o /x` ditolak 7z).
 * - Sandi lewat stdin (satu baris), TIDAK pernah di argumen proses (terlihat di `ps`).
 * - `-ba` membuang header sehingga `-slt` berisi blok `Kunci = Nilai` dipisah baris kosong.
 */
object SevenZipCommand {
    /** Urut prioritas. `7z` ada di paket `7zip`; `7zz` hanya di `7zip-standalone`. */
    val GUEST_BINARIES = listOf("/usr/bin/7z", "/usr/bin/7zz")
    const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    const val INSTALL_CMD = "apt-get update && apt-get install -y --no-install-recommends 7zip"
    const val HINT_INSTALL = "7zip belum terpasang di Debian. Di Terminal jalankan: $INSTALL_CMD"

    /** [ARCHIVE4] Titik mount berlingkup di guest: HANYA folder arsip dan folder staging yang di-bind, bukan seluruh penyimpanan. */
    const val GUEST_SRC = "/mnt/molina-src"
    const val GUEST_OUT = "/mnt/molina-out"

    /** [ARCHIVE6] Titik mount sumber kompres: satu per folder induk (`/mnt/molina-in/0`, `/1`, ...). Tidak dipakai bersamaan dengan [GUEST_SRC]. */
    const val GUEST_IN = "/mnt/molina-in"
    /** [ARCHIVE6] Nama berkas keluaran DI DALAM staging; diberi nama akhir oleh pemanggil saat commit. */
    const val OUT_FILE = "archive.7z"
    const val MAX_SOURCE_DIRS = 8

    private const val ANDROID_STORAGE = "/storage/emulated/0"
    private const val GUEST_SDCARD = "/sdcard"

    /** Jalur Android -> jalur Debian. Hanya yang di bawah penyimpanan bersama yang terlihat di guest; selain itu null. */
    fun guestPath(androidPath: String): String? {
        val p = androidPath
        if (p.isEmpty() || p.indexOf('\u0000') >= 0 || p.indexOf('\n') >= 0 || p.indexOf('\r') >= 0) return null
        if (p.split('/').any { it == ".." }) return null
        return when {
            p == ANDROID_STORAGE || p == GUEST_SDCARD -> GUEST_SDCARD
            p.startsWith("$ANDROID_STORAGE/") -> GUEST_SDCARD + p.substring(ANDROID_STORAGE.length)
            p.startsWith("$GUEST_SDCARD/") -> p
            else -> null
        }
    }

    /** Argumen `7z l`: daftar isi dalam format teknis (`-slt`), tanpa header (`-ba`). */
    fun listArgs(guestArchive: String): List<String> {
        requireGuestAbs(guestArchive)
        return listOf("l", "-slt", "-ba", "-spd", "--", guestArchive)
    }

    /**
     * Argumen `7z x` ke [guestOutDir] (folder staging yang sudah kosong). `-y` aman di staging kosong;
     * `-bsp1` mencetak persen progres ke stdout. Sandi TIDAK dimasukkan di sini (lewat stdin).
     */
    fun extractArgs(guestArchive: String, guestOutDir: String): List<String> {
        requireGuestAbs(guestArchive)
        requireGuestAbs(guestOutDir)
        return listOf("x", "-y", "-bsp1", "-spd", "-o$guestOutDir", "--", guestArchive)
    }

    /**
     * [ARCHIVE4] Seperti [extractArgs] tetapi hanya entri [only] (nama dalam arsip; akhiran "/" dibuang, folder ikut
     * beserta isinya). Nama diletakkan SETELAH `--` sehingga `-x` tidak dibaca sebagai opsi, dan `-spd` mematikan wildcard.
     * Nama berawalan `@` (list file 7z) atau `!` ditolak karena tafsir 7z atasnya tidak dijamin.
     */
    fun extractArgs(guestArchive: String, guestOutDir: String, only: List<String>): List<String> {
        val base = extractArgs(guestArchive, guestOutDir)
        val names = only.map { raw ->
            val clean = safeEntryPath(raw.trimEnd('/'))
                ?: throw IllegalArgumentException("Nama entri tidak aman: $raw")
            require(!clean.startsWith("@") && !clean.startsWith("!")) { "Nama entri tidak didukung untuk ekstrak sebagian: $raw" }
            clean
        }
        return base + names
    }

    /**
     * [ARCHIVE6] Argumen `7z a` untuk membuat arsip 7z ke [guestOut] dari [guestSources] (jalur guest ABSOLUT).
     * Diverifikasi terhadap 7-Zip 23.01 asli:
     * - Jalur sumber absolut: 7z menyimpan HANYA nama item (`/mnt/molina-in/0/foto` -> `foto/...`), jadi tidak perlu `--cwd`.
     * - `-p` tanpa nilai membaca sandi dari stdin (satu baris; non-TTY tidak meminta verifikasi). Sandi TIDAK ada di argumen.
     * - [encryptNames] = `-mhe=on`: nama berkas ikut terenkripsi (daftar isi pun butuh sandi). Wajib bersama [hasPassword].
     * - Sumber yang namanya (komponen terakhir) memuat `*` atau `?` DITOLAK: lihat komentar di badan fungsi. Nama bersarang di dalam
     *   folder sumber tidak terpengaruh.
     * - [excludes] = jalur dalam arsip (`nama/sub/tautan`) yang DILEWATI, satu `-x!` per entri; literal karena `-spd`.
     *   Dipakai untuk tautan simbolik: tanpa ini 7z MENGIKUTI tautan folder (mis. -> /etc di rootfs) dan mengarsipkan isi targetnya.
     * - `-snl`: lapis kedua; tautan yang lolos dari [excludes] disimpan sebagai tautan, tidak diikuti.
     * - `-spd`: tanpa wildcard (nama berisi `*` aman). `--` melindungi nama berawalan `-`. `-y` aman: keluaran di staging kosong.
     * - Sumber yang tak terbaca membuat 7z keluar 1 TETAPI arsip tetap tertulis; pemanggil wajib menganggapnya gagal.
     */
    fun createArgs(
        guestOut: String, guestSources: List<String>, level: Int, hasPassword: Boolean, encryptNames: Boolean,
        excludes: List<String> = emptyList(),
    ): List<String> {
        requireGuestAbs(guestOut)
        require(guestSources.isNotEmpty()) { "Tidak ada sumber untuk dikompres" }
        guestSources.forEach {
            requireGuestAbs(it)
            // 7-Zip 23.01 (terukur): bila komponen TERAKHIR sebuah sumber memuat * atau ?, sumber lain yang hilang diabaikan
            // DIAM-DIAM (exit 0, tanpa peringatan) walau -spd aktif. Arsip bersandi yang tidak lengkap tanpa tanda = berbahaya, maka ditolak.
            val leaf = it.substringAfterLast('/')
            require(leaf.none { c -> c == '*' || c == '?' }) { "Nama berisi * atau ? tidak didukung untuk 7z bersandi: $leaf" }
        }
        require(level in 0..9) { "Level kompresi tidak valid: $level" }
        require(!encryptNames || hasPassword) { "Enkripsi nama berkas membutuhkan kata sandi" }
        excludes.forEach {
            require(it.isNotEmpty() && !it.startsWith("/") && it.none { c -> c == '\u0000' || c == '\n' || c == '\r' }) { "Nama pengecualian tidak valid" }
        }
        return buildList {
            add("a"); add("-t7z"); add("-mx=$level"); add("-snl"); add("-spd"); add("-bsp1"); add("-y")
            if (hasPassword) add("-p")
            if (encryptNames) add("-mhe=on")
            for (e in excludes) add("-x!$e")
            add("--"); add(guestOut)
            addAll(guestSources)
        }
    }

    /** True bila [p] aman dipakai sebagai sisi host `--bind=host:guest` (proot memecah di ':'). */
    fun bindHostOk(p: String): Boolean =
        p.startsWith("/") && p.none { it == ':' || it == '\n' || it == '\r' || it == '\u0000' } && p.split('/').none { it == ".." }

    /**
     * [ARCHIVE4] Argv libproot.so dengan bind EKSPLISIT [binds] (host -> guest) dan tanpa bind penyimpanan lain.
     * Dipakai ekstrak via Debian: 7z hanya melihat folder arsip dan folder staging, tidak mengikuti lingkup akses
     * penyimpanan Terminal. Titik mount guest harus sudah ada di rootfs (dibuat pemanggil).
     */
    fun prootArgsScoped(rootfs: String, binds: List<Pair<String, String>>, binary: String, args: List<String>): List<String> = buildList {
        add("--link2symlink"); add("--kill-on-exit"); add("-0")
        add("--rootfs=$rootfs"); add("--cwd=/root")
        add("--bind=/dev"); add("--bind=/proc"); add("--bind=/sys")
        for ((host, guest) in binds) {
            require(bindHostOk(host)) { "Jalur host tidak valid untuk bind: $host" }
            requireGuestAbs(guest)
            require(guest.indexOf(':') < 0) { "Jalur guest tidak valid untuk bind: $guest" }
            add("--bind=$host:$guest")
        }
        add("/usr/bin/env"); add("-i")
        add("HOME=/root"); add("LANG=C.UTF-8"); add("MALLOC_ARENA_MAX=2")
        add("PATH=$GUEST_PATH")
        add(binary)
        addAll(args)
    }

    /** Bahan stdin untuk sandi: satu baris, atau null bila tanpa sandi (stdin ditutup). Sandi berisi baris baru ditolak. */
    fun stdinFor(password: String?): ByteArray? {
        val pw = password?.takeIf { it.isNotEmpty() } ?: return null
        require(pw.indexOf('\n') < 0 && pw.indexOf('\r') < 0 && pw.indexOf('\u0000') < 0) { "Sandi tidak boleh memuat baris baru" }
        return (pw + "\n").toByteArray(Charsets.UTF_8)
    }

    /**
     * Argv penuh untuk libproot.so (tanpa argv[0]). [storage] = jalur Android penyimpanan bersama (di-bind sebagai
     * `/sdcard` dan `/storage/emulated/0`), [binary] = jalur guest 7z, [args] = argumen 7z.
     * Beda dari [YtdlpCommand.prootArgs]: memanggil biner native langsung (bukan lewat python3).
     */
    fun prootArgs(rootfs: String, storage: String, binary: String, args: List<String>): List<String> = buildList {
        add("--link2symlink"); add("--kill-on-exit"); add("-0")
        add("--rootfs=$rootfs"); add("--cwd=/root")
        add("--bind=/dev"); add("--bind=/proc"); add("--bind=/sys")
        add("--bind=$storage:$GUEST_SDCARD"); add("--bind=$storage:$ANDROID_STORAGE")
        add("/usr/bin/env"); add("-i")
        add("HOME=/root"); add("LANG=C.UTF-8"); add("MALLOC_ARENA_MAX=2")
        add("PATH=$GUEST_PATH")
        add(binary)
        addAll(args)
    }

    private fun requireGuestAbs(p: String) {
        require(p.startsWith("/")) { "Jalur guest harus absolut: $p" }
        require(p.indexOf('\u0000') < 0 && p.indexOf('\n') < 0 && p.indexOf('\r') < 0) { "Jalur tidak valid" }
    }

    // ---------------------------------------------------------------- daftar isi

    data class Entry(
        val path: String,
        val size: Long,
        val packed: Long,
        val isDir: Boolean,
        val isLink: Boolean,
        val encrypted: Boolean,
        val method: String,
        val attributes: String,
    )

    /**
     * Urai keluaran `7z l -slt -ba`. Toleran: baris yang bukan `Kunci = Nilai` (mis. prompt "Enter password")
     * dilewati; blok tanpa `Path` dilewati.
     */
    fun parseList(stdout: String): List<Entry> {
        val out = ArrayList<Entry>()
        var cur: MutableMap<String, String>? = null
        fun flush() {
            val m = cur ?: return
            cur = null
            val path = m["Path"] ?: return
            val attr = m["Attributes"].orEmpty()
            val unixMode = attr.substringAfter(' ', "").trim()
            val isDir = m["Folder"] == "+" || attr.startsWith("D") || unixMode.startsWith("d")
            val isLink = unixMode.startsWith("l") || m.containsKey("Symbolic Link") || m.containsKey("Link")
            out.add(Entry(
                path = path,
                size = m["Size"]?.toLongOrNull() ?: 0L,
                packed = m["Packed Size"]?.toLongOrNull() ?: 0L,
                isDir = isDir,
                isLink = isLink,
                encrypted = m["Encrypted"] == "+",
                method = m["Method"].orEmpty(),
                attributes = attr,
            ))
        }
        for (raw in stdout.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) { flush(); continue }
            val i = line.indexOf(" = ")
            if (i <= 0) continue
            val key = line.substring(0, i)
            val value = line.substring(i + 3)
            if (key == "Path" && cur != null && cur!!.containsKey("Path")) flush()
            if (cur == null) cur = LinkedHashMap()
            cur!![key] = value
        }
        flush()
        return out
    }

    /**
     * [ARCHIVE4] Baca keluaran `7z l -slt -ba` blok demi blok (dipisah baris kosong) tanpa menampung seluruh keluaran,
     * lalu panggil [sink] per entri. Berhenti setelah [maxEntries]; mengembalikan true bila MASIH ADA entri sesudahnya (terpotong).
     */
    fun parseListStream(reader: java.io.BufferedReader, maxEntries: Int, sink: (Entry) -> Unit): Boolean {
        var count = 0
        val block = StringBuilder()
        fun flush(): Boolean {
            if (block.isEmpty()) return false
            val entries = parseList(block.toString())
            block.setLength(0)
            for (e in entries) {
                if (count >= maxEntries) return true
                sink(e)
                count++
            }
            return false
        }
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) { if (flush()) return true } else block.append(line).append('\n')
        }
        return flush()
    }

    /**
     * Nama entri yang tidak tepercaya -> nama bersih (pemisah `/`, tanpa awalan `/`), atau null bila berbahaya:
     * kosong, NUL, `..`, jalur absolut, huruf drive, atau pemisah Windows (`\`).
     * Pemeriksaan ini peringatan dini; staging tetap divalidasi ulang setelah ekstrak.
     */
    fun safeEntryPath(raw: String): String? {
        if (raw.isEmpty() || raw.indexOf('\u0000') >= 0 || raw.indexOf('\\') >= 0) return null
        if (raw.startsWith("/") || (raw.length >= 2 && raw[1] == ':')) return null
        val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    // ---------------------------------------------------------------- hasil proses

    enum class Failure(val hint: String) {
        NEED_PASSWORD("Arsip dikunci. Masukkan kata sandi."),
        WRONG_PASSWORD("Kata sandi salah."),
        MISSING_VOLUME("Ada bagian arsip yang hilang. Pastikan semua bagian (.001, .002, ...) ada di folder yang sama."),
        NOT_ARCHIVE("Berkas ini bukan arsip yang dikenali atau rusak."),
        UNSUPPORTED_METHOD("Metode kompresi tidak didukung oleh 7zip build Debian ini (RAR berkompresi tidak didukung)."),
        DISK_FULL("Ruang penyimpanan tidak cukup."),
        OUT_OF_MEMORY("Memori tidak cukup untuk arsip ini."),
        DATA_ERROR("Data arsip rusak (galat CRC atau data)."),
        BAD_COMMAND("Perintah 7z ditolak (argumen tidak valid)."),
        CANCELLED("Dibatalkan."),
        SOURCE_UNREADABLE("Sebagian berkas sumber tidak bisa dibaca. Arsip tidak dibuat."),
        UNKNOWN("7z gagal tanpa sebab yang dikenali."),
    }

    /** Kode keluar 7z: 0 sukses, 1 peringatan non-fatal, 2 fatal, 7 galat perintah, 8 memori, 255 dihentikan. */
    fun isSuccess(exit: Int): Boolean = exit == 0

    /**
     * Petakan keluaran gagal ke [Failure]. Urutan penting: yang spesifik lebih dulu
     * ("Data Error in encrypted file. Wrong password?" harus menjadi WRONG_PASSWORD, bukan DATA_ERROR).
     * [passwordGiven] membedakan NEED_PASSWORD (prompt muncul, stdin kosong) dari WRONG_PASSWORD.
     */
    fun classify(exit: Int, output: String, passwordGiven: Boolean): Failure {
        val e = output.lowercase()
        fun has(vararg k: String) = k.any { it in e }
        return when {
            has("enter password") && !passwordGiven -> Failure.NEED_PASSWORD
            has("wrong password") -> Failure.WRONG_PASSWORD
            has("cannot find volume", "unexpected end of archive") -> Failure.MISSING_VOLUME
            has("unsupported method", "unsupported compression") -> Failure.UNSUPPORTED_METHOD
            has("no space left", "disk full", "not enough space", "there is not enough space") -> Failure.DISK_FULL
            exit == 8 || has("can not allocate memory", "cannot allocate memory", "out of memory") -> Failure.OUT_OF_MEMORY
            has("cannot open the file as archive", "can't open as archive", "is not archive") -> Failure.NOT_ARCHIVE
            has("data error", "crc failed", "crc error") -> Failure.DATA_ERROR
            exit == 7 || has("command line error") -> Failure.BAD_COMMAND
            exit == 255 -> Failure.CANCELLED
            else -> Failure.UNKNOWN
        }
    }

    /**
     * [ARCHIVE6] Klasifikasi galat `7z a`. Berbeda dari [classify]: exit 1 (peringatan, mis. sumber hilang/tak terbaca)
     * di sini FATAL karena arsip yang tertulis tidak lengkap, dan pada arsip bersandi itu berbahaya (pengguna mengira semuanya aman).
     */
    fun classifyCreate(exit: Int, output: String, passwordGiven: Boolean): Failure =
        if (exit == 1) Failure.SOURCE_UNREADABLE else classify(exit, output, passwordGiven)

    // ---------------------------------------------------------------- progres

    private val PCT = Regex("""(\d{1,3})%""")
    /** Backspace (U+0008) dan CR dari progres `-bsp1`; ditulis eksplisit karena `\b` di dalam kelas karakter tidak valid di Java/ICU. */
    private val CTRL = Regex("[\u0008\r]+")

    /** Persen terakhir (0..100) dari potongan stdout `-bsp1` (berisi `\b` dan `\r`); null bila belum ada. */
    fun parseProgress(chunk: String): Int? {
        val last = PCT.findAll(chunk).lastOrNull() ?: return null
        val v = last.groupValues[1].toIntOrNull() ?: return null
        return if (v in 0..100) v else null
    }

    /** Buang prompt, kontrol progres, dan baris kosong berlebih dari keluaran untuk ditampilkan/di-log. */
    fun cleanOutput(raw: String): String =
        raw.replace(CTRL, "\n")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !PCT.matches(it) && !it.endsWith("Scan") }
            .joinToString("\n")
}
