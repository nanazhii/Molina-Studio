package com.molina.cinema

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * [PERF-TIER] Tingkat kemampuan perangkat untuk menyetel mpv. Otomatis dari RAM, bendera low-RAM Android,
 * dan versi OS. [PERF-MODE] Pengguna bisa memaksa lewat pref "perf_mode": auto | low | balanced | high.
 */
enum class PerfTier { LOW, MID, HIGH }

object PerfTierPolicy {
    private const val TAG = "perf"
    private const val PREF_KEY = "perf_mode"
    private const val GB = 1024L * 1024L * 1024L
    /** [PERF-MODE] [PERF-480] Tinggi maks stream YouTube pada tier LOW (juga di Wi-Fi): hemat data dan beban dekoder. */
    const val LOW_HEIGHT_CAP = 480
    @Volatile private var cached: PerfTier? = null

    private fun sp(ctx: Context) = MpvPrefs.playerPrefs(ctx) // [SSOT-PREFS]

    /** Mode pilihan pengguna: auto | low | balanced | high. */
    fun mode(ctx: Context): String = try {
        sp(ctx).getString(PREF_KEY, "auto") ?: "auto"
    } catch (e: ClassCastException) { "auto" }

    fun setMode(ctx: Context, m: String) {
        sp(ctx).edit().putString(PREF_KEY, m).apply()
        cached = null
    }

    /** Tier hasil deteksi perangkat, tanpa memperhitungkan pilihan pengguna. */
    fun detected(ctx: Context): PerfTier {
        val am = ctx.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return when {
            am.isLowRamDevice || mi.totalMem < 3L * GB -> PerfTier.LOW   // perangkat 2-3 GB melaporkan < 3 GB
            mi.totalMem < 6L * GB -> PerfTier.MID
            else -> PerfTier.HIGH
        }
    }

    /** [T23] Satu sumber pemetaan: mode pilihan pengguna + hasil deteksi -> tier. */
    private fun tierFor(mode: String, auto: PerfTier): PerfTier = when (mode) {
        "low" -> PerfTier.LOW
        "balanced" -> PerfTier.MID
        "high" -> PerfTier.HIGH
        else -> auto
    }

    /** [T23] Tier efektif tanpa efek samping (tanpa cache, log, atau applyTier); untuk UI dan laporan. */
    fun effective(ctx: Context): PerfTier = tierFor(mode(ctx), detected(ctx))

    fun current(ctx: Context): PerfTier {
        cached?.let { return it }
        val forced = mode(ctx)
        val auto = detected(ctx)
        val t = tierFor(forced, auto)
        MpvLog.i(TAG, "tier=$t (mode=$forced auto=$auto api=${Build.VERSION.SDK_INT})")
        CoverArt.applyTier(t) // [PERF-LITE]
        cached = t
        return t
    }

    /** [T24] Alasan tier efektif untuk pengguna: pilihan manual, atau hasil deteksi (bendera low-RAM / total RAM). */
    fun reasonText(ctx: Context): String {
        if (mode(ctx) != "auto") return "Anda memilih mode ini di Pengaturan."
        val am = ctx.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        if (am.isLowRamDevice) return "Aktif otomatis: Android menandai perangkat ini RAM rendah."
        return String.format(java.util.Locale("id", "ID"), "Aktif otomatis karena RAM perangkat %.1f GB.", mi.totalMem.toDouble() / GB)
    }

    /** Pada API < 28 jalur zero-copy (AImageReader) sering ditolak driver (-10004); langsung pakai mediacodec-copy. */
    fun hwdec(requested: String): String =
        if (requested != "no" && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) "mediacodec-copy" else requested

    /** AV1 tanpa dekoder perangkat keras hanya membuat mediacodec gagal membuka; biarkan software (dav1d). */
    fun hwdecCodecs(tier: PerfTier): String =
        if (tier == PerfTier.LOW || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            "h264,hevc,mpeg4,mpeg2video,vp8,vp9"
        else "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1"

    /** Dipanggil di akhir initOptions(); menimpa opsi sebelumnya sesuai tier. HIGH tidak diubah. */
    fun apply(tier: PerfTier, set: (String, String) -> Unit, forceGpu: () -> Unit) {
        if (tier == PerfTier.HIGH) return
        val mb = 1024 * 1024
        if (tier == PerfTier.LOW) {
            forceGpu()                                   // gpu-next jauh lebih berat
            set("demuxer-max-bytes", "${16 * mb}")
            set("demuxer-max-back-bytes", "${2 * mb}")
            set("cache-secs", "45")
            set("vd-lavc-threads", "2")                  // kurangi frame yang ditahan decoder software
            set("vd-lavc-fast", "yes")
            set("vd-lavc-skiploopfilter", "nonkey")
            set("framedrop", "decoder+vo")
            set("video-sync", "audio")
            set("interpolation", "no")
            set("deband", "no")
            set("vf", "")
            set("scale", "bilinear")
            set("cscale", "bilinear")
            set("dscale", "bilinear")
            set("dither-depth", "no")
            set("correct-downscaling", "no")
            set("linear-downscaling", "no")
            set("sigmoid-upscaling", "no")
        } else {
            set("demuxer-max-bytes", "${32 * mb}")
            set("demuxer-max-back-bytes", "${4 * mb}")
            set("vd-lavc-threads", "3")
            set("framedrop", "vo")
        }
    }
}
