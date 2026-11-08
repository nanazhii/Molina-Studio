package com.molina.studio.terminal

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper

/**
 * [PASTE-SHAPE] Deteksi bentuk teks yang ditempel ke Terminal: apakah kode Python telanjang (perlu dibungkus
 * `python3 << 'PYEOF'`), heredoc yang sudah lengkap, heredoc tanpa penutup, atau perintah shell biasa.
 * Murni heuristik. Hanya Python telanjang dan heredoc tanpa penutup yang memunculkan dialog; sisanya langsung ditempel.
 */
internal object PasteShape {
    enum class Kind { SHELL, HEREDOC_OK, HEREDOC_OPEN, PYTHON_BARE }
    data class Result(val kind: Kind, val delimiter: String? = null)

    // Pembuka heredoc: << EOF, <<-EOF, << 'EOF', << "EOF", <<\EOF. Here-string (<<<) tidak cocok.
    private val OPENER = Regex("""(?<!<)<<(-?)\s*(?:'([A-Za-z_]\w*)'|"([A-Za-z_]\w*)"|\\?([A-Za-z_]\w*))""")

    private val PY_START = Regex(
        """^(import\s+\w|from\s+[\w.]+\s+import\b|def\s+\w+\s*\(|class\s+\w+|async\s+def\b|@\w|if\s+__name__\s*==|print\s*\(|""" +
            """with\s+.+:\s*$|try:\s*$|for\s+.+\s+in\s+.+:\s*$|while\s+.+:\s*$|if\s+[^\[]+:\s*$)"""
    )

    private val SHELL_CMD = setOf(
        "cd", "ls", "git", "apt", "apt-get", "pkg", "sudo", "export", "echo", "cat", "mkdir", "rm", "cp", "mv",
        "python", "python3", "pip", "pip3", "bash", "sh", "curl", "wget", "nano", "chmod", "source", "set", "unset", "alias"
    )

    fun classify(text: String): Result {
        val lines = text.replace("\r\n", "\n").split('\n')
        if (lines.count { it.isNotBlank() } < 2) return Result(Kind.SHELL) // satu baris: selalu biarkan
        var open: String? = null
        var strip = false
        var seen: String? = null
        for (ln in lines) {
            val o = open
            if (o != null) { // di dalam badan heredoc: hanya cari penutup
                if ((if (strip) ln.trimStart('\t') else ln) == o) open = null
                continue
            }
            val m = OPENER.find(ln) ?: continue
            open = m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[4] }
            strip = m.groupValues[1] == "-"
            seen = open
        }
        if (open != null) return Result(Kind.HEREDOC_OPEN, open)
        if (seen != null) return Result(Kind.HEREDOC_OK, seen)
        return if (looksPython(lines)) Result(Kind.PYTHON_BARE) else Result(Kind.SHELL)
    }

    private fun looksPython(lines: List<String>): Boolean {
        val code = lines.filter { it.isNotBlank() }
        val first = code.firstOrNull() ?: return false
        if (first.startsWith("#!")) return first.contains("python")
        val head = code.firstOrNull { !it.trimStart().startsWith("#") } ?: return false
        val token = head.trim().substringBefore(' ')
        if (token in SHELL_CMD || head.contains("&&") || head.trimEnd().endsWith("\\")) return false
        var starts = 0
        var block = 0
        for ((i, l) in code.withIndex()) {
            val indented = l.startsWith(" ") || l.startsWith("\t")
            if (!indented && PY_START.containsMatchIn(l.trim())) starts++
            if (indented && i > 0 && code[i - 1].trimEnd().endsWith(":")) block++
        }
        return starts >= 1 && starts + block >= 2
    }

    /** Bungkus kode Python; pembatas dipilih yang tidak muncul sebagai baris sendiri. Tanpa newline akhir (Enter oleh pengguna). */
    fun wrap(text: String): String {
        val body = text.replace("\r\n", "\n").trimEnd('\n')
        val used = body.split('\n').toSet()
        var d = "PYEOF"
        var i = 1
        while (d in used) d = "PYEOF" + i++
        return "python3 << '" + d + "'\n" + body + "\n" + d
    }

    fun close(text: String, delimiter: String): String = text.replace("\r\n", "\n").trimEnd('\n') + "\n" + delimiter

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /** Tempel [text] lewat [doPaste]; bila bentuknya perlu dibenahi, tawarkan dulu. Tanpa Activity: tempel apa adanya. */
    fun offer(ctx: Context, text: String, doPaste: (String) -> Unit) {
        val r = classify(text)
        val activity = ctx.findActivity()?.takeIf { !it.isFinishing }
        if (activity == null || r.kind == Kind.SHELL || r.kind == Kind.HEREDOC_OK) { doPaste(text); return }
        val title: String
        val msg: String
        val fixLabel: String
        val fixed: String
        if (r.kind == Kind.PYTHON_BARE) {
            title = "Kode Python terdeteksi"
            msg = "Teks ini tampak seperti skrip Python tanpa pembungkus. Jika ditempel apa adanya, shell akan menjalankannya baris demi baris dan gagal.\n\nBungkus dengan python3 << 'PYEOF' ... PYEOF?"
            fixLabel = "Bungkus"
            fixed = wrap(text)
        } else {
            val d = r.delimiter ?: "EOF"
            title = "Heredoc belum ditutup"
            msg = "Ada pembuka heredoc (" + d + ") tetapi baris penutupnya tidak ada. Shell akan menunggu di prompt lanjutan (>).\n\nTambahkan baris " + d + " di akhir?"
            fixLabel = "Tambah " + d
            fixed = close(text, d)
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(fixLabel) { _, _ -> doPaste(fixed) }
            .setNegativeButton("Apa adanya") { _, _ -> doPaste(text) }
            .setNeutralButton("Batal", null)
            .show()
    }
}
