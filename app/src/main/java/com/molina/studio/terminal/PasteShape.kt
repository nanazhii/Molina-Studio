package com.molina.studio.terminal

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContextWrapper
import android.graphics.drawable.GradientDrawable
import android.widget.Toast
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView
import com.molina.studio.ui.MolinaTheme

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
    fun wrap(text: String, base: String = "PYEOF"): String { // [PS-UI2]
        val body = text.replace("\r\n", "\n").trimEnd('\n')
        val used = body.split('\n').toSet()
        var d = base
        var i = 1
        while (d in used) d = base + i++
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

    private class Option(val label: String, val text: String)

    /** [PS-UI2] Ganti pembatas pada pembuka heredoc yang belum tertutup, lalu tutup dengan pembatas baru. Null bila bentrok. */
    private fun renameOpener(text: String, from: String, to: String): String? {
        val lines = text.replace("\r\n", "\n").split('\n').toMutableList()
        if (to in lines) return null // "to" sudah muncul sebagai baris isi: akan menutup heredoc terlalu dini
        for (i in lines.indices.reversed()) {
            val m = OPENER.find(lines[i]) ?: continue
            val g = m.groups[2] ?: m.groups[3] ?: m.groups[4] ?: continue
            if (g.value != from) continue
            lines[i] = lines[i].replaceRange(g.range, to)
            return lines.joinToString("\n").trimEnd('\n') + "\n" + to
        }
        return null
    }

    /** Pratinjau teks yang akan ditempel: 3 baris pertama + baris terakhir (penutup heredoc), baris panjang dipotong. */
    private fun preview(text: String): String {
        val lines = text.split('\n').map { if (it.length > 56) it.take(55) + "\u2026" else it }
        if (lines.size <= 4) return lines.joinToString("\n")
        return lines.take(3).joinToString("\n") + "\n\u2026 (+" + (lines.size - 4) + " baris lagi)\n" + lines.last()
    }

    /**
     * Tempel [text] lewat [doPaste]; bila bentuknya perlu dibenahi, tawarkan dulu. Dialog memuat pilihan varian
     * (Python telanjang: Bungkus PYEOF / Bungkus EOF; heredoc terbuka: Tambah pembatas / Ganti ke pembatas lain),
     * pratinjau, dan tombol Salin. Mati/tanpa Activity: tempel apa adanya.
     */
    fun offer(ctx: Context, text: String, doPaste: (String) -> Unit) {
        if (!TerminalPrefs.pasteShape) { doPaste(text); return } // sakelar Setelan Terminal I/O
        val r = classify(text)
        val activity = ctx.findActivity()?.takeIf { !it.isFinishing }
        if (activity == null || r.kind == Kind.SHELL || r.kind == Kind.HEREDOC_OK) { doPaste(text); return }
        val title: String
        val msg: String
        val opts: List<Option>
        if (r.kind == Kind.PYTHON_BARE) {
            title = "Kode Python terdeteksi"
            msg = "Tanpa pembungkus, shell menjalankan teks ini baris demi baris dan gagal. Pilih pembungkus heredoc:"
            opts = listOf(Option("Bungkus PYEOF", wrap(text, "PYEOF")), Option("Bungkus EOF", wrap(text, "EOF")))
        } else {
            val d = r.delimiter ?: "EOF"
            val alt = if (d == "EOF") "PYEOF" else "EOF"
            title = "Heredoc belum ditutup"
            msg = "Baris penutup " + d + " tidak ada, shell akan menunggu di prompt lanjutan (>). Pilih perbaikan:"
            opts = listOfNotNull(
                Option("Tambah " + d, close(text, d)),
                renameOpener(text, d, alt)?.let { Option("Ganti ke " + alt, it) }
            )
        }
        val density = activity.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()
        val ink = 0xFF0A0A0F.toInt()
        fun bg(selected: Boolean) = GradientDrawable().apply {
            cornerRadius = px(8).toFloat()
            setColor(if (selected) MolinaTheme.BRAND_ARGB else ink)
            setStroke(px(1), MolinaTheme.BRAND_ARGB)
        }
        fun chip(label: String): TextView = TextView(activity).apply {
            this.text = label
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(px(10), px(7), px(10), px(7))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { rightMargin = px(8) }
        }
        var sel = 0
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
        }
        box.addView(TextView(activity).apply { this.text = msg; textSize = 14f })
        val chips = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = px(10) }
        }
        val pv = TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(MolinaTheme.BRAND_ARGB)
            setBackgroundColor(ink)
            setPadding(px(10), px(8), px(10), px(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = px(10) }
        }
        val chipViews = opts.map { chip(it.label) }
        fun refresh() {
            chipViews.forEachIndexed { i, v ->
                v.background = bg(i == sel)
                v.setTextColor(if (i == sel) ink else MolinaTheme.BRAND_ARGB)
            }
            pv.text = preview(opts[sel].text)
        }
        chipViews.forEachIndexed { i, v -> v.setOnClickListener { sel = i; refresh() }; chips.addView(v) }
        box.addView(chips)
        box.addView(pv)
        val copy = chip("Salin versi terbungkus").apply {
            background = bg(false)
            setTextColor(MolinaTheme.BRAND_ARGB)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = px(10) }
            setOnClickListener {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("molina-paste", opts[sel].text))
                Toast.makeText(activity, "Versi terbungkus disalin", Toast.LENGTH_SHORT).show()
            }
        }
        box.addView(copy)
        refresh()
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Tempel") { _, _ -> doPaste(opts[sel].text) }
            .setNegativeButton("Apa adanya") { _, _ -> doPaste(text) }
            .setNeutralButton("Batal", null)
            .show()
    }
}
