package com.molina.studio.common

import android.app.ActivityManager
import android.content.Context

/** Kelas perangkat dari sistem Android (bukan data pribadi). Satu sumber untuk laporan bug dan, nanti, mode Hemat otomatis. */
internal object DeviceClass {
    /** true bila sistem menandai perangkat sebagai low-RAM; null bila tidak bisa ditentukan. */
    fun isLowRam(ctx: Context): Boolean? =
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice

    /** Total RAM (MB) menurut sistem; null bila tidak terbaca. */
    fun totalRamMb(ctx: Context): Long? {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / (1024L * 1024L)
    }

    /** Teks untuk laporan: "ya", "tidak", atau "?" (tidak diketahui). */
    fun label(ctx: Context): String = when (isLowRam(ctx)) {
        true -> "ya"
        false -> "tidak"
        null -> "?"
    }
}
