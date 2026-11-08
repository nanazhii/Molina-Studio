package com.molina.core.common

/**
 * Penanda "sedang di layar mana" untuk laporan crash (baris "Mode"). JVM murni; dibaca dari handler crash
 * di thread mana pun, jadi setiap kolom @Volatile. Hanya mencatat nama layar, tanpa data pengguna.
 */
object CrashContext {
    const val HOST_APP = "Aplikasi"
    const val HOST_PLAYER = "Player (mpv)"

    /** Activity di depan: [HOST_APP] (MainActivity) atau [HOST_PLAYER] (MPVActivity). */
    @Volatile var host: String? = null

    /** Tab aktif di MainActivity (mis. "mpv", "Berkas", "Code", "Settings"). */
    @Volatile var tab: String? = null

    /** Layar penuh di atas tab (mis. "Mode TV"); null bila tidak ada. */
    @Volatile var overlay: String? = null

    /** Contoh: "Aplikasi > mpv > Mode TV", atau "Player (mpv)"; "?" bila belum ada yang tercatat. */
    fun describe(): String {
        val h = host
        if (h == HOST_PLAYER) return HOST_PLAYER
        return listOfNotNull(h, tab, overlay).joinToString(" > ").ifEmpty { "?" }
    }
}
