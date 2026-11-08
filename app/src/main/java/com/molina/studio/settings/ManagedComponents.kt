package com.molina.studio.settings

import com.molina.studio.ui.components.PkgState
import com.molina.studio.automation.AutoState

/**
 * [KOMPONEN-PUSAT] Satu sumber status untuk komponen yang dikelola halaman Paket (Debian, yt-dlp,
 * NewPipe, Komponen bawaan). Kartu hanya merender [ManagedComponent]; PkgState, lencana, dan teks
 * status tidak lagi dihitung sendiri di tiap Composable.
 */
internal enum class ComponentId { Debian, Ytdlp, NewPipe, Bundle }

internal data class ManagedComponent(
    val id: ComponentId,
    val title: String,
    val subtitle: String,
    val state: PkgState,
    val badge: String? = null
)

internal object ManagedComponents {

    /** [pending] negatif = belum pernah diperiksa. */
    fun debian(pending: Int, running: Boolean) = ManagedComponent(
        ComponentId.Debian, "Program Debian", AptHistory.checkSubtitle(pending),
        when {
            running -> PkgState.Checking
            pending > 0 -> PkgState.Outdated
            pending == 0 -> PkgState.Current
            else -> PkgState.Unchecked
        },
        badge = if (pending > 0) pending.toString() else null
    )

    fun ytdlp(s: AutoState, running: Boolean) = ManagedComponent(
        ComponentId.Ytdlp, "yt-dlp (pengunduh tambahan)", ytdlpText(s),
        when {
            running -> PkgState.Checking
            s.ytdlpOutdated -> PkgState.Outdated
            s.ytdlpInstalled.isNotEmpty() -> PkgState.Current
            else -> PkgState.Unchecked
        },
        badge = if (s.ytdlpOutdated) "Baru" else null
    )

    fun newpipe(s: AutoState, running: Boolean) = ManagedComponent(
        ComponentId.NewPipe, "NewPipeExtractor (pencari video)", newpipeText(s),
        when {
            running -> PkgState.Checking
            s.newpipeOutdated -> PkgState.Outdated
            s.newpipeLatest.isNotEmpty() -> PkgState.Current
            else -> PkgState.Unchecked
        },
        badge = if (s.newpipeOutdated) "Baru" else null
    )

    fun bundle(s: AutoState, total: Int, hashPinned: Int, requiredVerified: Boolean): ManagedComponent {
        val outdated = s.newpipeOutdated || s.ytdlpOutdated
        return ManagedComponent(
            ComponentId.Bundle, "Komponen bawaan Molina",
            "$total komponen \u00b7 $hashPinned sudah diperiksa keasliannya \u00b7 ringkasan keamanan",
            if (outdated) PkgState.Outdated else if (requiredVerified) PkgState.Current else PkgState.Unchecked,
            badge = if (outdated) "Baru" else null
        )
    }

    private fun ytdlpText(s: AutoState): String = when {
        s.ytdlpInstalled.isEmpty() && s.ytdlpLatest.isEmpty() -> "Belum dicek, atau yt-dlp belum terpasang"
        s.ytdlpOutdated -> "Pembaruan tersedia: ${s.ytdlpInstalled} \u2192 ${s.ytdlpLatest}"
        s.ytdlpInstalled.isNotEmpty() -> "Terbaru (${s.ytdlpInstalled})"
        else -> "Rilis terbaru: ${s.ytdlpLatest}"
    }

    private fun newpipeText(s: AutoState): String {
        val cur = com.molina.engine.newpipe.NewPipeInfo.VERSION
        return when {
            s.newpipeOutdated -> "Pembaruan tersedia: $cur \u2192 ${s.newpipeLatest}. Sudah menyatu di aplikasi, jadi baru terbarui lewat versi Molina yang baru."
            s.newpipeLatest.isNotEmpty() -> "Terbaru ($cur)"
            else -> "Dibundel $cur. Belum diperiksa."
        }
    }
}
