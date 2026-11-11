package com.molina.core.common

/** [AUTO-NET] Snapshot jaringan saat resolve. [wifi] = Wi-Fi/Ethernet. [downKbps] 0 = tidak diketahui. */
data class NetSnapshot(val wifi: Boolean = false, val downKbps: Int = 0) {
    companion object { val UNKNOWN = NetSnapshot() }
}

/** [AUTO-NET] Dipasang aplikasi (MolinaApp); dibaca resolver tiap resolve agar selalu segar. */
object NetworkHint {
    @Volatile var provider: (() -> NetSnapshot)? = null

    fun snapshot(): NetSnapshot = try { provider?.invoke() } catch (e: Exception) { null } ?: NetSnapshot.UNKNOWN
}
