package com.molina.cinema

/**
 * [E-3c-2] Satu-satunya aturan mode kualitas efektif pemutar. Murni JVM (tanpa Android/JNI/core:stream).
 *
 * Aturan (sama dengan perilaku lama di MPVActivity.effectiveQuality dan setter useAudioUI):
 * - audio-only aktif bila [forcedAudioOnly] (tombol musik atau jalur non-web) ATAU [useAudioUI] (UI audio nyata);
 * - saat audio-only aktif, ia mengalahkan kunci resolusi tersimpan;
 * - bila tidak, mode efektif adalah kunci tersimpan.
 * [effective] generik agar tidak bergantung pada enum kualitas di core:stream.
 */
object QualityRule {
    fun audioOnlyActive(forcedAudioOnly: Boolean, useAudioUI: Boolean): Boolean = forcedAudioOnly || useAudioUI

    fun <Q> effective(forcedAudioOnly: Boolean, useAudioUI: Boolean, audio: Q, stored: Q): Q =
        if (audioOnlyActive(forcedAudioOnly, useAudioUI)) audio else stored
}
