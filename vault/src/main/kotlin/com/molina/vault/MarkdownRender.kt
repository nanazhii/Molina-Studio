package com.molina.vault

// MOLINA-H4-PREVIEW
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan

/**
 * Perender markdown ringan untuk pratinjau di dalam Vault. Tanpa dependensi dan tanpa jaringan:
 * tautan hanya ditampilkan sebagai teks (tidak bisa diketuk) dan gambar tidak dimuat.
 */
internal object MarkdownRender {
    private val AMBER = Color.parseColor("#FFB300")
    private val CODE_BG = Color.parseColor("#1E1E22")
    private val MUTED = Color.parseColor("#9E9E9E")
    private const val FENCE = "\u0060\u0060\u0060"
    private const val MAX_INLINE = 2000

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val QUOTE = Regex("^\\s*>\\s?(.*)$")
    private val LIST = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
    private val INLINE = Regex("`([^`]+)`|\\*\\*(.+?)\\*\\*|\\*(?=\\S)(.+?)(?<=\\S)\\*|!\\[([^\\]]*)\\]\\(([^)]*)\\)|\\[([^\\]]+)\\]\\(([^)]*)\\)")

    fun render(src: String): CharSequence {
        val out = SpannableStringBuilder()
        var fenced = false
        for (rawLine in src.split("\n")) {
            val line = rawLine.trimEnd('\r')
            val trimmed = line.trimStart()
            if (trimmed.startsWith(FENCE)) { fenced = !fenced; continue }
            if (fenced) {
                add(out, line + "\n", TypefaceSpan("monospace"), BackgroundColorSpan(CODE_BG))
                continue
            }
            val heading = HEADING.find(line)
            val quote = QUOTE.find(line)
            val item = LIST.find(line)
            when {
                heading != null -> {
                    val level = heading.groupValues[1].length
                    val size = when (level) { 1 -> 1.6f; 2 -> 1.35f; 3 -> 1.2f; else -> 1.1f }
                    add(out, inline(heading.groupValues[2].trim()),
                        RelativeSizeSpan(size), StyleSpan(Typeface.BOLD),
                        ForegroundColorSpan(if (level <= 2) AMBER else Color.WHITE))
                    out.append('\n')
                }
                RULE.matches(line) -> add(out, "────────────────\n", ForegroundColorSpan(MUTED))
                trimmed.startsWith("|") -> add(out, line + "\n", TypefaceSpan("monospace"))
                quote != null -> {
                    add(out, "▎ ", ForegroundColorSpan(AMBER))
                    add(out, inline(quote.groupValues[1]), ForegroundColorSpan(MUTED), StyleSpan(Typeface.ITALIC))
                    out.append('\n')
                }
                item != null -> {
                    val depth = (item.groupValues[1].replace("\t", "    ").length / 2).coerceAtMost(6)
                    val marker = item.groupValues[2]
                    val bullet = if (marker[0].isDigit()) marker + " " else "• "
                    add(out, "  ".repeat(depth) + bullet, ForegroundColorSpan(AMBER))
                    out.append(inline(item.groupValues[3]))
                    out.append('\n')
                }
                line.isBlank() -> out.append('\n')
                else -> {
                    out.append(inline(line))
                    out.append('\n')
                }
            }
        }
        return out
    }

    private fun inline(s: String): SpannableStringBuilder {
        if (s.length > MAX_INLINE) return SpannableStringBuilder(s)
        val out = SpannableStringBuilder()
        var last = 0
        for (m in INLINE.findAll(s)) {
            if (m.range.first > last) out.append(s, last, m.range.first)
            val g = m.groups
            when {
                g[1] != null -> add(out, m.groupValues[1], TypefaceSpan("monospace"), BackgroundColorSpan(CODE_BG))
                g[2] != null -> add(out, inline(m.groupValues[2]), StyleSpan(Typeface.BOLD))
                g[3] != null -> add(out, inline(m.groupValues[3]), StyleSpan(Typeface.ITALIC))
                g[4] != null -> add(out, "[gambar: " + m.groupValues[4] + "]", ForegroundColorSpan(MUTED))
                else -> {
                    add(out, m.groupValues[6], UnderlineSpan(), ForegroundColorSpan(AMBER))
                    if (m.groupValues[7].isNotEmpty()) add(out, " (" + m.groupValues[7] + ")", ForegroundColorSpan(MUTED))
                }
            }
            last = m.range.last + 1
        }
        if (last < s.length) out.append(s, last, s.length)
        return out
    }

    private fun add(out: SpannableStringBuilder, t: CharSequence, vararg spans: Any) {
        val start = out.length
        out.append(t)
        if (out.length == start) return
        for (sp in spans) out.setSpan(sp, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
