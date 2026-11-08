package com.molina.cinema

import java.io.File

/**
 * Penjaga konfigurasi mpv. mpv.conf dan input.conf bisa diedit dari aplikasi; pengguna dapat
 * dibujuk menempelkan isi yang memuat skrip, soket kendali, `run`/`subprocess` (menjalankan
 * program), atau mematikan pengaman jaringan. Teks diperiksa saat disimpan dan sekali lagi
 * sebelum mpv membacanya (berkas berisiko dikarantina jadi *.blocked).
 * Daftar hitam heuristik: mengurangi risiko, bukan jaminan. JVM murni (tanpa Android).
 */
object ConfigGuard {
    data class Finding(val line: Int, val text: String, val reason: String)

    private val BLOCKED_OPTIONS: Map<String, String> = mapOf(
        "script" to "memuat skrip eksternal",
        "scripts" to "memuat skrip eksternal",
        "load-scripts" to "mengubah pemuatan skrip otomatis",
        "include" to "menyertakan berkas lain (melewati pemeriksaan)",
        "input-conf" to "memakai berkas input lain (melewati pemeriksaan)",
        "input-file" to "membuka kanal perintah lewat berkas/FIFO",
        "input-ipc-server" to "membuka soket kendali yang bisa dipakai aplikasi lain",
        "input-ipc-client" to "membuka soket kendali yang bisa dipakai aplikasi lain",
        "use-filedir-conf" to "membaca konfigurasi dari folder media",
        "ytdl" to "mengaktifkan pengunduh eksternal (yt-dlp)",
        "ytdl-raw-options" to "meneruskan argumen ke yt-dlp",
        "load-unsafe-playlists" to "mengizinkan playlist memuat berkas lokal",
        "stream-lavf-o" to "mengubah whitelist protokol ffmpeg",
        "demuxer-lavf-o" to "mengubah whitelist protokol ffmpeg",
        "tls-verify" to "mengubah verifikasi sertifikat TLS",
        "tls-ca-file" to "mengganti daftar sertifikat tepercaya",
        "profile-cond" to "menjalankan ekspresi skrip otomatis",
    )

    private val BLOCKED_COMMANDS: Map<String, String> = mapOf(
        "run" to "menjalankan program eksternal",
        "subprocess" to "menjalankan program eksternal",
        "load-script" to "memuat skrip eksternal",
        "load-config-file" to "memuat konfigurasi lain (melewati pemeriksaan)",
        "load-input-conf" to "memuat input lain (melewati pemeriksaan)",
    )

    private val SETTERS = setOf("set", "add", "cycle", "cycle-values", "multiply", "change-list")
    private val CMD_PREFIXES = setOf(
        "no-osd", "osd-bar", "osd-msg", "osd-msg-bar", "osd-auto", "raw",
        "expand-properties", "repeatable", "nonrepeatable", "async", "sync", "expand-path",
    )
    private val OP_SUFFIX = Regex("-(add|append|pre|set|remove|toggle|clr)$")
    private val WS = Regex("\\s+")

    private fun normalizeOption(raw: String): String =
        raw.trim().trim('"', '\'').lowercase().removePrefix("--").removePrefix("no-").replace(OP_SUFFIX, "")

    private fun normalizeCommand(raw: String): String =
        raw.trim().trim('"', '\'').lowercase().replace('_', '-')

    fun scanMpvConf(text: String): List<Finding> {
        val out = ArrayList<Finding>()
        text.lineSequence().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) return@forEachIndexed
            val key = normalizeOption(line.takeWhile { it != '=' && !it.isWhitespace() })
            BLOCKED_OPTIONS[key]?.let { out += Finding(i + 1, line.take(120), it) }
        }
        return out
    }

    fun scanInputConf(text: String): List<Finding> {
        val out = ArrayList<Finding>()
        text.lineSequence().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val parts = line.split(WS, limit = 2)
            if (parts.size < 2) return@forEachIndexed
            for (seg in parts[1].split(';')) {
                val t = seg.trim().split(WS).filter { it.isNotEmpty() }
                val idx = t.indexOfFirst { normalizeCommand(it) !in CMD_PREFIXES }
                if (idx < 0) continue
                val cmd = normalizeCommand(t[idx])
                val why = BLOCKED_COMMANDS[cmd]
                    ?: if (cmd in SETTERS) t.getOrNull(idx + 1)?.let { BLOCKED_OPTIONS[normalizeOption(it)] } else null
                if (why != null) { out += Finding(i + 1, line.take(120), why); break }
            }
        }
        return out
    }

    /** Ringkas temuan jadi teks untuk dialog/log. */
    fun describe(findings: List<Finding>): String =
        findings.take(5).joinToString("\n") { "Baris ${it.line}: ${it.reason}\n  ${it.text}" }

    private fun quarantine(f: File) {
        val dst = File(f.parentFile, f.name + ".blocked")
        if (dst.exists()) dst.deleteRecursively()
        if (!f.renameTo(dst)) f.deleteRecursively() // gagal aman: lebih baik hilang daripada terbaca mpv
    }

    /** Panggil sebelum mpv dibuat. Mengembalikan pesan untuk log. */
    fun sanitizeConfigDir(dir: File): List<String> {
        val msgs = ArrayList<String>()
        val checks = listOf<Pair<String, (String) -> List<Finding>>>(
            "mpv.conf" to { t -> scanMpvConf(t) },
            "input.conf" to { t -> scanInputConf(t) },
        )
        for ((name, scan) in checks) {
            val f = File(dir, name)
            if (!f.isFile) continue
            val findings = try { scan(f.readText()) } catch (e: Exception) { listOf(Finding(0, "", "tidak dapat dibaca")) }
            if (findings.isEmpty()) continue
            quarantine(f)
            msgs += "$name dikarantina (${findings.first().reason}, baris ${findings.first().line})"
        }
        val scripts = File(dir, "scripts")
        if (scripts.isDirectory && scripts.list()?.isNotEmpty() == true) {
            quarantine(scripts)
            msgs += "folder scripts dikarantina (skrip otomatis tidak diizinkan)"
        }
        return msgs
    }
}
