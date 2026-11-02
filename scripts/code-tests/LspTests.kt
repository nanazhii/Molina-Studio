import com.molina.code.check.Severity
import com.molina.code.lsp.LspClient
import com.molina.code.lsp.LspDecoder
import com.molina.code.lsp.LspDiagnostics
import com.molina.code.lsp.LspException
import com.molina.code.lsp.LspFraming
import com.molina.code.lsp.LspJson
import com.molina.code.lsp.LspListener
import com.molina.code.lsp.LspState
import com.molina.code.lsp.LspUri
import com.molina.code.check.Diagnostic

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}
private fun throwsLsp(f: () -> Unit): Boolean = try { f(); false } catch (e: LspException) { true }

/** Kabel palsu: menampung pesan klien -> server sebagai peta JSON. */
private class Wire {
    val sent = ArrayList<Map<*, *>>()
    private val dec = LspDecoder()
    var failWrites = false
    val send: (ByteArray) -> Unit = { b ->
        if (failWrites) throw java.io.IOException("pipa putus")
        dec.feed(b, 0, b.size) { sent.add(LspJson.parse(it) as Map<*, *>) }
    }
    fun methods(): List<Any?> = sent.map { it["method"] }
    fun last(): Map<*, *> = sent.last()
}

private class Events : LspListener {
    var ready = 0
    var closed: String? = null
    var closedCount = 0
    val diags = ArrayList<Triple<String, Int?, List<Diagnostic>>>()
    override fun onReady() { ready++ }
    override fun onDiagnostics(uri: String, version: Int?, items: List<Diagnostic>) { diags.add(Triple(uri, version, items)) }
    override fun onClosed(reason: String) { closed = reason; closedCount++ }
}

private fun server(o: Any?): ByteArray = LspFraming.encode(LspJson.write(o))
private fun reply(id: Long, result: Any?): ByteArray = server(mapOf("jsonrpc" to "2.0", "id" to id, "result" to result))

private fun newClient(): Triple<LspClient, Wire, Events> {
    val w = Wire()
    val e = Events()
    return Triple(LspClient(w.send, e), w, e)
}

private fun readyClient(): Triple<LspClient, Wire, Events> {
    val t = newClient()
    t.first.start("file:///w")
    t.first.feed(reply(1, mapOf("capabilities" to emptyMap<String, Any?>())))
    return t
}

fun main() {
    // ---- JSON
    check("json: tulis objek bersarang", LspJson.write(mapOf("a" to listOf(1, 2.5, "x", null, true))) == """{"a":[1,2.5,"x",null,true]}""")
    check("json: Double bulat ditulis sebagai bilangan bulat", LspJson.write(3.0) == "3" && LspJson.write(-7.0) == "-7")
    check("json: NaN jadi null", LspJson.write(Double.NaN) == "null")
    val tricky = "a\"b\\c\nd\te\u0001é😀"
    check("json: escape + roundtrip unicode", LspJson.parse(LspJson.write(tricky)) == tricky)
    check("json: karakter kontrol di-escape", LspJson.write("\u0001") == "\"\\u0001\"")
    check("json: kunci bukan string ditolak", throwsLsp { LspJson.write(mapOf(1 to "x")) })
    check("json: tipe asing ditolak", throwsLsp { LspJson.write(Any()) })
    var deepV: Any? = "x"
    repeat(100) { deepV = listOf(deepV) }
    check("json: terlalu dalam ditolak", throwsLsp { LspJson.write(deepV) })
    check("json: parse rusak jadi LspException", throwsLsp { LspJson.parse("{") })

    // ---- framing + decoder
    val m1 = """{"a":"é😀"}"""
    val enc = LspFraming.encode(m1)
    check("framing: Content-Length dalam byte, bukan karakter",
        String(enc, Charsets.US_ASCII).startsWith("Content-Length: ${m1.toByteArray(Charsets.UTF_8).size}\r\n\r\n"))
    val got = ArrayList<String>()
    val two = enc + LspFraming.encode("""{"b":2}""")
    val d1 = LspDecoder()
    for (b in two) d1.feed(byteArrayOf(b), 0, 1) { got.add(it) }
    check("decoder: dua pesan, dipotong satu byte", got == listOf(m1, """{"b":2}"""))
    val got2 = ArrayList<String>()
    LspDecoder().feed(two, 0, two.size) { got2.add(it) }
    check("decoder: dua pesan dalam satu potongan", got2 == listOf(m1, """{"b":2}"""))
    val got3 = ArrayList<String>()
    val hdr = "content-type: application/vscode-jsonrpc; charset=utf-8\r\nCONTENT-LENGTH: 2\r\n\r\n{}".toByteArray()
    LspDecoder().feed(hdr, 0, hdr.size) { got3.add(it) }
    check("decoder: nama header tak peka huruf besar, header lain diabaikan", got3 == listOf("{}"))
    val got4 = ArrayList<String>()
    val z = "Content-Length: 0\r\n\r\n".toByteArray()
    LspDecoder().feed(z, 0, z.size) { got4.add(it) }
    check("decoder: badan kosong", got4 == listOf(""))
    check("decoder: tanpa Content-Length ditolak", throwsLsp { val b = "X: 1\r\n\r\n".toByteArray(); LspDecoder().feed(b, 0, b.size) { } })
    check("decoder: Content-Length negatif ditolak", throwsLsp { val b = "Content-Length: -1\r\n\r\n".toByteArray(); LspDecoder().feed(b, 0, b.size) { } })
    check("decoder: Content-Length bukan angka ditolak", throwsLsp { val b = "Content-Length: abc\r\n\r\n".toByteArray(); LspDecoder().feed(b, 0, b.size) { } })
    check("decoder: Content-Length melebihi batas ditolak", throwsLsp { val b = "Content-Length: 99\r\n\r\n".toByteArray(); LspDecoder(maxBody = 10).feed(b, 0, b.size) { } })
    check("decoder: header tanpa penutup terlalu panjang ditolak", throwsLsp { val b = ByteArray(5000) { 'a'.code.toByte() }; LspDecoder().feed(b, 0, b.size) { } })
    check("decoder: potongan di luar larik ditolak", throwsLsp { LspDecoder().feed(ByteArray(2), 1, 5) { } })
    val big = "x".repeat(20000)
    val bigMsg = LspFraming.encode("\"$big\"")
    val got5 = ArrayList<String>()
    val d5 = LspDecoder()
    var o = 0
    while (o < bigMsg.size) { val n = minOf(777, bigMsg.size - o); d5.feed(bigMsg, o, n) { got5.add(it) }; o += n }
    check("decoder: badan besar dalam potongan tak rata", got5.size == 1 && got5[0].length == big.length + 2)
    val got6 = ArrayList<String>()
    val d6 = LspDecoder()
    repeat(50) { d6.feed(enc, 0, enc.size) { got6.add(it) } }
    check("decoder: pemakaian berulang tanpa membengkak", got6.size == 50 && got6.all { it == m1 })

    // ---- URI
    check("uri: path biasa", LspUri.fromPath("/sdcard/molina/a.py") == "file:///sdcard/molina/a.py")
    check("uri: spasi dan unicode di-encode", LspUri.fromPath("/a b/é.py") == "file:///a%20b/%C3%A9.py")
    check("uri: roundtrip", LspUri.toPath(LspUri.fromPath("/a b/é😀#x.py")) == "/a b/é😀#x.py")
    check("uri: path relatif diberi awalan /", LspUri.fromPath("x.py") == "file:///x.py")
    check("uri: bukan file ditolak", LspUri.toPath("http://x") == null)
    check("uri: percent rusak ditolak", LspUri.toPath("file:///a%2") == null && LspUri.toPath("file:///a%zz") == null)
    check("uri: karakter unicode mentah diterima", LspUri.toPath("file:///é") == "/é")

    // ---- konversi diagnostik
    val raw = LspJson.parse("""[
        {"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":6}},"severity":2,"code":"F401","source":"pyflakes","message":"'os' imported but unused"},
        {"range":{"start":{"line":4,"character":8},"end":{"line":4,"character":10}},"severity":1,"code":821,"message":"undefined name"},
        {"range":{"start":{"line":1,"character":0},"end":{"line":1,"character":1}},"severity":4,"message":"hint"},
        {"range":{"start":{"line":2,"character":0}},"message":"tanpa akhir"},
        {"message":"tanpa range"},
        "bukan objek",
        {"range":{"start":{"line":3,"character":0},"end":{"line":3,"character":2}}}
    ]""") as List<*>
    val cv = LspDiagnostics.convert(raw)
    check("diag: item rusak dilewati", cv.size == 4)
    check("diag: posisi basis 1", cv[0].line == 1 && cv[0].col == 1 && cv[0].endLine == 1 && cv[0].endCol == 7)
    check("diag: severity dipetakan", cv[0].severity == Severity.WARNING && cv[1].severity == Severity.ERROR && cv[2].severity == Severity.INFO)
    check("diag: kode string dan angka", cv[0].code == "F401" && cv[1].code == "821" && cv[2].code == null)
    check("diag: source", cv[0].source == "pyflakes" && cv[1].source == "lsp")
    check("diag: pesan kosong diberi pengganti", cv[3].message == "(tanpa pesan)" && cv[3].severity == Severity.WARNING)
    check("diag: null/kosong", LspDiagnostics.convert(null).isEmpty() && LspDiagnostics.convert(emptyList<Any?>()).isEmpty())
    val many = List(LspDiagnostics.MAX_ITEMS + 50) { mapOf("range" to mapOf("start" to mapOf("line" to 0.0, "character" to 0.0), "end" to mapOf("line" to 0.0, "character" to 1.0)), "message" to "m") }
    check("diag: dibatasi MAX_ITEMS", LspDiagnostics.convert(many).size == LspDiagnostics.MAX_ITEMS)
    val longMsg = LspDiagnostics.convert(listOf(mapOf("range" to mapOf("start" to mapOf("line" to 0.0, "character" to 0.0), "end" to mapOf("line" to 0.0, "character" to 1.0)), "message" to "x".repeat(5000))))
    check("diag: pesan dipangkas", longMsg[0].message.length == 1000)
    check("diag: posisi negatif dijepit", LspDiagnostics.convert(listOf(mapOf("range" to mapOf("start" to mapOf("line" to -3.0, "character" to -1.0), "end" to mapOf("line" to 0.0, "character" to 1.0)), "message" to "m")))[0].let { it.line == 1 && it.col == 1 })

    // ---- alur klien
    run {
        val (c, w, e) = newClient()
        check("klien: awal IDLE", c.state == LspState.IDLE)
        check("klien: start mengirim initialize", c.start("file:///w") && w.last()["method"] == "initialize" && w.last()["id"] == 1.0)
        check("klien: INITIALIZING", c.state == LspState.INITIALIZING)
        check("klien: start kedua ditolak", !c.start("file:///w") && w.sent.size == 1)
        val caps = ((w.last()["params"] as Map<*, *>)["capabilities"] as Map<*, *>)["textDocument"] as Map<*, *>
        check("klien: kapabilitas diagnostik dideklarasikan", (caps["publishDiagnostics"] as Map<*, *>)["versionSupport"] == true)
        check("klien: processId null", (w.last()["params"] as Map<*, *>).containsKey("processId") && (w.last()["params"] as Map<*, *>)["processId"] == null)
        c.openDocument("file:///w/a.py", "python", "x = 1\n")
        check("klien: dokumen ditahan sebelum siap", w.sent.size == 1)
        c.changeDocument("file:///w/a.py", "x = 2\n")
        c.feed(reply(1, mapOf("capabilities" to emptyMap<String, Any?>())))
        check("klien: urutan initialize, initialized, didOpen", w.methods() == listOf("initialize", "initialized", "textDocument/didOpen"))
        val td = (w.last()["params"] as Map<*, *>)["textDocument"] as Map<*, *>
        check("klien: didOpen memakai teks terbaru dan versi 2", td["text"] == "x = 2\n" && td["version"] == 2.0 && td["languageId"] == "python")
        check("klien: READY dan onReady sekali", c.state == LspState.READY && e.ready == 1)

        c.changeDocument("file:///w/a.py", "x = 3\n")
        val ch = w.last()
        check("klien: didChange seluruh teks tanpa range",
            ch["method"] == "textDocument/didChange" && ((ch["params"] as Map<*, *>)["contentChanges"] as List<*>)[0].let { (it as Map<*, *>)["text"] == "x = 3\n" && !it.containsKey("range") })
        check("klien: versi naik", ((ch["params"] as Map<*, *>)["textDocument"] as Map<*, *>)["version"] == 3.0)
        val before = w.sent.size
        c.changeDocument("file:///w/a.py", "x = 3\n")
        check("klien: teks sama tidak mengirim apa pun", w.sent.size == before)
        c.changeDocument("file:///w/tak-dikenal.py", "x")
        check("klien: dokumen tak dikenal diabaikan", w.sent.size == before)
        c.openDocument("file:///w/a.py", "python", "x = 4\n")
        check("klien: buka ulang dokumen terbuka = didChange", w.last()["method"] == "textDocument/didChange")

        c.feed(server(mapOf("jsonrpc" to "2.0", "method" to "textDocument/publishDiagnostics", "params" to mapOf(
            "uri" to "file:///w/a.py", "version" to 4, "diagnostics" to listOf(
                mapOf("range" to mapOf("start" to mapOf("line" to 0, "character" to 4), "end" to mapOf("line" to 0, "character" to 5)), "severity" to 1, "message" to "m", "code" to "E1"))))))
        check("klien: diagnostik diteruskan ke listener", e.diags.size == 1 && e.diags[0].first == "file:///w/a.py" && e.diags[0].second == 4 && e.diags[0].third[0].col == 5)
        c.feed(server(mapOf("jsonrpc" to "2.0", "method" to "textDocument/publishDiagnostics", "params" to mapOf("uri" to "file:///w/a.py", "diagnostics" to emptyList<Any?>()))))
        check("klien: diagnostik kosong menghapus, versi null", e.diags.size == 2 && e.diags[1].third.isEmpty() && e.diags[1].second == null)
        c.feed(server(mapOf("jsonrpc" to "2.0", "method" to "window/logMessage", "params" to mapOf("message" to "x"))))
        check("klien: notifikasi lain diabaikan", e.diags.size == 2 && e.closed == null)

        c.closeDocument("file:///w/a.py")
        check("klien: didClose", w.last()["method"] == "textDocument/didClose")
        c.closeDocument("file:///w/a.py")
        check("klien: tutup dua kali tidak mengirim lagi", w.methods().count { it == "textDocument/didClose" } == 1)

        val n0 = w.sent.size
        c.shutdown()
        check("klien: shutdown mengirim permintaan shutdown", w.last()["method"] == "shutdown" && c.state == LspState.SHUTTING_DOWN)
        val sid = (w.last()["id"] as Double).toLong()
        c.openDocument("file:///w/b.py", "python", "x")
        check("klien: tidak membuka dokumen saat shutdown", w.sent.size == n0 + 1)
        c.feed(reply(sid, null))
        check("klien: exit setelah jawaban shutdown, lalu CLOSED", w.last()["method"] == "exit" && !w.last().containsKey("params") && c.state == LspState.CLOSED)
        check("klien: onClosed sekali", e.closedCount == 1 && e.closed == "selesai")
        val n1 = w.sent.size
        c.openDocument("file:///w/c.py", "python", "x")
        c.feed(reply(99, null))
        check("klien: setelah CLOSED tak mengirim dan tak bereaksi", w.sent.size == n1 && e.closedCount == 1)
    }

    // ---- permintaan dari server harus dijawab
    run {
        val (c, w, _) = readyClient()
        val n = w.sent.size
        c.feed(server(mapOf("jsonrpc" to "2.0", "id" to 7, "method" to "workspace/configuration", "params" to mapOf("items" to listOf(mapOf("section" to "a"), mapOf("section" to "b"))))))
        check("server-req: configuration dijawab larik null seukuran items",
            w.sent.size == n + 1 && w.last()["id"] == 7.0 && w.last()["result"] == listOf<Any?>(null, null) && w.last().containsKey("result"))
        c.feed(server(mapOf("jsonrpc" to "2.0", "id" to "abc", "method" to "window/workDoneProgress/create", "params" to mapOf("token" to 1))))
        check("server-req: id string dipertahankan, result null ada", w.last()["id"] == "abc" && w.last().containsKey("result") && w.last()["result"] == null)
        c.feed(server(mapOf("jsonrpc" to "2.0", "id" to 8, "method" to "client/registerCapability", "params" to mapOf("registrations" to emptyList<Any?>()))))
        check("server-req: registerCapability dijawab", w.last()["id"] == 8.0 && w.last().containsKey("result"))
        c.feed(server(mapOf("jsonrpc" to "2.0", "id" to 9, "method" to "foo/bar")))
        val er = w.last()["error"] as Map<*, *>
        check("server-req: metode tak dikenal = error -32601", w.last()["id"] == 9.0 && er["code"] == -32601.0)
        check("server-req: klien tetap READY", c.state == LspState.READY)
    }

    // ---- kegagalan
    run {
        val (c, _, e) = newClient()
        c.start("file:///w")
        c.feed(server(mapOf("jsonrpc" to "2.0", "id" to 1, "error" to mapOf("code" to -32603, "message" to "boom"))))
        check("gagal: initialize error menutup klien", c.state == LspState.CLOSED && e.closed!!.contains("boom") && e.ready == 0 && e.closedCount == 1)
    }
    run {
        val (c, _, e) = readyClient()
        c.feed("Content-Length: 3\r\n\r\n{{{".toByteArray())
        check("gagal: JSON rusak menutup klien", c.state == LspState.CLOSED && e.closed!!.startsWith("protokol rusak"))
        val dcount = e.diags.size
        c.feed(server(mapOf("jsonrpc" to "2.0", "method" to "textDocument/publishDiagnostics", "params" to mapOf("uri" to "u", "diagnostics" to emptyList<Any?>()))))
        check("gagal: setelah rusak, byte berikutnya diabaikan", e.diags.size == dcount && e.closedCount == 1)
    }
    run {
        val (c, _, e) = readyClient()
        c.feed("Content-Length: 2\r\n\r\n[]".toByteArray())
        check("gagal: pesan bukan objek menutup klien", c.state == LspState.CLOSED && e.closedCount == 1)
    }
    run {
        val (c, _, e) = readyClient()
        c.feed("Content-Length: 0\r\n\r\n".toByteArray())
        check("badan kosong diabaikan, klien tetap hidup", c.state == LspState.READY && e.closedCount == 0)
    }
    run {
        val (c, _, e) = newClient()
        var got: String? = null
        c.start("file:///w")
        c.transportClosed("proses mati")
        got = e.closed
        check("transport putus: CLOSED dengan alasan", c.state == LspState.CLOSED && got == "proses mati")
        c.transportClosed("lagi")
        check("transport putus: onClosed tepat sekali", e.closedCount == 1)
    }
    run {
        val (c, w, e) = readyClient()
        w.failWrites = true
        c.changeDocument("file:///w/x.py", "a")
        c.openDocument("file:///w/x.py", "python", "a")
        check("tulis gagal: klien menutup diri", c.state == LspState.CLOSED && e.closed!!.startsWith("gagal menulis"))
    }
    run {
        val (c, w, e) = newClient()
        c.start("file:///w")
        c.shutdown()
        check("shutdown sebelum siap: langsung CLOSED tanpa mengirim shutdown", c.state == LspState.CLOSED && w.methods() == listOf("initialize") && e.closed == "dibatalkan")
    }
    run {
        val (c, w, e) = newClient()
        c.openDocument("file:///w/a.py", "python", "x")
        c.start(null)
        check("start tanpa rootUri: rootUri null terkirim", (w.last()["params"] as Map<*, *>).containsKey("rootUri") && (w.last()["params"] as Map<*, *>)["rootUri"] == null)
        c.feed(reply(1, emptyMap<String, Any?>()))
        check("dokumen yang dibuka sebelum start ikut terkirim", w.methods().last() == "textDocument/didOpen" && e.ready == 1)
    }
    run {
        val (c, w, _) = readyClient()
        c.openDocument("file:///w/a.py", "python", "x")
        c.openDocument("file:///w/b.py", "python", "y")
        c.closeDocument("file:///w/a.py")
        check("banyak dokumen: urutan open, open, close", w.methods().takeLast(3) == listOf("textDocument/didOpen", "textDocument/didOpen", "textDocument/didClose"))
    }

    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails != 0) kotlin.system.exitProcess(1)
}
