package com.molina.cinema

/**
 * [RES-CHIP] Label resolusi ringkas untuk indikator di bilah atas pemutar, dari ukuran bingkai video (video-params/w, /h).
 * Sisi panjang dan sisi pendek sama-sama dicoba (bingkai sinema 1920x800 dan 4:3 1440x1080 tetap terbaca 1080p),
 * dengan toleransi 4% untuk bingkai terpotong (1916x1076). Di bawah 720 dipakai sisi pendek apa adanya (mis. 540p, 360p).
 * [tier] memberi aksen warna (0 redup, 1 putih, 2 emas); [detail] merangkai teks ketuk: label+fps, codec, ukuran tepat.
 * Murni JVM, tanpa Android.
 */
object ResLabel {
    private fun ge(v: Int, t: Int): Boolean = v * 100L >= t * 96L

    // -1 = belum diketahui, 0 = di bawah 720, 1 = 720p, 2 = 1080p, 3 = 1440p, 4 = 4K, 5 = 8K
    private fun level(w: Int, h: Int): Int {
        val s = minOf(w, h)
        val l = maxOf(w, h)
        return when {
            s <= 0 -> -1
            ge(l, 7680) || ge(s, 4320) -> 5
            ge(l, 3840) || ge(s, 2160) -> 4
            ge(l, 2560) || ge(s, 1440) -> 3
            ge(l, 1920) || ge(s, 1080) -> 2
            ge(l, 1280) || ge(s, 720) -> 1
            else -> 0
        }
    }

    /** Label (mis. "1080p", "4K") atau kosong bila ukuran belum diketahui. */
    fun of(w: Int, h: Int): String = when (level(w, h)) {
        -1 -> ""
        5 -> "8K"
        4 -> "4K"
        3 -> "1440p"
        2 -> "1080p"
        1 -> "720p"
        else -> "${minOf(w, h)}p"
    }

    /** Aksen warna chip: 2 = 4K ke atas (emas), 1 = 1080p/1440p (putih), 0 = 720p ke bawah atau belum diketahui (redup). */
    fun tier(w: Int, h: Int): Int = when (level(w, h)) {
        4, 5 -> 2
        2, 3 -> 1
        else -> 0
    }

    /** Akhiran fps hanya untuk laju tinggi (di atas 30): 59.94 -> "60", 50 -> "50"; 24/25/30 atau tak masuk akal -> "". */
    fun fpsSuffix(fps: Double): String {
        if (!fps.isFinite() || fps < 1.0 || fps > 240.0) return ""
        val r = Math.round(fps).toInt()
        return if (r > 30) r.toString() else ""
    }

    /** Nama codec ramah dari video-format mpv; kosong bila tidak diketahui. */
    fun codecName(raw: String?): String {
        val c = raw?.trim()?.lowercase().orEmpty()
        return when (c) {
            "" -> ""
            "h264" -> "H.264"
            "hevc", "h265" -> "HEVC"
            "mpeg2video" -> "MPEG-2"
            "mpeg4" -> "MPEG-4"
            "vc1" -> "VC-1"
            else -> c.uppercase()
        }
    }

    /** [RES-HDR] Lencana HDR dari video-params/gamma dan /colormatrix mpv: pq -> "HDR", hlg -> "HLG", dolbyvision -> "DV"; SDR atau tak dikenal -> "". */
    fun hdrName(gamma: String?, matrix: String?): String {
        if (matrix?.trim()?.lowercase() == "dolbyvision") return "DV"
        return when (gamma?.trim()?.lowercase().orEmpty()) {
            "pq" -> "HDR"
            "hlg" -> "HLG"
            else -> ""
        }
    }

    /** Teks ketuk: "1080p60 \u00B7 HEVC \u00B7 1920\u00D71080"; bagian yang tidak diketahui dilewati. */
    fun detail(w: Int, h: Int, fps: Double, codec: String?, hdr: String = ""): String {
        val label = of(w, h)
        if (label.isEmpty()) return ""
        val parts = ArrayList<String>(4)
        parts.add(label + fpsSuffix(fps))
        codecName(codec).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        if (hdr.isNotEmpty()) parts.add(hdr) // [RES-HDR]
        parts.add("$w\u00D7$h")
        return parts.joinToString(" \u00B7 ")
    }
}
