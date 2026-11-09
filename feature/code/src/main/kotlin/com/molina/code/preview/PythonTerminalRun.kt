package com.molina.code.preview

/** [CODE-PYRUN2] Bahasa skrip yang didukung "Jalankan di Terminal": interpreter, pembatas heredoc, dan ekstensi. */
enum class TerminalRunLang(val label: String, val interpreter: String, val delimiter: String, val ext: String) {
    PY("Python", "python3", "PYEOF", ".py"),
    SH("Shell", "bash", "SHEOF", ".sh")
}

/**
 * Aturan murni (tanpa Android) untuk "Jalankan di Terminal" berkas Python/Shell:
 * mode HEREDOC (`python3 << 'PYEOF'` ... `PYEOF`) dan mode FILE (`python3 nama.py`).
 * Diuji di JVM (scripts/code-tests/PythonRunTests.kt).
 */
object PythonTerminalRun {
    const val DELIMITER = "PYEOF"
    const val MAX_NAME = 60

    /** Hasil normalisasi nama: [name] berisi nama berkas ber-ekstensi, atau [error] berisi alasan. */
    class PyName(val name: String?, val error: String?)

    /** Bahasa dari ekstensi nama (.py / .sh, huruf besar/kecil sama), atau null. */
    fun langOf(name: String?): TerminalRunLang? {
        if (name == null) return null
        for (l in TerminalRunLang.values()) {
            if (name.length > l.ext.length && name.endsWith(l.ext, ignoreCase = true)) return l
        }
        return null
    }

    fun isPythonName(name: String?): Boolean = langOf(name) == TerminalRunLang.PY

    /** Nama untuk kolom isian: nama berkas tanpa ekstensi [lang] ("skrip" bila kosong atau Untitled). */
    fun suggestStem(name: String?, lang: TerminalRunLang = TerminalRunLang.PY): String {
        val n = name?.trim().orEmpty()
        val stem = if (n.endsWith(lang.ext, ignoreCase = true)) n.dropLast(lang.ext.length) else n
        return if (stem.isEmpty() || stem.startsWith("Untitled", ignoreCase = true)) "skrip" else stem
    }

    /**
     * Pengguna cukup mengisi nama; ekstensi dideteksi otomatis: ekstensi [lang] (huruf apa pun) dibuang lalu
     * ditambahkan lagi dalam huruf kecil, jadi `tes`, `tes.py` dan `tes.PY` semuanya menjadi `tes.py`.
     */
    fun normalizeName(input: String, lang: TerminalRunLang = TerminalRunLang.PY): PyName {
        var s = input.trim()
        if (s.endsWith(lang.ext, ignoreCase = true)) s = s.dropLast(lang.ext.length)
        if (s.isEmpty()) return PyName(null, "Isi nama berkas")
        if (s.length > MAX_NAME) return PyName(null, "Nama terlalu panjang (maks. $MAX_NAME karakter)")
        val ok = s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' || it == '.' }
        if (!ok) return PyName(null, "Nama hanya boleh huruf, angka, titik, - dan _")
        if (s.startsWith('.') || s.endsWith('.') || ".." in s) return PyName(null, "Titik pada nama tidak valid")
        return PyName(s + lang.ext, null)
    }

    /** Pembatas heredoc yang tidak bentrok dengan isi: PYEOF, PYEOF_1, PYEOF_2, ... */
    fun uniqueDelimiter(body: String, delimiter: String = DELIMITER): String {
        val lines = body.lineSequence().map { it.trimEnd() }.toHashSet()
        if (delimiter !in lines) return delimiter
        var i = 1
        while ("${delimiter}_$i" in lines) i++
        return "${delimiter}_$i"
    }

    /**
     * Mode 1: `python3 << 'PYEOF'` + isi + `PYEOF` (shell: `bash << 'SHEOF'`). Pembatas dikutip sehingga shell
     * tidak mengubah isi ($, `, \). Null bila isi kosong. Catatan: stdin dipakai heredoc, jadi input()/read tidak berfungsi.
     */
    fun heredoc(text: String, lang: TerminalRunLang = TerminalRunLang.PY): String? {
        val body = ScriptLaunch.normalizeEol(text).trimEnd('\n')
        if (body.isBlank()) return null
        val d = uniqueDelimiter(body, lang.delimiter)
        return "${lang.interpreter} << '$d'\n$body\n$d"
    }

    /**
     * Mode 2: `python3 nama.py` (shell: `bash nama.sh`), dijalankan dari folder berkas lewat subshell sehingga
     * folder aktif Terminal tidak berubah dan berkas relatif tetap benar. [guestPath] jalur absolut di Debian.
     */
    fun fileCommand(guestPath: String, lang: TerminalRunLang = TerminalRunLang.PY): String {
        val i = guestPath.lastIndexOf('/')
        val dir = if (i <= 0) "/" else guestPath.substring(0, i)
        val name = guestPath.substring(i + 1)
        return "(cd ${ScriptLaunch.shellQuote(dir)} && ${lang.interpreter} ${ScriptLaunch.shellQuote(name)})"
    }

    /** Pratinjau singkat perintah untuk dialog (aturan potong seragam: [CommandBlockSpec.clip]). */
    fun preview(command: String, maxLines: Int = CommandBlockSpec.PREVIEW_MAX_LINES): String =
        CommandBlockSpec.clip(command, maxLines)
}
