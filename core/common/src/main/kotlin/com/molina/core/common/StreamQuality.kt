package com.molina.core.common

import java.util.concurrent.ConcurrentHashMap

/** [QUALITY] Batas resolusi video YouTube. AUTO = perilaku lama (stream gabungan terbaik, tanpa audio terpisah). */
enum class VideoQuality(val label: String, val maxHeight: Int, val hint: String) {
    AUTO("Otomatis", 0, "Putar cepat memakai stream gabungan; resolusi mengikuti yang tersedia (umumnya rendah)."),
    P480("480p", 480, "Hemat data. Video dan audio digabung oleh pemutar."),
    P576("576p", 576, "Menengah. Video dan audio digabung oleh pemutar."),
    P720("720p HD", 720, "Tajam, cukup ringan untuk Android 8."),
    P1080("Full HD 1080p", 1080, "Paling tajam; butuh dekoder H.264 dan koneksi cepat."),
    AUDIO("Hanya audio", 0, "Putar suaranya saja (tanpa mengunduh video): hemat data dan baterai, cocok untuk musik dan podcast."); // [AUDIO-ONLY]

    /** Tinggi tersedia terbaik: tertinggi <= batas; bila semuanya di atas batas, yang terendah. Null bila kosong. */
    fun chooseHeight(heights: Collection<Int>): Int? {
        val h = heights.filter { it > 0 }.distinct().sorted()
        if (h.isEmpty()) return null
        if (maxHeight <= 0) return h.last()
        return h.lastOrNull { it <= maxHeight } ?: h.first()
    }

    companion object {
        fun fromName(n: String?): VideoQuality = values().firstOrNull { it.name == n } ?: AUTO
    }
}

/** [QUALITY] Pilihan aktif + peta URL video -> URL audio terpisah (YouTube 720p ke atas = video-only + audio). */
object StreamQuality {
    @Volatile var current: VideoQuality = VideoQuality.AUTO
    /** [QUALITY-LOCK] Tinggi & status fallback hasil resolve terakhir (diisi resolver) agar UI bisa memberi tahu pengguna. */
    @Volatile var lastHeight: Int = 0
    @Volatile var lastFallback: Boolean = false
    /** [QUALITY-LOCK] Dipasang aplikasi untuk menyimpan pilihan secara permanen. */
    @Volatile var onChange: ((VideoQuality) -> Unit)? = null
    fun set(v: VideoQuality) { current = v; onChange?.invoke(v) }
    private val audio = ConcurrentHashMap<String, String>()

    fun rememberAudio(videoUrl: String, audioUrl: String) {
        if (audio.size >= 64) audio.clear()
        audio[videoUrl] = audioUrl
    }

    fun audioFor(videoUrl: String?): String? = videoUrl?.let { audio[it] }
}
