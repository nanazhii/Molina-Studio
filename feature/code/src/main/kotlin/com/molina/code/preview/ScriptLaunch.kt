package com.molina.code.preview

/**
 * Aturan murni (tanpa Android) untuk fitur Script Execution: batas ukuran, normalisasi akhir baris,
 * nama berkas aman, dan perintah yang diketik ke sesi Molina Terminal. Diuji di JVM
 * (scripts/code-tests).
 */
object ScriptLaunch {
    /** Batas isi skrip yang boleh disimpan di Script Execution. */
    const val MAX_LINES = 3000
    const val MAX_TITLE = 80
    const val MAX_NOTE = 500

    /** Jumlah baris. Kosong = 0; baris terakhir tanpa LF tetap dihitung; LF penutup tidak menambah baris. */
    fun countLines(text: String): Int {
        if (text.isEmpty()) return 0
        val normalized = if ('\r' in text) normalizeEol(text) else text
        var n = 0
        for (c in normalized) if (c == '\n') n++
        return if (normalized.endsWith('\n')) n else n + 1
    }

    /** CRLF dan CR sendirian jadi LF. CRLF merusak shebang (`#!/bin/bash\r`) di Debian. */
    fun normalizeEol(text: String): String =
        if ('\r' !in text) text else text.replace("\r\n", "\n").replace('\r', '\n')

    /** Interpreter dari judul (ekstensi) dan isi (shebang). Default bash bila tak dikenal. */
    fun planFor(title: String, text: String): RunPlan {
        val first = text.lineSequence().firstOrNull()?.trimEnd('\r')
        return RunPlans.of(title, first) ?: RunPlans.of("x.sh", null)!!
    }

    fun hasShebang(text: String): Boolean = text.startsWith("#!")

    /**
     * Nama berkas di guest: `<slug>-<id>.<ext>`. Slug hanya [a-z0-9._-], ekstensi .sh/.py/.bash
     * pada judul dibuang agar tidak ganda, panjang slug maksimal 40.
     */
    fun fileName(id: String, title: String, plan: RunPlan): String {
        var base = title.trim().lowercase()
        for (ext in listOf(".sh", ".py", ".bash")) if (base.endsWith(ext)) base = base.removeSuffix(ext)
        val sb = StringBuilder()
        var lastDash = true
        for (c in base) {
            val ok = c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_'
            if (ok) { sb.append(c); lastDash = false }
            else if (!lastDash) { sb.append('-'); lastDash = true }
        }
        var slug = sb.toString().trim('-', '.', '_')
        if (slug.length > 40) slug = slug.take(40).trim('-', '.', '_')
        if (slug.isEmpty()) slug = "skrip"
        val safeId = id.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.ifEmpty { "x" }
        return "$slug-$safeId.${plan.extension}"
    }

    /** Kutip tunggal ala POSIX: `a'b` -> `'a'\''b'`. */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Satu baris perintah untuk diketik (tanpa Enter). Selalu `chmod +x` lebih dulu. Bila skrip punya
     * shebang, jalankan langsung agar interpreternya dihormati; bila tidak, pakai interpreter dari [plan].
     */
    fun terminalCommand(guestPath: String, plan: RunPlan, text: String, args: String = ""): String {
        val p = shellQuote(guestPath)
        val a = args.filter { it >= ' ' }.trim().let { if (it.isEmpty()) "" else " $it" }
        return if (hasShebang(text)) "chmod +x $p && $p$a" else "chmod +x $p && ${plan.interpreter} $p$a"
    }
}
