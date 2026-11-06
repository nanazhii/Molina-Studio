package com.molina.cinema

import kotlin.math.roundToLong

/*
 * [SUBTITLE] Codec ASS/SSA minimal + konversi SRT<->ASS. Kotlin murni.
 * Parse: hanya baris Dialogue di [Events]; tag override {..} dibuang, \N \n \h diterjemahkan.
 * Write: ASS valid dengan satu gaya "Default" (dari AssStyle), siap dirender libass di libmpv Molina.
 * Catatan kehilangan data (disengaja): konversi ke SRT membuang gaya, posisi, dan efek ASS.
 */

/** Gaya ASS tunggal. Warna "#RRGGBB" dikonversi ke format ASS &HAABBGGRR. */
internal data class AssStyle(
    val fontName: String = "Arial",
    val fontSize: Int = 48,
    val primaryHex: String = "#FFFFFF",
    val outlineHex: String = "#000000",
    val outline: Double = 2.0,
    val shadow: Double = 1.0,
    val bold: Boolean = false,
) {
    companion object {
        /** Ambil warna/outline/bayangan/tebal dari preset Gaya Subtitle; sisanya bawaan. */
        fun fromPreset(p: SubStylePreset): AssStyle {
            val m = p.props.toMap()
            return AssStyle(
                primaryHex = m["sub-color"] ?: "#FFFFFF",
                outlineHex = m["sub-border-color"] ?: "#000000",
                outline = m["sub-border-size"]?.toDoubleOrNull() ?: 2.0,
                shadow = m["sub-shadow-offset"]?.toDoubleOrNull() ?: 1.0,
                bold = m["sub-bold"] == "yes",
            )
        }
    }
}

internal enum class SubFormat { SRT, ASS, UNKNOWN }

internal object AssCodec {
    private const val MAX_CUES = 50_000
    private val OVERRIDE = Regex("""\{[^}]*}""")
    private val DEFAULT_FORMAT = listOf("Layer", "Start", "End", "Style", "Name", "MarginL", "MarginR", "MarginV", "Effect", "Text")

    /** "H:MM:SS.cc" -> ms. Pecahan 1-3 digit diterima. null bila bukan waktu. */
    internal fun parseTime(s: String): Long? {
        val m = Regex("""^\s*(\d+):(\d{1,2}):(\d{1,2})[.,](\d{1,3})\s*$""").matchEntire(s) ?: return null
        val (h, mi, se, fr) = m.destructured
        val ms = when (fr.length) { 1 -> fr.toLong() * 100; 2 -> fr.toLong() * 10; else -> fr.toLong() }
        return h.toLong() * 3_600_000 + mi.toLong() * 60_000 + se.toLong() * 1_000 + ms
    }

    internal fun formatTime(ms: Long): String {
        val cs = (ms.coerceAtLeast(0) / 10.0).roundToLong() // ASS hanya sentisekon
        return "%d:%02d:%02d.%02d".format(cs / 360_000, cs / 6_000 % 60, cs / 100 % 60, cs % 100)
    }

    /** Teks dialog ASS -> teks polos: buang {tag}, \N/\n jadi baris baru, \h jadi spasi. */
    internal fun plain(text: String): String =
        text.replace(OVERRIDE, "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").trim()

    fun parse(input: String): List<SubCue> {
        val out = ArrayList<SubCue>()
        var inEvents = false
        var format = DEFAULT_FORMAT
        for (raw in input.removePrefix("\uFEFF").lineSequence()) {
            if (out.size >= MAX_CUES) break
            val line = raw.trim()
            if (line.startsWith("[")) { inEvents = line.equals("[Events]", ignoreCase = true); continue }
            if (!inEvents) continue
            when {
                line.startsWith("Format:", ignoreCase = true) ->
                    format = line.substringAfter(':').split(',').map { it.trim() }
                line.startsWith("Dialogue:", ignoreCase = true) -> {
                    val fields = line.substringAfter(':').trimStart().split(',', limit = format.size)
                    if (fields.size < format.size) continue
                    val iS = format.indexOfFirst { it.equals("Start", true) }
                    val iE = format.indexOfFirst { it.equals("End", true) }
                    val iT = format.indexOfFirst { it.equals("Text", true) }
                    if (iS < 0 || iE < 0 || iT < 0) continue
                    val start = parseTime(fields[iS]) ?: continue
                    val end = parseTime(fields[iE]) ?: continue
                    val text = plain(fields[iT])
                    if (text.isNotEmpty()) out.add(SubCue(start, maxOf(end, start), text))
                }
            }
        }
        return out.sortedBy { it.startMs }
    }

    private fun assColor(hex: String): String {
        val h = hex.removePrefix("#")
        if (h.length != 6 || h.any { !it.isLetterOrDigit() }) return "&H00FFFFFF"
        return "&H00" + (h.substring(4, 6) + h.substring(2, 4) + h.substring(0, 2)).uppercase() // BGR
    }

    // '{' dan '}' akan dibaca sebagai tag override oleh libass; diganti agar teks tidak hilang.
    private fun escapeText(t: String) = t.replace('{', '(').replace('}', ')').replace("\r", "").replace("\n", "\\N")

    fun write(cues: List<SubCue>, style: AssStyle = AssStyle(), title: String = "Molina"): String = buildString {
        append("[Script Info]\n")
        append("Title: ").append(title.replace('\n', ' ')).append('\n')
        append("ScriptType: v4.00+\nWrapStyle: 0\nScaledBorderAndShadow: yes\nPlayResX: 1920\nPlayResY: 1080\n\n")
        append("[V4+ Styles]\n")
        append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, ")
        append("Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
        append("Style: Default,").append(style.fontName.replace(',', ' ')).append(',').append(style.fontSize).append(',')
        append(assColor(style.primaryHex)).append(",&H000000FF,").append(assColor(style.outlineHex)).append(",&H80000000,")
        append(if (style.bold) "-1" else "0").append(",0,0,0,100,100,0,0,1,")
        append(style.outline).append(',').append(style.shadow).append(",2,60,60,50,1\n\n")
        append("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
        for (c in cues)
            append("Dialogue: 0,").append(formatTime(c.startMs)).append(',').append(formatTime(c.endMs))
                .append(",Default,,0,0,0,,").append(escapeText(c.text)).append('\n')
    }
}

internal object SubtitleConvert {
    /** Deteksi dari isi (bukan ekstensi): ASS punya [Script Info]/[Events]; SRT punya "-->". */
    fun detect(raw: String): SubFormat {
        val head = raw.removePrefix("\uFEFF").take(4_000)
        return when {
            head.contains("[Script Info]", ignoreCase = true) || head.contains("[Events]", ignoreCase = true) -> SubFormat.ASS
            head.contains("-->") -> SubFormat.SRT
            else -> SubFormat.UNKNOWN
        }
    }

    fun parseAny(raw: String): List<SubCue> = when (detect(raw)) {
        SubFormat.ASS -> AssCodec.parse(raw)
        SubFormat.SRT -> SrtCodec.parse(raw)
        SubFormat.UNKNOWN -> emptyList()
    }

    /** Konversi isi subtitle ke [target] (SRT atau ASS). null bila format sumber tidak dikenali atau kosong. */
    fun convert(raw: String, target: SubFormat, style: AssStyle = AssStyle()): String? {
        val src = detect(raw)
        if (src == SubFormat.UNKNOWN || target == SubFormat.UNKNOWN) return null
        val cues = parseAny(raw)
        if (cues.isEmpty()) return null
        return if (target == SubFormat.ASS) AssCodec.write(cues, style) else SrtCodec.write(cues)
    }
}
