package com.molina.core.net

/** [AUTO-NET] Snapshot jaringan saat resolve. [wifi] = Wi-Fi/Ethernet. [downKbps] 0 = tidak diketahui. */
data class NetSnapshot(
    val wifi: Boolean = false,
    val downKbps: Int = 0,
    /** [AUTO-WIFI] Tinggi maks dekoder HARDWARE per codec ("vp9", "av1"); kosong = tidak ada. */
    val hwMaxHeight: Map<String, Int> = emptyMap(),
    /** [PERF-MODE] Batas tinggi AUTO di semua jaringan (0 = tanpa batas, perilaku lama). */
    val heightCap: Int = 0,
    /** [NET-CAP] Jaringan aktif berkuota (data seluler / hotspot / Wi-Fi ditandai metered). */
    val metered: Boolean = false,
) {
    companion object { val UNKNOWN = NetSnapshot() }
}

/** [AUTO-NET] Dipasang aplikasi (MolinaApp); dibaca resolver tiap resolve agar selalu segar. */
object NetworkHint {
    @Volatile var provider: (() -> NetSnapshot)? = null

    fun snapshot(): NetSnapshot = try { provider?.invoke() } catch (e: Exception) { null } ?: NetSnapshot.UNKNOWN
}
