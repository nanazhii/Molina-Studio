package com.molina.studio.download

import android.content.Context
import com.molina.core.common.DownloadChoices

/**
 * Mengingat pilihan mode/kualitas Unduhan terakhir (SharedPreferences molina_download). Disimpan saat unduhan
 * benar-benar dimulai (bukan tiap chip diketuk). Nilai tidak valid dijatuhkan ke bawaan oleh
 * [DownloadChoices.sanitize]. URL sengaja TIDAK disimpan.
 */
internal object DownloadPrefs {
    private const val PREFS = "molina_download"
    private var restored = false

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): DownloadChoices {
        val s = p(ctx)
        fun <T> safe(f: () -> T): T? = try { f() } catch (e: ClassCastException) { null }
        return DownloadChoices.sanitize(
            video = if (s.contains("video")) safe { s.getBoolean("video", true) } else null,
            height = if (s.contains("height")) safe { s.getInt("height", 0) } else null,
            videoExt = safe { s.getString("video_ext", null) },
            audioFmt = safe { s.getString("audio_fmt", null) },
            bitrate = safe { s.getString("bitrate", null) },
            subs = safe { s.getString("subs", null) }?.split(',')?.filter { it.isNotBlank() },
            subsMode = safe { s.getString("subs_mode", null) },
            cover = if (s.contains("cover")) safe { s.getBoolean("cover", true) } else null,
            limitRate = safe { s.getString("limit_rate", null) },
            sidecar = if (s.contains("sidecar")) safe { s.getBoolean("sidecar", false) } else null,
            perChannel = if (s.contains("per_channel")) safe { s.getBoolean("per_channel", false) } else null,
        )
    }

    fun save(ctx: Context, c: DownloadChoices) {
        p(ctx).edit()
            .putBoolean("video", c.video).putInt("height", c.height)
            .putString("video_ext", c.videoExt).putString("audio_fmt", c.audioFmt)
            .putString("bitrate", c.bitrate)
            .putString("subs", c.subs.joinToString(",")).putString("subs_mode", c.subsMode)
            .putBoolean("cover", c.cover).putString("limit_rate", c.limitRate)
            .putBoolean("sidecar", c.sidecar).putBoolean("per_channel", c.perChannel).apply()
    }

    /** [WIFI-ONLY] Tidak ikut [DownloadChoices]: ini pilihan jaringan dan berlaku untuk mulai, ulangi, dan ulangi riwayat. */
    fun wifiOnly(ctx: Context): Boolean = try {
        p(ctx).getBoolean("wifi_only", false)
    } catch (e: ClassCastException) {
        false
    }

    fun setWifiOnly(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("wifi_only", on).apply()
        DownloadForm.wifiOnly.value = on
    }

    /** Isi [DownloadForm] sekali per proses; pemanggilan berikutnya diabaikan agar tidak menimpa pilihan aktif. */
    @Synchronized
    fun restore(ctx: Context) {
        if (restored) return
        restored = true
        DownloadForm.apply(load(ctx))
        DownloadForm.wifiOnly.value = wifiOnly(ctx)
    }
}
