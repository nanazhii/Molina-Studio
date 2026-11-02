package com.molina.studio.download

import android.content.Context
import android.net.ConnectivityManager

/** [WIFI-ONLY] Pemeriksaan jaringan untuk opsi "Hanya Wi-Fi" (izin ACCESS_NETWORK_STATE sudah ada di Manifest). */
internal object NetworkGuard {
    const val BLOCK_MSG =
        "Mode Hanya Wi-Fi aktif, tetapi jaringan sekarang berkuota (data seluler atau hotspot berkuota). " +
            "Sambungkan Wi-Fi biasa atau matikan opsi Hanya Wi-Fi."

    /** True bila jaringan aktif berkuota. Tidak ada jaringan atau gagal membaca = false (biar yt-dlp melapor sendiri). */
    fun isMetered(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (cm.activeNetwork == null) false else cm.isActiveNetworkMetered
    } catch (e: Exception) {
        false
    }
}
