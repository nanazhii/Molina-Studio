package com.molina.studio.terminal

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper

/**
 * Penjaga tempel Terminal. Teks dari papan klip bisa disusupi (halaman web yang menyalin perintah
 * berbeda dari yang tampak, karakter kendali tersembunyi, pola unduh-lalu-jalankan). Teks aman
 * langsung ditempel; teks berisiko ditampilkan dulu dan butuh persetujuan eksplisit.
 * Murni heuristik: tidak menjamin aman, hanya memberi jeda sadar sebelum eksekusi.
 */
object PasteGuard {
    private const val PREVIEW_MAX = 600

    private val RULES: List<Pair<Regex, String>> = listOf(
        Regex("""\b(curl|wget)\b[^\n|;]*\|\s*(sudo\s+)?(ba|z|da|k)?sh\b""", RegexOption.IGNORE_CASE)
            to "mengunduh lalu langsung menjalankan skrip (curl/wget ke shell)",
        Regex("""([$]\(|`|<\()\s*(curl|wget)\b""", RegexOption.IGNORE_CASE)
            to "menjalankan hasil unduhan lewat substitusi perintah",
        Regex("""\bbase64\s+(-d|--decode)\b[^\n]*\|\s*(sudo\s+)?(ba|z|da|k)?sh\b""", RegexOption.IGNORE_CASE)
            to "menjalankan kode tersandi base64",
        Regex("""/dev/(tcp|udp)/""")
            to "koneksi jaringan mentah (pola reverse shell)",
        Regex("""\b(nc|ncat|netcat)\b[^\n]*\s-[a-z]*e\b""", RegexOption.IGNORE_CASE)
            to "netcat dengan eksekusi (pola reverse shell)",
        Regex("""\brm\s+(-[a-zA-Z-]+\s+)*-[a-zA-Z]*[rR][a-zA-Z]*\s+(--no-preserve-root\s+)?(/|~|[$]HOME|/sdcard|/storage)(/\*|\*)?(\s|$)""")
            to "penghapusan rekursif di lokasi penting",
    )

    private fun isHiddenControl(c: Char): Boolean =
        (c.isISOControl() && c != '\n' && c != '\t') ||
            c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069'

    /** Daftar alasan peringatan; kosong berarti tidak ada pola berisiko yang dikenali. */
    fun scan(text: String): List<String> {
        val out = ArrayList<String>()
        for ((re, why) in RULES) if (re.containsMatchIn(text)) out += why
        if (text.any(::isHiddenControl)) out += "memuat karakter kendali atau arah teks tersembunyi"
        return out
    }

    private fun visible(text: String): String {
        val sb = StringBuilder()
        for (c in text.take(PREVIEW_MAX)) {
            if (isHiddenControl(c)) sb.append("<U+%04X>".format(c.code)) else sb.append(c)
        }
        if (text.length > PREVIEW_MAX) sb.append("\n... (${text.length - PREVIEW_MAX} karakter lagi)")
        return sb.toString()
    }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /** Tempel [text] lewat [doPaste]. Bila berisiko, minta persetujuan; tanpa Activity ditolak (gagal aman). */
    fun pasteGuarded(ctx: Context, text: String, doPaste: (String) -> Unit) {
        val reasons = scan(text)
        if (reasons.isEmpty()) { doPaste(text); return }
        val activity = ctx.findActivity()?.takeIf { !it.isFinishing } ?: return
        val msg = buildString {
            append("Peringatan:\n")
            reasons.forEach { append("\u2022 ").append(it).append('\n') }
            append("\nTeks yang akan ditempel:\n\n").append(visible(text))
        }
        AlertDialog.Builder(activity)
            .setTitle("Tempel perintah berisiko?")
            .setMessage(msg)
            .setPositiveButton("Batal", null)
            .setNegativeButton("Tetap tempel") { _, _ -> doPaste(text) }
            .show()
    }
}
