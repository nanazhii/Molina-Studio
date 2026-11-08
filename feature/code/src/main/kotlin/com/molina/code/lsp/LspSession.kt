package com.molina.code.lsp

import com.molina.code.check.Diagnostic
import java.io.IOException

/** [CODE-LSP4] Target definition; [hostPath] null bila berkasnya tidak terlihat dari Android. Posisi basis 0. */
data class LspDefinitionTarget(val hostPath: String?, val uri: String, val line: Int, val column: Int)

/** Callback sesi. Datang dari thread latar DI DALAM kunci sesi: jangan memblokir; pindahkan kerja berat ke thread lain. */
interface LspSessionListener {
    fun onReady() {}

    /** [hostPath] = path Android dokumen (sudah diterjemahkan dari URI guest). */
    fun onDiagnostics(hostPath: String, version: Int?, items: List<Diagnostic>) {}

    /** Stderr server (potongan apa adanya). Isinya bisa memuat kode pengguna: jangan dicatat ke log. */
    fun onServerLog(text: String) {}

    /** Tepat sekali. Proses sudah/sedang dimatikan. */
    fun onClosed(reason: String) {}
}

/**
 * [CODE-LSP2] Merekatkan [LspClient] (tahap 1) ke proses menetap lewat [StreamExecutor], dengan penerjemahan
 * path Android <-> Debian di batas ini: UI hanya mengenal path Android, server hanya mengenal path guest.
 *
 * Aman dipanggil dari thread mana pun: semua akses klien diserialkan satu kunci (stdout datang dari thread
 * latar, panggilan UI dari thread lain). Tidak ada timer di sini (debounce dan idle shutdown = tahap 3).
 */
class LspSession(
    private val executor: StreamExecutor,
    private val paths: LspPathMap,
    private val request: StreamRequest,
    private val listener: LspSessionListener,
    private val clientName: String = "Molina Code",
) {
    private val lock = Any()
    private var client: LspClient? = null
    @Volatile private var handle: StreamHandle? = null
    private var started = false
    private val guestToHost = HashMap<String, String>()

    val state: LspState get() = synchronized(lock) { client?.state ?: LspState.IDLE }

    /**
     * Mulai proses dan kirim initialize. [rootHostDir] = folder proyek di Android; bila tidak terlihat di guest,
     * rootUri dikirim null. False bila sesi sudah pernah dimulai. Kegagalan memulai proses sampai lewat
     * [LspSessionListener.onClosed].
     */
    fun start(rootHostDir: String?): Boolean = synchronized(lock) {
        if (started) return false
        started = true
        val c = LspClient(
            send = { bytes ->
                val h = handle ?: throw IOException("transport belum siap")
                if (!h.write(bytes)) throw IOException("transport tertutup atau antrean penuh")
            },
            listener = ClientEvents(),
            clientName = clientName,
        )
        client = c
        // onExit bisa datang sinkron (mis. Debian belum terpasang): klien sudah ada, jadi ditutup dengan benar.
        handle = executor.startStream(request, StreamEvents())
        c.start(rootHostDir?.let { paths.hostToGuestUri(it) })
        true
    }

    /** False bila sesi belum mulai/sudah tertutup atau [hostPath] tidak terlihat di Debian (server tidak bisa membukanya). */
    fun openDocument(hostPath: String, languageId: String, text: String): Boolean = synchronized(lock) {
        val c = client ?: return false
        if (c.state == LspState.CLOSED || c.state == LspState.SHUTTING_DOWN) return false
        val uri = paths.hostToGuestUri(hostPath) ?: return false
        guestToHost[uri] = hostPath
        c.openDocument(uri, languageId, text)
        true
    }

    fun changeDocument(hostPath: String, text: String) = synchronized(lock) {
        val uri = paths.hostToGuestUri(hostPath) ?: return@synchronized
        client?.changeDocument(uri, text)
    }

    /**
     * [CODE-LSP4] Completion di (line, column) basis 0 untuk dokumen yang sudah dibuka. [cb] datang dari thread latar
     * DI DALAM kunci sesi: jangan memblokir. False bila tidak bisa diminta (belum siap, dokumen tidak terlihat/belum dibuka).
     */
    fun completion(hostPath: String, line: Int, column: Int, cb: (List<LspCompletionItem>?, String?) -> Unit): Boolean =
        synchronized(lock) {
            val c = client ?: return false
            val uri = paths.hostToGuestUri(hostPath) ?: return false
            c.completion(uri, line, column, cb)
        }

    /** [CODE-LSP4] Definition di (line, column) basis 0. Tiap target membawa path Android bila terlihat di host, null bila tidak (mis. pustaka sistem Debian). */
    fun definition(hostPath: String, line: Int, column: Int, cb: (List<LspDefinitionTarget>?, String?) -> Unit): Boolean =
        synchronized(lock) {
            val c = client ?: return false
            val uri = paths.hostToGuestUri(hostPath) ?: return false
            c.definition(uri, line, column) { list, err ->
                if (list == null) cb(null, err)
                else cb(list.map { LspDefinitionTarget(guestToHost[it.uri] ?: paths.guestUriToHostPath(it.uri), it.uri, it.line, it.column) }, null)
            }
        }

    fun closeDocument(hostPath: String) = synchronized(lock) {
        val uri = paths.hostToGuestUri(hostPath) ?: return@synchronized
        guestToHost.remove(uri)
        client?.closeDocument(uri)
    }

    /** Matikan rapi (shutdown + exit); proses dimatikan begitu klien tertutup. Gunakan [kill] bila server tidak menjawab. */
    fun stop() = synchronized(lock) { client?.shutdown() ?: Unit }

    /** Matikan paksa seluruh pohon proses. */
    fun kill() { handle?.cancel() }

    private inner class ClientEvents : LspListener {
        override fun onReady() = listener.onReady()

        override fun onDiagnostics(uri: String, version: Int?, items: List<Diagnostic>) {
            val host = guestToHost[uri] ?: paths.guestUriToHostPath(uri) ?: return // berkas di luar yang terlihat: abaikan
            listener.onDiagnostics(host, version, items)
        }

        override fun onClosed(reason: String) {
            handle?.cancel() // jaminan: tidak ada proses yatim setelah klien tertutup
            listener.onClosed(reason)
        }
    }

    private inner class StreamEvents : StreamListener {
        override fun onStdout(data: ByteArray, length: Int) {
            synchronized(lock) { client?.feed(data, 0, length) }
        }

        override fun onStderr(text: String) = listener.onServerLog(text)

        override fun onExit(result: StreamResult) {
            val reason = result.startError
                ?: if (result.cancelled) "dihentikan" else "server berhenti (exit=${result.exitCode})"
            synchronized(lock) { client?.transportClosed(reason) }
        }
    }
}
