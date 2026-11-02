package com.molina.core.common

/**
 * Preset cepat di form Unduhan. [apply] hanya mengubah kolom milik preset (mode, kualitas, subtitle, sampul, poster);
 * batas kecepatan dan folder tidak pernah disentuh karena itu pilihan jaringan pengguna, bukan gaya unduhan.
 * Murni JVM (tanpa Android) supaya bisa diuji.
 */
enum class DownloadPreset(val label: String) {
    /** Home Theater: 1080p MP4, subtitle Indonesia + Inggris disematkan, poster + info untuk rak Molina Cinema. */
    CINEMA("Cinema"),

    /** Hemat ruang: 480p MP4 tanpa subtitle dan tanpa poster. */
    HEMAT("Hemat"),

    /** Musik: MP3 320k dengan sampul + tag. */
    MUSIK("Musik");

    fun apply(c: DownloadChoices): DownloadChoices = when (this) {
        CINEMA -> c.copy(video = true, height = 1080, videoExt = "mp4", subs = listOf("id", "en"), subsMode = "embed", sidecar = true)
        HEMAT -> c.copy(video = true, height = 480, videoExt = "mp4", subs = emptyList(), sidecar = false)
        MUSIK -> c.copy(video = false, audioFmt = "mp3", bitrate = "320k", cover = true)
    }

    /** True bila [c] sedang persis memakai preset ini (chip tampil terpilih). */
    fun matches(c: DownloadChoices): Boolean = when (this) {
        CINEMA -> c.video && c.height == 1080 && c.videoExt == "mp4" && c.subs == listOf("id", "en") && c.subsMode == "embed" && c.sidecar
        HEMAT -> c.video && c.height == 480 && c.videoExt == "mp4" && c.subs.isEmpty() && !c.sidecar
        MUSIK -> !c.video && c.audioFmt == "mp3" && c.bitrate == "320k" && c.cover
    }

    companion object {
        const val HINT = "Cinema = 1080p MP4 + subtitle ID/EN + poster · Hemat = 480p MP4 · Musik = MP3 320k + sampul"

        fun active(c: DownloadChoices): DownloadPreset? = values().firstOrNull { it.matches(c) }
    }
}
