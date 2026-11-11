package com.molina.studio.stream

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.molina.core.common.NetSnapshot

/** [AUTO-NET] Membaca jaringan aktif (murah, tanpa permintaan jaringan). */
object NetHint {
    /** [AUTO-WIFI] Tinggi maks dekoder HARDWARE per codec (VP9/AV1), dihitung sekali. Kosong = tidak ada -> VP9/AV1 tidak dipakai. */
    private val hwMax: Map<String, Int> by lazy {
        try {
            val infos = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            val sizes = listOf(2160 to 3840, 1440 to 2560, 1080 to 1920, 720 to 1280)
            mapOf("vp9" to "video/x-vnd.on2.vp9", "av1" to "video/av01").mapValues { (_, mime) ->
                var best = 0
                for (ci in infos) {
                    val n = ci.name
                    if (ci.isEncoder || n.startsWith("OMX.google.") || n.startsWith("c2.android.") || n.startsWith("c2.google.")) continue
                    if (!ci.supportedTypes.contains(mime)) continue
                    val vc = runCatching { ci.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() ?: continue
                    for ((h, w) in sizes) if (h > best && vc.isSizeSupported(w, h)) best = h
                }
                best
            }.filterValues { it > 0 }
        } catch (e: Exception) { emptyMap() }
    }

    fun snapshot(ctx: Context): NetSnapshot {
        val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetSnapshot.UNKNOWN
        val net = cm.activeNetwork ?: return NetSnapshot.UNKNOWN
        val caps = cm.getNetworkCapabilities(net) ?: return NetSnapshot.UNKNOWN
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return NetSnapshot(wifi = wifi, downKbps = caps.linkDownstreamBandwidthKbps, hwMaxHeight = hwMax)
    }
}
