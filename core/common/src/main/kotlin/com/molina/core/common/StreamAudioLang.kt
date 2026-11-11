package com.molina.core.common

/** [AUDIO-LANG] Satu trek bahasa audio yang tersedia pada video aktif. [key] = kode bahasa ("id", "en", ...). */
data class AudioChoice(val key: String, val label: String)

/**
 * [AUDIO-LANG] Pilihan bahasa audio YouTube. Kosong ("") = ikuti trek asli video (perilaku lama).
 * [lastTracks]/[lastActive] diisi resolver tiap resolve agar UI pemutar bisa menampilkan daftar trek.
 */
object StreamAudioLang {
    @Volatile var preferred: String = ""
    @Volatile var onChange: ((String) -> Unit)? = null
    @Volatile var lastTracks: List<AudioChoice> = emptyList()
    @Volatile var lastActive: String = ""

    /** Simpan permanen lewat [onChange] (dipasang aplikasi). */
    fun set(v: String) {
        preferred = normalize(v)
        onChange?.invoke(preferred)
    }

    /** Kode ISO: "id-ID" -> "id"; kode lama Android ("in", "iw", "ji") dipetakan ke "id", "he", "yi". */
    fun normalize(code: String?): String {
        val c = code?.trim()?.lowercase().orEmpty()
        if (c.isEmpty()) return ""
        return when (val l = c.substringBefore('-').substringBefore('_')) {
            "in" -> "id"; "iw" -> "he"; "ji" -> "yi"; else -> l
        }
    }
}
