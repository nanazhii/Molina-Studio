package com.molina.cinema

/**
 * [E-3c-1] Satu-satunya aturan memilih varian video terhadap batas tinggi kualitas. Murni JVM (tanpa Android/JNI).
 *
 * Aturan (sama dengan perilaku lama di MPVActivity.switchVariantNoReload dan applyQualityLockOn):
 * - pilih varian dengan tinggi tertinggi yang tidak melebihi [maxHeight];
 * - bila semua varian lebih tinggi dari batas, pilih yang terendah (fallback);
 * - seri tinggi: varian yang lebih dulu dalam daftar menang; daftar kosong menghasilkan null.
 * Pemanggil yang menangani AUTO (tanpa batas) dan AUDIO (tanpa video) memutuskan sebelum memanggil ini.
 */
object VariantPicker {
    fun pick(variants: List<VideoVariant>, maxHeight: Int): VideoVariant? =
        variants.filter { it.height <= maxHeight }.maxByOrNull { it.height }
            ?: variants.minByOrNull { it.height }
}
