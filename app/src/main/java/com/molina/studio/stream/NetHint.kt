package com.molina.studio.stream

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.molina.core.common.NetSnapshot

/** [AUTO-NET] Membaca jaringan aktif (murah, tanpa permintaan jaringan). */
object NetHint {
    fun snapshot(ctx: Context): NetSnapshot {
        val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetSnapshot.UNKNOWN
        val net = cm.activeNetwork ?: return NetSnapshot.UNKNOWN
        val caps = cm.getNetworkCapabilities(net) ?: return NetSnapshot.UNKNOWN
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return NetSnapshot(wifi = wifi, downKbps = caps.linkDownstreamBandwidthKbps)
    }
}
