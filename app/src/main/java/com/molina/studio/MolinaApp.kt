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
import com.molina.studio.stream.YtdlpStreamResolver
import com.molina.studio.system.MpvControl
import com.molina.studio.system.SystemRefresh

/** Titik masuk proses: pasang sink log sedini mungkin agar log sebelum Activity tidak hilang. */
class MolinaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.install(LogBus)
        com.molina.core.api.MolinaServices.terminal = com.molina.studio.terminal.TerminalGatewayImpl(this)
        com.molina.core.api.MolinaServices.explorer = com.molina.studio.cinema.ExplorerGatewayImpl(this) // [EXPLORER-GW]
        com.molina.core.api.MolinaServices.media = com.molina.studio.cinema.MediaGatewayImpl(this) // [MEDIA-GW]
        com.molina.core.log.AnrWatchdog.attach(this) // [ANR-LOG]
        // Molina Shield harus hidup sebelum mpv diinisialisasi (opsi http_proxy dibaca saat init).
        ShieldPrefs.init(this)
        ShieldProxy.start()
        // [SHIELD-NOPROXY] FFmpeg membaca no_proxy dari lingkungan proses; hapus agar tak ada host yang melewati Shield.
        runCatching { android.system.Os.unsetenv("no_proxy"); android.system.Os.unsetenv("NO_PROXY") }
        MpvLifecycle.installExitHook()
        StreamEnginePrefs.init(this)
        com.molina.core.net.NetworkHint.provider = { com.molina.studio.stream.NetHint.snapshot(this) } // [AUTO-NET]
        PrivacyPrefs.init(this)
        // [EXT-CONFIRM] token intent internal untuk pemutar; intent luar tanpa token dikonfirmasi bila meminta jaringan
        com.molina.core.policy.ExternalIntentGate.tokenProvider = { com.molina.studio.security.ShortcutToken.get(this) }
        com.molina.studio.code.CodePrefs.init(this)
        com.molina.studio.code.ScriptStore.init(this)
        MpvControl.install(this)
        com.molina.studio.cmd.CommandServer.start(this)
        SystemRefresh.install(this)
        com.molina.cinema.SubtitleExtractors.provider = com.molina.studio.cinema.ProotSubtitleExtractor(this) // [SUBTITLE-EXTRACT]
        com.molina.studio.automation.AutoPrefs.init(this)
        com.molina.studio.automation.AutoWork.sync(this)
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
