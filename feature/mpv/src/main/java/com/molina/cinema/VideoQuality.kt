package com.molina.cinema

import java.util.Locale

/** [VIDEO-QUALITY] Satu varian/track video (mis. varian HLS) dengan metadata nyata dari mpv. */
data class VideoVariant(
    val mpvId: Int,
    val width: Int,
    val height: Int,
    val fps: Double,
    val bitrateBps: Long,
    val codec: String?,
)

/** [VIDEO-QUALITY] Kondisi pemutaran nyata saat ini. Nilai 0 = tidak diketahui. */
data class PlayConditions(
    val outWidth: Int,
    val outHeight: Int,
    val fps: Double,
    val hwdec: String,
    val droppedFrames: Long,
    /** bit/detik; 0 bila tidak sedang mengunduh (cache penuh) atau bukan jaringan. */
    val netSpeedBps: Long,
    val buffering: Boolean,
    val displayShortSidePx: Int,
)

/** [VIDEO-QUALITY] Logika murni (tanpa Android/JNI) agar mudah diuji. */
object VideoQuality {
    fun mbps(bps: Long): String = String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)

    fun codecName(c: String?): String = when (c?.lowercase()) {
        null, "" -> ""
        "h264" -> "H.264"
        "hevc" -> "H.265"
        "av1" -> "AV1"
        "vp9" -> "VP9"
        else -> c?.uppercase().orEmpty()
    }

    fun label(v: VideoVariant): String = listOfNotNull(
        if (v.height > 0) "${v.height}p" else "?",
        codecName(v.codec).ifEmpty { null },
        if (v.fps > 0) String.format(Locale.US, "%.0f fps", v.fps) else null,
        if (v.bitrateBps > 0) mbps(v.bitrateBps) else null,
    ).joinToString(" \u00B7 ")

    /**
     * Varian yang paling cocok dengan kondisi nyata (mengembalikan mpvId, null bila tidak ada varian):
     * - tinggi tidak melebihi sisi pendek layar (toleransi 10%);
     * - dekoder perangkat lunak (hwdec "no") dibatasi 720p;
     * - bila kecepatan unduh terukur, bitrate varian maksimal 75% dari kecepatan itu;
     * - bila sedang buffering, turun satu tingkat dari varian yang sedang diputar.
     */
    fun recommend(variants: List<VideoVariant>, c: PlayConditions, currentId: Int): Int? {
        if (variants.isEmpty()) return null
        val sorted = variants.sortedWith(compareBy({ it.height }, { it.bitrateBps }))
        var cap: List<VideoVariant> = sorted
        if (c.displayShortSidePx > 0)
            cap = cap.filter { it.height <= c.displayShortSidePx * 11 / 10 }.ifEmpty { listOf(sorted.first()) }
        if (c.hwdec == "no")
            cap = cap.filter { it.height <= 720 }.ifEmpty { listOf(cap.first()) }
        if (c.netSpeedBps > 0)
            cap = cap.filter { it.bitrateBps <= 0 || it.bitrateBps * 4 <= c.netSpeedBps * 3 }.ifEmpty { listOf(cap.first()) }
        var best = cap.last()
        if (c.buffering) {
            val cur = cap.indexOfFirst { it.mpvId == currentId }
            if (cur > 0) best = cap[cur - 1] else if (cur == 0) best = cap[0]
        }
        return best.mpvId
    }
}
