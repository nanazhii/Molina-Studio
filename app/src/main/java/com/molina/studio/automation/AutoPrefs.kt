package com.molina.studio.automation

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Status otomasi Molina (pembaruan otomatis dan cadangan harian). Dibaca UI lewat [AutoPrefs.state]. */
data class AutoState(
    val autoUpdate: Boolean = true,
    val autoBackup: Boolean = false,
    val addToCinema: Boolean = false,
    val lastCheck: Long = 0L,
    val lastBackup: Long = 0L,
    val ytdlpInstalled: String = "",
    val ytdlpLatest: String = "",
    val newpipeLatest: String = ""
) {
    /** True bila rilis NewPipeExtractor terbaru lebih baru dari yang dibundel di aplikasi ini. */
    val newpipeOutdated: Boolean
        get() = newpipeLatest.isNotEmpty() && versionNewer(newpipeLatest, com.molina.engine.newpipe.NewPipeInfo.VERSION)

    /** True bila rilis resmi yt-dlp lebih baru dari yang terpasang (keduanya diketahui). */
    val ytdlpOutdated: Boolean
        get() = ytdlpInstalled.isNotEmpty() && ytdlpLatest.isNotEmpty() && versionNewer(ytdlpLatest, ytdlpInstalled)
}

/** Bandingkan versi bertitik (mis. 2025.10.22): true bila [latest] lebih baru dari [installed]. */
internal fun versionNewer(latest: String, installed: String): Boolean {
    val a = latest.trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }
    val b = installed.trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/** SharedPreferences molina_auto. Hanya sakelar yang ikut Cadangan gabungan (lihat BackupManager). */
object AutoPrefs {
    private const val FILE = "molina_auto"
    private var sp: SharedPreferences? = null
    private val _state = MutableStateFlow(AutoState())
    val state: StateFlow<AutoState> = _state.asStateFlow()

    @Synchronized
    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = p
        _state.value = AutoState(
            autoUpdate = p.getBoolean("auto_update", true),
            autoBackup = p.getBoolean("auto_backup", false),
            addToCinema = p.getBoolean("dl_to_cinema", false),
            lastCheck = p.getLong("last_check", 0L),
            lastBackup = p.getLong("last_backup", 0L),
            ytdlpInstalled = p.getString("ytdlp_installed", "").orEmpty(),
            ytdlpLatest = p.getString("ytdlp_latest", "").orEmpty(),
            newpipeLatest = p.getString("newpipe_latest", "").orEmpty()
        )
    }

    @Synchronized
    private fun change(ctx: Context, write: (SharedPreferences.Editor) -> Unit, next: (AutoState) -> AutoState) {
        init(ctx)
        val ed = sp!!.edit()
        write(ed)
        ed.apply()
        _state.value = next(_state.value)
    }

    fun setAutoUpdate(ctx: Context, v: Boolean) =
        change(ctx, { it.putBoolean("auto_update", v) }, { it.copy(autoUpdate = v) })

    fun setAutoBackup(ctx: Context, v: Boolean) =
        change(ctx, { it.putBoolean("auto_backup", v) }, { it.copy(autoBackup = v) })

    fun setAddToCinema(ctx: Context, v: Boolean) =
        change(ctx, { it.putBoolean("dl_to_cinema", v) }, { it.copy(addToCinema = v) })

    fun markChecked(ctx: Context) {
        val now = System.currentTimeMillis()
        change(ctx, { it.putLong("last_check", now) }, { it.copy(lastCheck = now) })
    }

    fun markBackup(ctx: Context) {
        val now = System.currentTimeMillis()
        change(ctx, { it.putLong("last_backup", now) }, { it.copy(lastBackup = now) })
    }

    /** Versi terpasang (dari --version) dan rilis terbaru (dari GitHub); kosong = tidak diketahui. */
    fun recordYtdlp(ctx: Context, installed: String, latest: String) =
        change(
            ctx,
            { it.putString("ytdlp_installed", installed).putString("ytdlp_latest", latest) },
            { it.copy(ytdlpInstalled = installed, ytdlpLatest = latest) }
        )

    /** Rilis NewPipeExtractor terbaru dari GitHub; kosong = tidak diketahui. */
    fun recordNewPipe(ctx: Context, latest: String) =
        change(ctx, { it.putString("newpipe_latest", latest) }, { it.copy(newpipeLatest = latest) })

    /** Dipanggil setelah pasang/perbarui sukses: versi terpasang = [v], lencana hilang bila sama dengan rilis terbaru. */
    fun setYtdlpInstalled(ctx: Context, v: String) =
        change(ctx, { it.putString("ytdlp_installed", v) }, { it.copy(ytdlpInstalled = v) })

    /** Dipanggil saat yt-dlp dihapus atau tidak ada: lencana dan status dibersihkan. */
    fun clearYtdlp(ctx: Context) = recordYtdlp(ctx, "", "")
}
