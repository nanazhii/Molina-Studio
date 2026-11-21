package com.molina.cinema

/**
 * [E-3c-1] Satu-satunya aturan memilih varian video terhadap batas tinggi kualitas. Murni JVM (tanpa Android/JNI).
 *
 * Aturan (sama dengan perilaku lama di MPVActivity.switchVariantNoReload dan applyQualityLockOn):
 * - pilih varian dengan tinggi tertinggi yang tidak melebihi [maxHeight];
 * - bila semua varian lebih tinggi dari batas, pilih yang terendah (fallback);
 * - seri tinggi: varian yang lebih dulu dalam daftar menang; daftar kosong menghasilkan null.
 * Pemanggil yang menangani AUTO (tanpa batas) dan AUDIO (tanpa video) memutuskan sebelum memanggil ini.
 *
 * [DIAG-QUALITY] [decide] mengembalikan juga alasan, dan [describe] meringkasnya jadi satu baris log
 * (hanya tinggi, codec, fps, bitrate, id trek; tidak pernah URL atau path).
 */
object VariantPicker {
    enum class Reason { WITHIN_CAP, FALLBACK_LOWEST, NO_VARIANT }

    class Decision(val variant: VideoVariant?, val reason: Reason)

    fun decide(variants: List<VideoVariant>, maxHeight: Int): Decision {
        val within = variants.filter { it.height <= maxHeight }.maxByOrNull { it.height }
        if (within != null) return Decision(within, Reason.WITHIN_CAP)
        val lowest = variants.minByOrNull { it.height }
        return Decision(lowest, if (lowest == null) Reason.NO_VARIANT else Reason.FALLBACK_LOWEST)
    }

    fun pick(variants: List<VideoVariant>, maxHeight: Int): VideoVariant? = decide(variants, maxHeight).variant

    /** Satu baris untuk tab Log: batas, kandidat (maks 12), hasil, dan alasan. */
    fun describe(variants: List<VideoVariant>, maxHeight: Int, d: Decision): String {
        val shown = variants.take(12).joinToString(",") { if (it.height > 0) "${it.height}p" else "?p" }
        val more = if (variants.size > 12) ",+${variants.size - 12}" else ""
        val chosen = d.variant?.let { "${VideoQuality.label(it)} id=${it.mpvId}" } ?: "tidak ada"
        val why = when (d.reason) {
            Reason.WITHIN_CAP -> "tertinggi <= batas"
            Reason.FALLBACK_LOWEST -> "semua di atas batas, ambil terendah"
            Reason.NO_VARIANT -> "tanpa varian"
        }
        return "pilih varian: batas=${maxHeight}p kandidat=[$shown$more] dipilih=$chosen ($why)"
    }
}
