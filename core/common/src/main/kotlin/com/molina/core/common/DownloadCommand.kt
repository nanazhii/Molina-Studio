package com.molina.core.common

enum class DownloadMode(val arg: String) { VIDEO("video"), AUDIO("audio") }

/** Permintaan satu sesi unduhan dari UI. Divalidasi oleh [DownloadCommand.validate] sebelum dijalankan. */
data class DownloadRequest(
    val urls: List<String>,
    val mode: DownloadMode = DownloadMode.VIDEO,
    val height: Int = 0,
    val videoExt: String = "mp4",
    val audioFormat: String = "mp3",
    val bitrate: String = "192k",
    val dir: String? = null,
    /** Kode bahasa subtitle (mode video saja). Kosong = tanpa subtitle. */
    val subs: List<String> = emptyList(),
    /** embed = disematkan ke video; file = .srt di samping video. */
    val subsMode: String = "embed",
    /** Sematkan sampul + tag ke audio (mode audio saja). */
    val cover: Boolean = true,
    /** Batas kecepatan, mis. "500K", "2M"; null = tanpa batas. */
    val limitRate: String? = null,
    /** Tulis poster (.jpg) + metadata ringkas (.info.json) di samping video (mode video saja). Bawaan mati. */
    val sidecar: Boolean = false,
    /** Simpan ke subfolder per kanal (<folder>/<Nama Kanal>/), video maupun audio. Bawaan mati. */
    val perChannel: Boolean = false,
    /** [DL-NETGUARD] Izinkan alamat jaringan lokal/privat (dari NetworkPolicy.allowLan). Loopback tetap ditolak. */
    val allowLan: Boolean = false,
    /** [DL-LYRICS] Simpan lirik dari deskripsi video sebagai .lrc di samping media (hanya bila ditemukan). Bawaan mati. */
    val lyrics: Boolean = false,
)

/** Argumen `molina_dl.py --json ...` untuk halaman Unduhan. Murni (tanpa Android). */
object DownloadCommand {
    const val SCRIPT_ASSET = "molina/molina_dl.py"
    const val MAX_URLS = 50
    const val MAX_URL_CHARS = 2048
    const val MAX_DIR_CHARS = 1024
    const val TIMEOUT_MS = 6L * 60 * 60 * 1000

    val VIDEO_EXTS = setOf("mp4", "mkv")
    val AUDIO_FORMATS = setOf("mp3", "m4a", "flac")
    val BITRATES = setOf("128k", "192k", "320k")

    val SUB_MODES = setOf("embed", "file")
    const val MAX_SUB_LANGS = 5
    private val SUB_LANG_RE = Regex("^[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8}){0,2}\$")
    private val RATE_RE = Regex("^[1-9][0-9]*(?:\\.[0-9]+)?[KkMm]?\$")

    private val URL_RE = Regex("^https?://[^\\s/\$.?#][^\\s]*\$", RegexOption.IGNORE_CASE)

    /** URL dipangkas, yang kosong dibuang, duplikat dibuang (urutan dipertahankan). */
    fun clean(urls: List<String>): List<String> = urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Pesan galat siap tampil bila [req] tidak boleh dijalankan; null bila aman. Pesan tidak memuat query URL. */
    fun validate(req: DownloadRequest): String? {
        val urls = clean(req.urls)
        if (urls.isEmpty()) return "Tempel minimal satu URL."
        if (urls.size > MAX_URLS) return "Maksimal $MAX_URLS URL per sesi."
        urls.firstOrNull { it.length > MAX_URL_CHARS || it.any { c -> c.isISOControl() } || !URL_RE.matches(it) }?.let {
            return "URL tidak valid (harus diawali http:// atau https://): " + LogRedact.target(it).take(80)
        }
        if (req.height !in 0..4320) return "Tinggi video tidak valid."
        if (req.videoExt !in VIDEO_EXTS) return "Format video tidak didukung."
        if (req.audioFormat !in AUDIO_FORMATS) return "Format audio tidak didukung."
        if (req.bitrate !in BITRATES) return "Bitrate tidak didukung."
        if (req.subs.size > MAX_SUB_LANGS || req.subs.any { !SUB_LANG_RE.matches(it) }) {
            return "Kode bahasa subtitle tidak valid (maksimal $MAX_SUB_LANGS, contoh: id, en)."
        }
        if (req.subsMode !in SUB_MODES) return "Mode subtitle tidak didukung."
        req.limitRate?.let { if (!RATE_RE.matches(it)) return "Batas kecepatan tidak valid (contoh: 500K, 2M)." }
        req.dir?.let {
            if (!it.startsWith("/") || it.length > MAX_DIR_CHARS || it.any { c -> c.isISOControl() }) {
                return "Folder tujuan harus berupa jalur absolut."
            }
            if (!dirAllowed(it)) return "Folder tujuan harus berada di dalam folder Molina." // [DL-DIR-ALLOWLIST]
        }
        return null
    }

    /** [DL-DIR-ALLOWLIST] Akar yang boleh dipakai sebagai folder tujuan (jalur Debian dan jalur Android). */
    private val DIR_ROOTS = listOf(
        "/sdcard/Molina", "/storage/emulated/0/Molina",
    )

    /** True bila [dir] berada di bawah salah satu akar Molina dan tidak memuat segmen "." atau "..". */
    fun dirAllowed(dir: String): Boolean {
        val d = dir.trimEnd('/')
        if (d.split('/').any { it == "." || it == ".." }) return false
        return DIR_ROOTS.any { d == it || d.startsWith("$it/") }
    }

    /** Argumen perawatan (tanpa URL): periksa lingkungan / perbarui yt-dlp. */
    fun doctorArgs(): List<String> = listOf("--json", "--doctor")
    fun updateArgs(): List<String> = listOf("--json", "--update-ytdlp")

    /**
     * Argv skrip (setelah berkas skrip). Panggil [validate] lebih dulu. Semua URL ditaruh setelah `--`
     * sehingga tidak pernah terbaca sebagai opsi; tidak ada shell di jalur eksekusi.
     */
    fun args(req: DownloadRequest): List<String> = buildList {
        add("--json")
        add("--mode"); add(req.mode.arg)
        if (req.mode == DownloadMode.VIDEO) {
            if (req.height > 0) { add("--height"); add(req.height.toString()) }
            add("--ext"); add(req.videoExt)
        } else {
            add("--audio-format"); add(req.audioFormat)
            add("--bitrate"); add(req.bitrate)
        }
        if (req.mode == DownloadMode.VIDEO && req.subs.isNotEmpty()) {
            add("--subs"); add(req.subs.distinct().joinToString(","))
            add("--subs-mode"); add(req.subsMode)
        }
        if (req.mode == DownloadMode.AUDIO && !req.cover) add("--no-cover")
        if (req.mode == DownloadMode.VIDEO && req.sidecar) add("--sidecar")
        if (req.perChannel) add("--per-channel")
        if (req.lyrics) add("--lyrics")
        if (req.allowLan) add("--allow-lan")
        req.limitRate?.let { add("--limit-rate"); add(it) }
        req.dir?.let { add("--dir"); add(it) }
        add("--")
        addAll(clean(req.urls))
    }
}
