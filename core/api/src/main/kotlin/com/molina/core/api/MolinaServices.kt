package com.molina.core.api

/** Pencatat layanan sederhana. Diisi sekali di Application.onCreate, dibaca fitur lewat kontrak. */
object MolinaServices {
    @Volatile private var terminalImpl: TerminalGateway? = null

    var terminal: TerminalGateway
        get() = checkNotNull(terminalImpl) { "TerminalGateway belum didaftarkan (MolinaApp.onCreate)" }
        set(v) { terminalImpl = v }
}
