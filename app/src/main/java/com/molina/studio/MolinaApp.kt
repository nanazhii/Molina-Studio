package com.molina.studio

import android.app.Application
import com.molina.cinema.MpvLifecycle
import com.molina.core.common.AppLog
import com.molina.core.common.CompositeStreamResolver
import com.molina.core.common.GuardedStreamResolver
import com.molina.core.common.ShieldProxy
import com.molina.core.common.StreamResolvers
import com.molina.engine.newpipe.NewPipeStreamResolver
import com.molina.studio.log.LogBus
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
        // Molina Shield harus hidup sebelum mpv diinisialisasi (opsi http_proxy dibaca saat init).
        ShieldPrefs.init(this)
        ShieldProxy.start()
        MpvLifecycle.installExitHook()
        StreamEnginePrefs.init(this)
        PrivacyPrefs.init(this)
        // [EXT-CONFIRM] token intent internal untuk pemutar; intent luar tanpa token dikonfirmasi bila meminta jaringan
        com.molina.core.common.ExternalIntentGate.tokenProvider = { com.molina.studio.security.ShortcutToken.get(this) }
        com.molina.studio.code.CodePrefs.init(this)
        com.molina.studio.code.ScriptStore.init(this)
        MpvControl.install(this)
        com.molina.studio.cmd.CommandServer.start(this)
        SystemRefresh.install(this)
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
