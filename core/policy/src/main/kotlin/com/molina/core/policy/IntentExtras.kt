package com.molina.core.policy

/** [EXT-INTENT] Aturan murni untuk extra intent pemutaran dari aplikasi lain (judul, posisi awal, pasangan header). */
object IntentExtras {
    const val MAX_TITLE = 200
    const val MAX_HEADER_ELEMENTS = 64

    /** Posisi awal (ms) -> nilai opsi mpv `start` (detik); null bila tidak positif. */
    fun startOption(positionMs: Int): String? = if (positionMs > 0) "${positionMs / 1000f}" else null

    /** Judul untuk `force-media-title`: karakter kontrol dibuang, maksimal [MAX_TITLE]; null bila kosong. */
    fun titleOption(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        return raw.filter { it >= ' ' && it != '\u007f' }.take(MAX_TITLE)
    }

    /** String[] berpasangan [nama1, nilai1, nama2, nilai2, ...]; elemen null jadi "", maksimal [MAX_HEADER_ELEMENTS] elemen. */
    fun pairHeaders(arr: Array<out String?>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i + 1 < arr.size && i < MAX_HEADER_ELEMENTS) {
            out[arr[i] ?: ""] = arr[i + 1] ?: ""
            i += 2
        }
        return out
    }
}
