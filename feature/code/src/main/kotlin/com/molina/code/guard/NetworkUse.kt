package com.molina.code.guard

/**
 * [CODE-OFFLINE] Pendeteksi perintah/pustaka jaringan di skrip sebelum dijalankan. Murni Kotlin (tanpa Android),
 * diuji di JVM (scripts/code-tests/GuardTests.kt). Editor Code sendiri tidak berjaringan, tetapi skrip yang
 * dijalankan di Debian memakai jaringan perangkat; objek ini hanya MEMBERI TAHU (heuristik, bukan penegakan,
 * bukan pemblokiran). Hasil kosong bukan jaminan skrip offline.
 */
object NetworkUse {
    const val MAX = 6

    private class Rule(val label: String, val re: Regex)

    // Perintah shell (juga dipakai untuk baris Python yang memanggil subprocess/os.system).
    private val SHELL = listOf(
        Rule("curl", Regex("""(?<![\w.-])curl(?![\w.-])""")),
        Rule("wget", Regex("""(?<![\w.-])wget(?![\w.-])""")),
        Rule("aria2c", Regex("""(?<![\w.-])aria2c(?![\w.-])""")),
        Rule("yt-dlp", Regex("""(?<![\w.-])yt-dlp(?![\w.])""")),
        Rule("ssh", Regex("""(?<![\w.-])ssh(?![\w.-])""")),
        Rule("scp", Regex("""(?<![\w.-])scp(?![\w.-])""")),
        Rule("sftp", Regex("""(?<![\w.-])sftp(?![\w.-])""")),
        Rule("ftp", Regex("""(?<![\w.-])ftp(?![\w.-])""")),
        Rule("telnet", Regex("""(?<![\w.-])telnet(?![\w.-])""")),
        Rule("nc", Regex("""(?<![\w.-])(?:nc|ncat|netcat|socat)(?![\w.-])""")),
        Rule("rsync (jarak jauh)", Regex("""(?<![\w.-])rsync\b[^\n]*(?:@|::|\s[\w.-]+:\S)""")),
        Rule("git push", Regex("""(?<![\w.-])git\s+push\b""")),
        Rule("git pull", Regex("""(?<![\w.-])git\s+pull\b""")),
        Rule("git fetch", Regex("""(?<![\w.-])git\s+fetch\b""")),
        Rule("git clone", Regex("""(?<![\w.-])git\s+clone\b""")),
        Rule("apt", Regex("""(?<![\w.-])apt(?:-get)?\s+(?:-\S+\s+)*(?:install|update|upgrade|dist-upgrade)\b""")),
        Rule("pip", Regex("""(?:(?<![\w.-])pip3?|-m\s+pip)\s+(?:install|download)\b""")),
        Rule("npm", Regex("""(?<![\w.-])(?:npm|yarn|pnpm)\s+(?:install|add|i)\b""")),
        Rule("cargo", Regex("""(?<![\w.-])cargo\s+(?:install|fetch|update)\b""")),
    )

    private val PY_IMPORT = Regex(
        """^\s*(?:import|from)\s+(requests|urllib\d*|http\.client|httpx|aiohttp|socket|ftplib|smtplib|telnetlib|paramiko|websockets?|xmlrpc\.client)\b""",
        RegexOption.MULTILINE
    )
    private val PY_SPAWN = Regex("""subprocess|os\.system|os\.popen|Popen|check_call|check_output""")
    private val KTS_NET = Regex("""java\.net\.|\.openConnection\s*\(|\.openStream\s*\(""")

    /** Label unik menurut urutan kemunculan, paling banyak [MAX]. Baris komentar murni dilewati. */
    fun scan(body: String, kind: ScriptPrecheck.Kind): List<String> {
        val found = LinkedHashSet<String>()
        val comment = if (kind == ScriptPrecheck.Kind.KTS) "//" else "#"
        val kept = body.lineSequence().filter { !it.trimStart().startsWith(comment) }.toList()
        val text = kept.joinToString("\n")
        when (kind) {
            ScriptPrecheck.Kind.SH -> for (r in SHELL) if (r.re.containsMatchIn(text)) found.add(r.label)
            ScriptPrecheck.Kind.PY -> {
                for (m in PY_IMPORT.findAll(text)) found.add(m.groupValues[1])
                for (line in kept) if (PY_SPAWN.containsMatchIn(line)) {
                    for (r in SHELL) if (r.re.containsMatchIn(line)) found.add(r.label)
                }
            }
            ScriptPrecheck.Kind.KTS -> if (KTS_NET.containsMatchIn(text)) found.add("java.net")
        }
        return found.take(MAX)
    }
}
