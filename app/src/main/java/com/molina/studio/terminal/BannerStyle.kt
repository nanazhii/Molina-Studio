package com.molina.studio.terminal

/** Gaya banner sesi baru dan tombol CLEAR. Mati = tidak ada banner (sesi paling cepat). */
enum class BannerStyle(val id: String, val label: String) {
    Logo("logo", "Logo Molina + status"),
    Compact("compact", "Ringkas (bingkai)"),
    Off("off", "Mati");

    companion object {
        fun fromId(id: String?): BannerStyle = entries.firstOrNull { it.id == id } ?: Logo
    }
}
