package com.molina.cinema

import android.content.Context

/**
 * [PREF-SPECTRUM] Pintu publik Settings Molina -> setelan visualizer spektrum pemutar. SpectrumPrefs tetap internal di modul
 * pemutar; keduanya memakai kunci yang sama (audio_spectrum, bawaan hidup), sehingga tombol di pemutar dan sakelar di
 * Pengaturan selalu sinkron (satu sumber kebenaran).
 */
object SpectrumSwitch {
    fun enabled(ctx: Context): Boolean = SpectrumPrefs.enabled(ctx)

    fun setEnabled(ctx: Context, on: Boolean) = SpectrumPrefs.set(ctx, on)
}
