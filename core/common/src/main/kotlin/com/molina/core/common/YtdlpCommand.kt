package com.molina.core.common

/**
 * Perintah yt-dlp di Debian PRoot. Murni (tanpa Android): :app hanya mengisi jalur rootfs dan
 * menjalankan hasilnya. yt-dlp dijalankan lewat `python3 <berkas>` sehingga bit eksekusi tidak
 * dibutuhkan, dan `env -i` memastikan lingkungan guest bersih.
 */
object YtdlpCommand {
    /** Lokasi yt-dlp di guest, urut prioritas (jalur pemasang aplikasi lebih dulu). */
    val GUEST_BINARIES = listOf("/usr/local/bin/yt-dlp", "/root/.local/bin/yt-dlp", "/usr/bin/yt-dlp")
    const val GUEST_PYTHON = "/usr/bin/python3"
    const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** Petunjuk bila python3 belum ada (rootfs minimal tidak membawanya). */
    const val INSTALL_PYTHON_CMD =
        "apt-get update && apt-get install -y --no-install-recommends python3-minimal ca-certificates"
    const val INSTALL_PYTHON_HINT = "Di Terminal jalankan: $INSTALL_PYTHON_CMD"

    const val HINT_INSTALL = "yt-dlp belum terpasang. Buka Pengaturan > Mesin cadangan (yt-dlp) > Pasang."

    /**
     * URL video di dalam playlist (list= / playlist=) tetap satu video. Selain itu halaman daftar
     * (mis. channel IPTV) boleh jadi playlist, tanpa mengekstrak tiap entri (flat).
     */
    fun playlistFlag(url: String): String {
        val q = url.substringAfter('?', "").substringBefore('#').lowercase()
        val inList = q.split('&').any { it.startsWith("list=") || it.startsWith("playlist=") }
        return if (inList) "--no-playlist" else "--flat-playlist"
    }

    /** Argumen yt-dlp untuk satu video. `--` mencegah URL dibaca sebagai opsi. */
    fun resolveArgs(url: String): List<String> = listOf(
        "--ignore-config", "-J", playlistFlag(url), "--no-warnings", "--no-progress", "--no-cache-dir",
        "--socket-timeout", "20", "--retries", "1",
        "-f", YtdlpParse.FORMAT,
        "--", url,
    )

    /**
     * Bahasa antarmuka YouTube untuk pencarian. Tanpa ini yt-dlp memakai hl=en sehingga judul video
     * berbahasa Indonesia tampil sebagai terjemahan Inggris. Versi yt-dlp lama mengabaikan argumen ini.
     */
    const val SEARCH_LANG = "id"

    /** Argumen pencarian judul (YouTube); keluaran satu JSON per baris untuk [YtdlpSearch.parse]. */
    fun searchArgs(query: String, limit: Int): List<String> {
        val n = limit.coerceIn(1, 50)
        return listOf(
            "--ignore-config", "--flat-playlist", "-j", "--no-warnings", "--no-progress", "--no-cache-dir",
            "--socket-timeout", "20", "--retries", "1",
            "--extractor-args", "youtube:lang=$SEARCH_LANG",
            "--", "ytsearch$n:${query.trim()}",
        )
    }

    /**
     * Argv penuh untuk libproot.so (tanpa argv[0]). [binary] adalah jalur guest yt-dlp,
     * [args] argumen yt-dlp.
     */
    fun prootArgs(rootfs: String, binary: String, args: List<String>): List<String> = buildList {
        add("--link2symlink"); add("--kill-on-exit"); add("-0")
        add("--rootfs=$rootfs"); add("--cwd=/root")
        add("--bind=/dev"); add("--bind=/proc"); add("--bind=/sys")
        add("/usr/bin/env"); add("-i")
        add("HOME=/root"); add("LANG=C.UTF-8"); add("MALLOC_ARENA_MAX=2")
        add("PYTHONDONTWRITEBYTECODE=1"); add("PYTHONIOENCODING=utf-8")
        add("PATH=$GUEST_PATH")
        add(GUEST_PYTHON); add(binary)
        addAll(args)
    }

    /**
     * Pembungkus sh: mencetak PID-nya sendiri sebagai baris pertama stdout lalu `exec` (PID sama
     * dengan proot), sehingga pohon proses bisa dimatikan tanpa API Process.pid() (baru ada di API 33).
     */
    val SH_WRAPPER = listOf("/system/bin/sh", "-c", "echo \$\$; exec \"\$@\"", "sh")

    /** Pisahkan baris PID dari sisa keluaran. */
    fun splitPidLine(stdout: String): Pair<Int?, String> {
        val nl = stdout.indexOf('\n')
        if (nl < 0) return null to stdout
        val pid = stdout.substring(0, nl).trim().toIntOrNull()
        return if (pid != null) pid to stdout.substring(nl + 1) else null to stdout
    }

    /** Petakan stderr yt-dlp ke [StreamError]. Urutan penting: yang spesifik lebih dulu. */
    fun classify(stderr: String): StreamError {
        val e = stderr.lowercase()
        fun has(vararg k: String) = k.any { it in e }
        return when {
            has("unsupported url") -> StreamError.UNSUPPORTED
            has("confirm your age", "age-restricted", "age restricted", "inappropriate for some users") ->
                StreamError.AGE_RESTRICTED
            has("private video", "this video is private", "log in", "login required", "sign in", "cookies") ->
                StreamError.PRIVATE
            has("not available in your country", "geo restrict", "geo-restrict", "blocked it in your country",
                "not made this video available in your country") -> StreamError.GEO_BLOCKED
            has("video unavailable", "http error 404", "does not exist", "has been removed", "not found",
                "no video could be found", "this content isn't available", "content is not available") ->
                StreamError.NOT_FOUND
            has("unable to extract", "extractor error", "unable to find", "failed to parse", "update yt-dlp",
                "please report this issue") -> StreamError.PARSE
            has("unable to download", "timed out", "temporary failure", "network is unreachable",
                "connection reset", "connection refused", "name or service not known", "http error 5",
                "http error 403", "http error 429", "ssl:", "certificate") -> StreamError.NETWORK
            else -> StreamError.UNKNOWN
        }
    }

    /** Ringkas stderr untuk log: baris `ERROR:` terakhir, tanpa URL penuh (disamarkan LogRedact). */
    fun briefError(stderr: String): String {
        val line = stderr.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("ERROR") }
            ?: stderr.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
            ?: return ""
        return LogRedact.text(line).take(240)
    }
}

/** Verifikasi unduhan yt-dlp terhadap berkas SHA2-256SUMS resmi rilis. */
object YtdlpInstall {
    const val BASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/"
    const val BINARY_URL = BASE + "yt-dlp"
    const val SUMS_URL = BASE + "SHA2-256SUMS"
    const val TARGET_GUEST_PATH = "usr/local/bin/yt-dlp"

    /** Batas wajar ukuran zipapp (±3 MB); di luar ini dianggap salah unduh. */
    const val MIN_BYTES = 500_000L
    const val MAX_BYTES = 40_000_000L

    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

    /** Hash yang diharapkan untuk berkas bernama persis [name], atau null. Format baris: `<hash>  <nama>`. */
    fun expectedSha(sums: String, name: String = "yt-dlp"): String? {
        for (raw in sums.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val hash = line.substringBefore(' ')
            val file = line.substringAfter(' ', "").trim().removePrefix("*")
            if (file == name && HEX64.matches(hash)) return hash.lowercase()
        }
        return null
    }
}
