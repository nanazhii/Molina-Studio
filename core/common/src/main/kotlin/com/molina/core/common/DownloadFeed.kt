package com.molina.core.common

/** Keadaan satu kali jalan untuk UI: [session] dari event skrip, ditambah status proses. */
data class DownloadRunState(
    val running: Boolean = false,
    val session: DownloadSessionState = DownloadSessionState(),
    val exitCode: Int? = null,
    val stderrTail: List<String> = emptyList(),
)

/**
 * Menyusun potongan keluaran executor (belum dipecah per baris, stdout dan stderr bergantian) menjadi
 * [DownloadRunState]. Murni dan tanpa Android. Tidak aman antar-thread: pemanggil harus menyinkronkan.
 * Keluaran mentah tidak disimpan: hanya state dan 20 baris stderr terakhir (URL dan token disamarkan).
 */
class DownloadFeed {
    var state: DownloadRunState = DownloadRunState(running = true)
        private set

    private val out = StringBuilder()
    private val err = StringBuilder()
    private var outSkip = false
    private var errSkip = false

    /** @return true bila [state] berubah. */
    fun push(text: String, isError: Boolean): Boolean {
        val before = state
        consume(text, isError)
        return state != before
    }

    /** Akhir proses: buang sisa baris tanpa newline, lalu tuntaskan item yang menggantung. */
    fun finish(cancelled: Boolean, exitCode: Int, startError: String?) {
        if (!outSkip && out.isNotEmpty()) line(out.toString(), false)
        if (!errSkip && err.isNotEmpty()) line(err.toString(), true)
        out.setLength(0); err.setLength(0)
        state = state.copy(
            running = false,
            exitCode = exitCode,
            session = settle(state.session, cancelled, exitCode, startError, state.stderrTail),
        )
    }

    private fun consume(text: String, isError: Boolean) {
        val buf = if (isError) err else out
        var skip = if (isError) errSkip else outSkip
        for (ch in text) {
            if (ch == '\n') {
                if (!skip) line(buf.toString(), isError)
                buf.setLength(0)
                skip = false
            } else if (!skip) {
                if (buf.length >= MAX_LINE) { buf.setLength(0); skip = true } else buf.append(ch)
            }
        }
        if (isError) errSkip = skip else outSkip = skip
    }

    private fun line(raw: String, isError: Boolean) {
        val t = raw.trimEnd('\r')
        if (t.isBlank()) return
        if (isError) {
            val safe = LogRedact.text(t).take(MAX_ERR_LINE)
            state = state.copy(stderrTail = (state.stderrTail + safe).takeLast(MAX_ERR_LINES))
        } else {
            val e = DownloadEventParser.parse(t) ?: return
            state = state.copy(session = DownloadReducer.reduce(state.session, e))
        }
    }

    companion object {
        const val MAX_LINE = 65_536
        const val MAX_ERR_LINE = 300
        const val MAX_ERR_LINES = 20

        /**
         * Tuntaskan sesi yang berhenti tanpa event `summary` (dibatalkan, proses mati, atau gagal mulai):
         * item yang masih berjalan menjadi gagal dengan alasan jelas, galat global diisi bila perlu.
         */
        internal fun settle(
            s: DownloadSessionState,
            cancelled: Boolean,
            exitCode: Int,
            startError: String?,
            stderrTail: List<String>,
        ): DownloadSessionState {
            if (s.finished) return s
            val rawStop = "proses berhenti (kode $exitCode)" + (stderrTail.lastOrNull()?.let { ": $it" } ?: "")
            val reason = when {
                cancelled -> "Dibatalkan."
                startError != null -> startError
                else -> DownloadErrorText.STOPPED
            }
            val code = if (cancelled) "cancelled" else "aborted"
            val items = s.items.map {
                if (it.status == DownloadItemStatus.RUNNING || it.status == DownloadItemStatus.PROCESSING) {
                    it.copy(status = DownloadItemStatus.FAILED, stage = null, errorCode = code, errorMsg = reason, errorRaw = rawStop)
                } else it
            }
            val fatal = s.fatalError ?: when {
                cancelled -> null
                startError != null -> startError
                exitCode != 0 -> DownloadErrorText.STOPPED
                else -> null
            }
            val fatalRaw = s.fatalRaw ?: when {
                cancelled -> null
                startError != null -> startError
                exitCode != 0 -> rawStop
                else -> null
            }
            return s.copy(
                items = items,
                fatalError = fatal,
                fatalRaw = fatalRaw,
                finished = true,
                ok = items.count { it.status == DownloadItemStatus.DONE },
                failed = items.count { it.status == DownloadItemStatus.FAILED },
                cancelled = cancelled,
            )
        }
    }
}
