package com.molina.studio

import android.app.Application
import com.molina.cinema.MpvLifecycle
import com.molina.core.api.AppLog
import com.molina.core.stream.CompositeStreamResolver
import com.molina.core.stream.GuardedStreamResolver
import com.molina.core.net.ShieldProxy
import com.molina.core.stream.StreamResolvers
import com.molina.engine.newpipe.NewPipeStreamResolver
import com.molina.core.log.LogBus
import com.molina.studio.security.PrivacyPrefs
import com.molina.studio.security.ShieldPrefs
import com.molina.studio.stream.StreamEnginePrefs
import com.molina.studio.stream.YtdlpInstaller
import com.molina.studio.stream.YtdlpStreamResolver
import com.molina.studio.automation.AutoPrefs
import com.molina.studio.packages.ToolChecker
import com.molina.studio.cinema.MpvRefreshable
import com.molina.studio.download.DownloadRefreshable
import com.molina.studio.code.CodeRefreshable
import com.molina.studio.settings.ToolRunRefreshable
import com.molina.studio.system.MpvControl
import com.molina.studio.system.SystemRefresh

/** Titik masuk proses: pasang sink log sedini mungkin agar log sebelum Activity tidak hilang. */
class MolinaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.install(LogBus)
        // [MPV-LOG-FILE] Di Application agar MPVActivity yang dibuka langsung (intent luar) juga tercatat; tidak menyentuh MPVLib.
        com.molina.core.log.MpvLogFileWriter.attach(this, com.molina.studio.AppPaths.get(this))
        com.molina.cinema.MpvLogHook.sink = { p, l, t -> com.molina.core.log.MpvLogFileWriter.append(p, l, t) }
        com.molina.cinema.MpvLogHook.canShare = { com.molina.core.log.MpvLogFileWriter.enabled } // [MPV-LOG-SHARE]
        com.molina.cinema.MpvLogHook.share = { act, note -> // [MPV-LOG-SHARE] I/O di thread sendiri, chooser di thread utama
            Thread({
                val f = runCatching { com.molina.core.log.MpvLogFileWriter.snapshotFile() }.getOrNull()
                act.runOnUiThread {
                    if (f == null) android.widget.Toast.makeText(act, "Belum ada mpv.log untuk dibagikan", android.widget.Toast.LENGTH_SHORT).show()
                    else com.molina.studio.settings.LogFileShare.share(act, f, note)
                }
            }, "molina-mpv-log-share").start()
        }
        val perfT0 = android.os.SystemClock.elapsedRealtime() // [PERF-STARTUP]
        com.molina.core.api.MolinaServices.terminal = com.molina.studio.terminal.TerminalGatewayImpl(this)
        com.molina.core.api.MolinaServices.explorer = com.molina.studio.cinema.ExplorerGatewayImpl(this) // [EXPLORER-GW]
        com.molina.core.api.MolinaServices.media = com.molina.studio.cinema.MediaGatewayImpl(this) // [MEDIA-GW]
        com.molina.core.log.AnrWatchdog.attach(this) // [ANR-LOG]
        // Molina Shield harus hidup sebelum mpv diinisialisasi (opsi http_proxy dibaca saat init).
        ShieldPrefs.init(this)
        ShieldProxy.start()
        // [SHIELD-LOG] Durasi per koneksi ShieldHttp ke Log Screen: probe yt-dlp -> stream/ytdlp (Info), lainnya -> shield/http (Debug); galat = Warn.
        com.molina.core.net.ShieldHttp.onLog = { m ->
            val probe = m.startsWith("shield/probe ")
            val pm = m.startsWith("shield/" + com.molina.core.stream.PageMedia.LABEL + " ") // [PAGE-MEDIA-LOG]
            val level = if (" GAGAL " in m) com.molina.core.api.LogLevel.Warn
                else if (probe || pm) com.molina.core.api.LogLevel.Info else com.molina.core.api.LogLevel.Debug
            AppLog.log(if (probe || pm) "stream" else "shield", level, if (probe) "ytdlp" else if (pm) com.molina.core.stream.PageMedia.LABEL else "http", m)
        }
        // [SHIELD-NOPROXY] FFmpeg membaca no_proxy dari lingkungan proses; hapus agar tak ada host yang melewati Shield.
        runCatching { android.system.Os.unsetenv("no_proxy"); android.system.Os.unsetenv("NO_PROXY") }
        MpvLifecycle.installExitHook()
        StreamEnginePrefs.init(this)
        com.molina.studio.stream.YtdlpFastDir.startWarmup(this) // [YTDL-WARM] siapkan mesin yt-dlp di latar (hanya bila yt-dlp aktif)
        com.molina.core.net.NetworkHint.provider = { com.molina.studio.stream.NetHint.snapshot(this) } // [AUTO-NET]
        PrivacyPrefs.init(this)
        // [EXT-CONFIRM] token intent internal untuk pemutar; intent luar tanpa token dikonfirmasi bila meminta jaringan
        com.molina.core.policy.ExternalIntentGate.tokenProvider = { com.molina.studio.security.ShortcutToken.get(this) }
        com.molina.studio.code.CodePrefs.init(this)
        com.molina.studio.code.ScriptStore.init(this)
        MpvControl.install(this)
        com.molina.studio.cmd.CommandServer.start(this)
        SystemRefresh.install(this, MpvRefreshable(applicationContext), DownloadRefreshable(), ToolRunRefreshable(), CodeRefreshable())
        com.molina.cinema.SubtitleExtractors.provider = com.molina.studio.cinema.ProotSubtitleExtractor(this) // [SUBTITLE-EXTRACT]
        com.molina.studio.automation.AutoPrefs.init(this)
        ToolChecker.ytdlpVersionSink = { c, v -> AutoPrefs.setYtdlpInstalled(c, v) } // [PAKET-SERAGAM] disuntik: :packages tidak impor :automation
        YtdlpInstaller.versionSink = { c, v -> AutoPrefs.setYtdlpInstalled(c, v) } // [T20] disuntik: :stream tidak impor :automation
        com.molina.studio.automation.AutoWork.sync(this)
        // [PERF-PRELOAD] Muat setelan Terminal di thread latar; MainActivity memanggil init() yang sama dan kini tinggal menunggu/lewat (idempoten, @Synchronized).
        Thread({ runCatching { com.molina.studio.terminal.TerminalPrefs.init(this) } }, "molina-prefs-preload").apply { isDaemon = true }.start()
        com.molina.core.log.LogBus.log("sistem", com.molina.core.api.LogLevel.Info, "Startup", "Application.onCreate ${android.os.SystemClock.elapsedRealtime() - perfT0} ms") // [PERF-STARTUP-END]
        // NewPipe lebih dulu; yt-dlp (default mati, lihat Pengaturan) hanya untuk URL yang tak dikenalinya.
        StreamResolvers.install {
            GuardedStreamResolver(
                CompositeStreamResolver(
                    primary = NewPipeStreamResolver(),
                    fallback = YtdlpStreamResolver(this, { StreamEnginePrefs.ytdlpEnabled.value }),
                )
            )
        }
    }
}
