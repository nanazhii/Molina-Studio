package com.molina.core.api

/** Pencatat layanan sederhana. Diisi sekali di Application.onCreate, dibaca fitur lewat kontrak. */
object MolinaServices {
    @Volatile private var terminalImpl: TerminalGateway? = null

    var terminal: TerminalGateway
        get() = checkNotNull(terminalImpl) { "TerminalGateway belum didaftarkan (MolinaApp.onCreate)" }
        set(v) { terminalImpl = v }

    @Volatile private var explorerImpl: ExplorerGateway? = null

    var explorer: ExplorerGateway
        get() = checkNotNull(explorerImpl) { "ExplorerGateway belum didaftarkan (MolinaApp.onCreate)" }
        set(v) { explorerImpl = v }

    @Volatile private var mediaImpl: MediaGateway? = null

    var media: MediaGateway
        get() = checkNotNull(mediaImpl) { "MediaGateway belum didaftarkan (MolinaApp.onCreate)" }
        set(v) { mediaImpl = v }
}
