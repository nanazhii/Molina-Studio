package com.molina.code.lsp

import com.molina.code.check.Diagnostic
import com.molina.code.check.Severity

// [CODE-LSP1] Klien LSP tanpa transport: byte masuk lewat [LspClient.feed], byte keluar lewat callback `send`.
// TIDAK thread-safe: panggil dari satu thread (tahap 2 menyerialkan lewat satu executor).

enum class LspState { IDLE, INITIALIZING, READY, SHUTTING_DOWN, CLOSED }

interface LspListener {
    /** Server selesai initialize; dokumen yang menunggu sudah dikirim. */
    fun onReady() {}

    /** Diagnostik terbaru untuk [uri] (daftar lengkap, menggantikan yang lama). [version] null bila server tak mengirimnya. */
    fun onDiagnostics(uri: String, version: Int?, items: List<Diagnostic>) {}

    /** Koneksi berakhir (shutdown rapi, galat protokol, atau transport putus). Dipanggil tepat sekali. */
    fun onClosed(reason: String) {}
}

/** Konversi `Diagnostic[]` LSP ke [Diagnostic] Molina (basis 1, kolom akhir eksklusif; posisi LSP = UTF-16, sama dengan String Kotlin). */
object LspDiagnostics {
    const val MAX_ITEMS = 2000
    private const val MAX_MESSAGE = 1000

    fun convert(list: List<*>?): List<Diagnostic> {
        if (list == null) return emptyList()
        val out = ArrayList<Diagnostic>(minOf(list.size, MAX_ITEMS))
        for (o in list) {
            if (out.size >= MAX_ITEMS) break
            val m = o as? Map<*, *> ?: continue
            val range = m["range"] as? Map<*, *> ?: continue
            val (sl, sc) = pos(range["start"]) ?: continue
            val (el, ec) = pos(range["end"]) ?: continue
            val sev = when ((m["severity"] as? Double)?.toInt()) {
                1 -> Severity.ERROR
                2, null -> Severity.WARNING
                else -> Severity.INFO
            }
            val code = when (val c = m["code"]) {
                is String -> c
                is Double -> c.toLong().toString()
                else -> null
            }
            val msg = ((m["message"] as? String) ?: "").take(MAX_MESSAGE).ifBlank { "(tanpa pesan)" }
            val source = (m["source"] as? String)?.ifBlank { null } ?: "lsp"
            out.add(Diagnostic(sl + 1, sc + 1, el + 1, ec + 1, sev, code, msg, source))
        }
        return out
    }

    private fun pos(o: Any?): Pair<Int, Int>? {
        val m = o as? Map<*, *> ?: return null
        val l = (m["line"] as? Double)?.toInt() ?: return null
        val c = (m["character"] as? Double)?.toInt() ?: return null
        return Pair(l.coerceAtLeast(0), c.coerceAtLeast(0))
    }
}

class LspClient(
    private val send: (ByteArray) -> Unit,
    private val listener: LspListener,
    private val clientName: String = "Molina Code",
) {
    var state: LspState = LspState.IDLE
        private set

    private class Doc(val lang: String, var version: Int, var text: String, var sent: Boolean)

    private val docs = LinkedHashMap<String, Doc>()
    private val decoder = LspDecoder()
    private var nextId = 1L
    private val pending = HashMap<Long, (Any?, String?) -> Unit>()

    /** Kirim initialize. False bila sudah dimulai/ditutup. Dokumen yang dibuka sebelum siap dikirim setelah initialize sukses. */
    fun start(rootUri: String?): Boolean {
        if (state != LspState.IDLE) return false
        state = LspState.INITIALIZING
        val params = mapOf(
            "processId" to null,
            "clientInfo" to mapOf("name" to clientName),
            "rootUri" to rootUri,
            "capabilities" to mapOf(
                "textDocument" to mapOf(
                    "synchronization" to mapOf("dynamicRegistration" to false, "didSave" to false),
                    "publishDiagnostics" to mapOf("relatedInformation" to false, "versionSupport" to true),
                    // [CODE-LSP4] completion polos (tanpa snippet) dan definition tanpa LocationLink
                    "completion" to mapOf(
                        "dynamicRegistration" to false,
                        "contextSupport" to false,
                        "completionItem" to mapOf("snippetSupport" to false, "documentationFormat" to listOf("plaintext")),
                    ),
                    "definition" to mapOf("dynamicRegistration" to false, "linkSupport" to false),
                ),
            ),
        )
        request("initialize", params) { _, err ->
            if (err != null) {
                close("initialize gagal: $err")
            } else if (state == LspState.INITIALIZING) {
                notify("initialized", emptyMap<String, Any?>())
                state = LspState.READY
                flushDocs()
                if (state == LspState.READY) listener.onReady()
            }
        }
        return true
    }

    /** Buka dokumen. Bila [uri] sudah terbuka, diperlakukan sebagai perubahan teks. */
    fun openDocument(uri: String, languageId: String, text: String) {
        if (state == LspState.CLOSED || state == LspState.SHUTTING_DOWN) return
        val d = docs[uri]
        if (d != null) return changeDocument(uri, text)
        val nd = Doc(languageId, 1, text, false)
        docs[uri] = nd
        if (state == LspState.READY) sendOpen(uri, nd)
    }

    /** Ganti isi dokumen (seluruh teks). Tidak mengirim apa pun bila teks sama. Dokumen belum dikenal diabaikan. */
    fun changeDocument(uri: String, text: String) {
        if (state == LspState.CLOSED || state == LspState.SHUTTING_DOWN) return
        val d = docs[uri] ?: return
        if (d.text == text) return
        d.version++
        d.text = text
        if (d.sent && state == LspState.READY) {
            notify(
                "textDocument/didChange",
                mapOf(
                    "textDocument" to mapOf("uri" to uri, "version" to d.version),
                    "contentChanges" to listOf(mapOf("text" to text)), // tanpa range = ganti seluruh isi
                ),
            )
        }
    }

    /**
     * [CODE-LSP4] Minta completion di (line, column) basis 0 (UTF-16). Teks dokumen terbaru sudah lebih dulu terkirim
     * (satu pipa, berurutan). [cb] dipanggil tepat sekali: (daftar, null) atau (null, alasan). Tanpa timeout di sini;
     * pemanggil yang memutuskan membuang hasil basi. False bila klien belum siap atau dokumen tidak dikenal/belum terkirim.
     */
    fun completion(uri: String, line: Int, column: Int, cb: (List<LspCompletionItem>?, String?) -> Unit): Boolean =
        positionRequest("textDocument/completion", uri, line, column) { r, err ->
            if (err != null) cb(null, err) else cb(LspCompletions.convert(r), null)
        }

    /** [CODE-LSP4] Minta definition di (line, column) basis 0. Hasil kosong = tidak ditemukan (bukan galat). */
    fun definition(uri: String, line: Int, column: Int, cb: (List<LspLocation>?, String?) -> Unit): Boolean =
        positionRequest("textDocument/definition", uri, line, column) { r, err ->
            if (err != null) cb(null, err) else cb(LspDefinitions.convert(r), null)
        }

    private fun positionRequest(method: String, uri: String, line: Int, column: Int, cb: (Any?, String?) -> Unit): Boolean {
        if (state != LspState.READY) return false
        val d = docs[uri] ?: return false
        if (!d.sent) return false
        request(
            method,
            mapOf(
                "textDocument" to mapOf("uri" to uri),
                "position" to mapOf("line" to line.coerceAtLeast(0), "character" to column.coerceAtLeast(0)),
            ),
            cb,
        )
        return true
    }

    fun closeDocument(uri: String) {
        val d = docs.remove(uri) ?: return
        if (d.sent && state == LspState.READY) notify("textDocument/didClose", mapOf("textDocument" to mapOf("uri" to uri)))
    }

    /** Matikan rapi (shutdown lalu exit). Sebelum siap: langsung ditutup. */
    fun shutdown() {
        when (state) {
            LspState.READY -> {
                state = LspState.SHUTTING_DOWN
                request("shutdown", null) { _, _ ->
                    notify("exit", null)
                    close("selesai")
                }
            }
            LspState.IDLE, LspState.INITIALIZING -> close("dibatalkan")
            else -> Unit
        }
    }

    /** Transport putus (proses mati, pipa tertutup). */
    fun transportClosed(reason: String) = close(reason)

    /** Byte dari server. Galat protokol menutup klien (listener.onClosed). */
    fun feed(data: ByteArray, off: Int = 0, n: Int = data.size) {
        if (state == LspState.CLOSED) return
        try {
            decoder.feed(data, off, n) { handle(it) }
        } catch (e: LspException) {
            close("protokol rusak: " + e.message)
        }
    }

    // ------------------------------------------------------------------ internal

    private fun flushDocs() {
        for ((uri, d) in docs) {
            if (state != LspState.READY) return
            if (!d.sent) sendOpen(uri, d)
        }
    }

    private fun sendOpen(uri: String, d: Doc) {
        d.sent = true
        notify(
            "textDocument/didOpen",
            mapOf("textDocument" to mapOf("uri" to uri, "languageId" to d.lang, "version" to d.version, "text" to d.text)),
        )
    }

    private fun request(method: String, params: Any?, cb: (Any?, String?) -> Unit) {
        val id = nextId++
        pending[id] = cb
        val msg = LinkedHashMap<String, Any?>()
        msg["jsonrpc"] = "2.0"
        msg["id"] = id
        msg["method"] = method
        if (params != null) msg["params"] = params
        write(msg)
    }

    private fun notify(method: String, params: Any?) {
        val msg = LinkedHashMap<String, Any?>()
        msg["jsonrpc"] = "2.0"
        msg["method"] = method
        if (params != null) msg["params"] = params
        write(msg)
    }

    private fun write(msg: Map<String, Any?>) {
        if (state == LspState.CLOSED) return
        try {
            send(LspFraming.encode(LspJson.write(msg)))
        } catch (e: Exception) {
            close("gagal menulis ke server: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun close(reason: String) {
        if (state == LspState.CLOSED) return
        state = LspState.CLOSED
        val cbs = pending.values.toList()
        pending.clear()
        for (cb in cbs) cb(null, reason)
        listener.onClosed(reason)
    }

    private fun handle(body: String) {
        if (state == LspState.CLOSED || body.isBlank()) return
        val root = LspJson.parse(body) as? Map<*, *> ?: throw LspException("pesan bukan objek JSON")
        val method = root["method"] as? String
        val id = root["id"]
        when {
            method != null && id != null -> serverRequest(id, method, root["params"])
            method != null -> notification(method, root["params"])
            id != null -> response(id, root)
        }
    }

    private fun response(id: Any, root: Map<*, *>) {
        val key = (id as? Double)?.toLong() ?: return
        val cb = pending.remove(key) ?: return
        val err = root["error"] as? Map<*, *>
        if (err != null) cb(null, (err["message"] as? String) ?: "galat server") else cb(root["result"], null)
    }

    private fun notification(method: String, params: Any?) {
        if (method != "textDocument/publishDiagnostics") return
        val p = params as? Map<*, *> ?: return
        val uri = p["uri"] as? String ?: return
        val version = (p["version"] as? Double)?.toInt()
        listener.onDiagnostics(uri, version, LspDiagnostics.convert(p["diagnostics"] as? List<*>))
    }

    /** Server memanggil klien: wajib dijawab, kalau tidak server bisa menunggu selamanya. */
    private fun serverRequest(id: Any, method: String, params: Any?) {
        val msg = LinkedHashMap<String, Any?>()
        msg["jsonrpc"] = "2.0"
        msg["id"] = id
        when (method) {
            "workspace/configuration" -> {
                val n = ((params as? Map<*, *>)?.get("items") as? List<*>)?.size ?: 0
                msg["result"] = List<Any?>(minOf(n, 64)) { null }
            }
            "client/registerCapability", "client/unregisterCapability", "window/workDoneProgress/create",
            "window/showMessageRequest", "workspace/workspaceFolders",
            -> msg["result"] = null
            else -> msg["error"] = mapOf("code" to -32601, "message" to "Method not found: $method")
        }
        write(msg)
    }
}
