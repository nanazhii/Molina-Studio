import com.molina.code.check.Diagnostic
import com.molina.code.lsp.LeadingPidFilter
import com.molina.code.lsp.LspDecoder
import com.molina.code.lsp.LspFraming
import com.molina.code.lsp.LspJson
import com.molina.code.lsp.LspPathMap
import com.molina.code.lsp.LspSession
import com.molina.code.lsp.LspSessionListener
import com.molina.code.lsp.LspState
import com.molina.code.lsp.StreamExecutor
import com.molina.code.lsp.StreamHandle
import com.molina.code.lsp.StreamListener
import com.molina.code.lsp.StreamRequest
import com.molina.code.lsp.StreamResult

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private const val ROOT = "/storage/emulated/0"

// ---------------------------------------------------------------- fakes

private class FakeHandle(val failWrites: Boolean = false) : StreamHandle {
    val sent = ArrayList<Map<*, *>>()
    private val dec = LspDecoder()
    var cancels = 0
    var inputClosed = false
    override fun write(data: ByteArray): Boolean {
        if (failWrites || inputClosed) return false
        dec.feed(data, 0, data.size) { sent.add(LspJson.parse(it) as Map<*, *>) }
        return true
    }
    override fun closeInput() { inputClosed = true }
    override fun cancel() { cancels++ }
    fun methods(): List<Any?> = sent.map { it["method"] }
}

private class FakeExecutor(
    val failWrites: Boolean = false,
    val syncExit: StreamResult? = null,
) : StreamExecutor {
    val handle = FakeHandle(failWrites)
    var request: StreamRequest? = null
    var listener: StreamListener? = null
    var starts = 0
    override fun startStream(request: StreamRequest, listener: StreamListener): StreamHandle {
        starts++
        this.request = request
        this.listener = listener
        syncExit?.let { listener.onExit(it) }
        return handle
    }
    fun server(json: String) {
        val b = LspFraming.encode(json)
        listener!!.onStdout(b, b.size)
    }
}

private class Events : LspSessionListener {
    var ready = 0
    var closed: String? = null
    var closedCount = 0
    val logs = StringBuilder()
    val diags = ArrayList<Triple<String, Int?, List<Diagnostic>>>()
    override fun onReady() { ready++ }
    override fun onDiagnostics(hostPath: String, version: Int?, items: List<Diagnostic>) { diags.add(Triple(hostPath, version, items)) }
    override fun onServerLog(text: String) { logs.append(text) }
    override fun onClosed(reason: String) { closed = reason; closedCount++ }
}

private fun req() = StreamRequest("pylsp", listOf("pylsp"), "$ROOT/Molina-Kerja")
private fun paths(full: Boolean = false) = LspPathMap(ROOT, "Molina-Kerja", full, listOf("Molina"))

private fun session(ex: FakeExecutor, ev: Events, full: Boolean = false) = LspSession(ex, paths(full), req(), ev)

private fun initOk(ex: FakeExecutor) = ex.server("""{"jsonrpc":"2.0","id":1,"result":{"capabilities":{}}}""")

// ---------------------------------------------------------------- LspPathMap

private fun pathMapTests() {
    val p = paths()
    check("host->guest di workspace", p.hostToGuestPath("$ROOT/Molina-Kerja/a/x.py") == "/sdcard/Molina-Kerja/a/x.py")
    check("host->guest extra root", p.hostToGuestPath("$ROOT/Molina/y.sh") == "/sdcard/Molina/y.sh")
    check("host di luar workspace = null", p.hostToGuestPath("$ROOT/DCIM/z.py") == null)
    check("host di luar storage = null", p.hostToGuestPath("/data/data/app/z.py") == null)
    check("host dengan .. = null", p.hostToGuestPath("$ROOT/Molina-Kerja/../DCIM/z.py") == null)
    check("guest /sdcard -> host", p.guestToHostPath("/sdcard/Molina-Kerja/a/x.py") == "$ROOT/Molina-Kerja/a/x.py")
    check("guest /storage/emulated/0 -> host", p.guestToHostPath("/storage/emulated/0/Molina-Kerja/x.py") == "$ROOT/Molina-Kerja/x.py")
    check("guest tak terlihat = null", p.guestToHostPath("/sdcard/DCIM/z.py") == null)
    check("guest di luar storage = null (stdlib python)", p.guestToHostPath("/usr/lib/python3.13/os.py") == null)
    check("guest dengan .. = null", p.guestToHostPath("/sdcard/Molina-Kerja/../DCIM/z.py") == null)
    check("guest /sdcard-lain bukan awalan sah", p.guestToHostPath("/sdcardx/Molina-Kerja/a.py") == null)
    val f = paths(true)
    check("akses penuh: host->guest", f.hostToGuestPath("$ROOT/DCIM/z.py") == "/sdcard/DCIM/z.py")
    check("akses penuh: guest->host", f.guestToHostPath("/sdcard/DCIM/z.py") == "$ROOT/DCIM/z.py")
    check("akses penuh: root sendiri", f.guestToHostPath("/sdcard") == ROOT)
    val odd = "$ROOT/Molina-Kerja/folder spasi/büku #1.py"
    val uri = p.hostToGuestUri(odd)
    check("URI guest dienkode", uri == "file:///sdcard/Molina-Kerja/folder%20spasi/b%C3%BCku%20%231.py")
    check("URI guest bolak-balik", uri != null && p.guestUriToHostPath(uri) == odd)
    check("URI bukan file:// = null", p.guestUriToHostPath("untitled:Untitled-1") == null)
    check("URI rusak = null", p.guestUriToHostPath("file:///sdcard/Molina-Kerja/%zz") == null)
    check("URI stdlib = null", p.guestUriToHostPath("file:///usr/lib/python3/os.py") == null)
}

// ---------------------------------------------------------------- LeadingPidFilter

private class Collector {
    var pid: Int? = null
    val data = java.io.ByteArrayOutputStream()
    fun feed(f: LeadingPidFilter, s: String) = feed(f, s.toByteArray(Charsets.ISO_8859_1))
    fun feed(f: LeadingPidFilter, b: ByteArray) = f.feed(b, b.size, { pid = it }, { d, n -> data.write(d, 0, n) })
    fun text() = String(data.toByteArray(), Charsets.ISO_8859_1)
}

private fun pidFilterTests() {
    run {
        val c = Collector(); val f = LeadingPidFilter()
        c.feed(f, "4242\nContent-Length: 2\r\n\r\n{}")
        check("PID satu potongan: dibuang, sisa utuh", c.pid == 4242 && c.text() == "Content-Length: 2\r\n\r\n{}")
    }
    run {
        val c = Collector(); val f = LeadingPidFilter()
        for (ch in "77\nabc") c.feed(f, ch.toString())
        check("PID terbelah per byte", c.pid == 77 && c.text() == "abc")
    }
    run {
        val c = Collector(); val f = LeadingPidFilter()
        c.feed(f, "123\n"); c.feed(f, "x"); c.feed(f, "123\ny")
        check("setelah selesai, baris angka berikutnya tidak dibuang", c.pid == 123 && c.text() == "x123\ny")
    }
    run {
        val c = Collector(); val f = LeadingPidFilter()
        c.feed(f, "halo dunia\nsisa")
        check("baris pertama bukan PID: diteruskan utuh", c.pid == null && c.text() == "halo dunia\nsisa")
    }
    run {
        val c = Collector(); val f = LeadingPidFilter()
        c.feed(f, "A".repeat(40) + "\nz")
        check("tanpa LF dalam 32 byte: diteruskan utuh", c.pid == null && c.text() == "A".repeat(40) + "\nz")
    }
    run {
        val c = Collector(); val f = LeadingPidFilter()
        c.feed(f, ByteArray(0)); c.feed(f, "5\n")
        check("potongan kosong diabaikan", c.pid == 5 && c.text() == "")
    }
    run {
        val c = Collector(); val f = LeadingPidFilter()
        c.feed(f, byteArrayOf(0x31, 0x0A, 0xE2.toByte(), 0x82.toByte(), 0xAC.toByte()))
        check("byte non-ASCII tidak rusak", c.pid == 1 && c.data.toByteArray().contentEquals(byteArrayOf(0xE2.toByte(), 0x82.toByte(), 0xAC.toByte())))
    }
}

// ---------------------------------------------------------------- LspSession

private fun sessionTests() {
    run { // mulai: initialize dengan rootUri guest
        val ex = FakeExecutor(); val ev = Events(); val s = session(ex, ev)
        check("start true", s.start("$ROOT/Molina-Kerja/proj"))
        check("start kedua false", !s.start("$ROOT/Molina-Kerja/proj") && ex.starts == 1)
        val init = ex.handle.sent.first()
        val params = init["params"] as Map<*, *>
        check("initialize terkirim", init["method"] == "initialize")
        check("rootUri = URI path guest", params["rootUri"] == "file:///sdcard/Molina-Kerja/proj")
        check("request diteruskan ke executor", ex.request?.guestArgv == listOf("pylsp") && ex.request?.name == "pylsp")
        check("state INITIALIZING", s.state == LspState.INITIALIZING)
    }
    run { // rootUri tidak terlihat -> null
        val ex = FakeExecutor(); val s = session(ex, Events())
        s.start("$ROOT/DCIM")
        check("rootUri null bila folder tak terlihat", (ex.handle.sent.first()["params"] as Map<*, *>)["rootUri"] == null)
    }
    run { // alur lengkap
        val ex = FakeExecutor(); val ev = Events(); val s = session(ex, ev)
        s.start("$ROOT/Molina-Kerja")
        val file = "$ROOT/Molina-Kerja/src/a b.py"
        check("openDocument sebelum siap diterima", s.openDocument(file, "python", "import os\n"))
        check("belum ada didOpen sebelum initialize selesai", ex.handle.methods() == listOf("initialize"))
        initOk(ex)
        check("siap: onReady sekali, state READY", ev.ready == 1 && s.state == LspState.READY)
        check("setelah siap: initialized lalu didOpen", ex.handle.methods() == listOf("initialize", "initialized", "textDocument/didOpen"))
        val open = (ex.handle.sent.last()["params"] as Map<*, *>)["textDocument"] as Map<*, *>
        check("didOpen memakai URI GUEST (bukan path Android)", open["uri"] == "file:///sdcard/Molina-Kerja/src/a%20b.py")
        s.changeDocument(file, "import sys\n")
        check("didChange terkirim", ex.handle.methods().last() == "textDocument/didChange")
        ex.server("""{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":"file:///sdcard/Molina-Kerja/src/a%20b.py","version":2,"diagnostics":[{"range":{"start":{"line":0,"character":7},"end":{"line":0,"character":10}},"severity":1,"message":"salah"}]}}""")
        check("diagnostik: URI guest diterjemahkan ke path Android", ev.diags.size == 1 && ev.diags[0].first == file && ev.diags[0].second == 2)
        check("diagnostik: isi terkonversi", ev.diags[0].third.size == 1 && ev.diags[0].third[0].message == "salah")
        ex.server("""{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":"file:///usr/lib/python3/os.py","diagnostics":[]}}""")
        check("diagnostik berkas di luar yang terlihat diabaikan", ev.diags.size == 1)
        s.closeDocument(file)
        check("didClose terkirim", ex.handle.methods().last() == "textDocument/didClose")
    }
    run { // diagnostik dengan stdout terpotong per byte
        val ex = FakeExecutor(); val ev = Events(); val s = session(ex, ev)
        s.start("$ROOT/Molina-Kerja")
        val b = LspFraming.encode("""{"jsonrpc":"2.0","id":1,"result":{}}""")
        for (x in b) ex.listener!!.onStdout(byteArrayOf(x), 1)
        check("stdout terpotong 1 byte tetap terbaca", ev.ready == 1)
    }
    run { // dokumen di luar yang terlihat ditolak
        val ex = FakeExecutor(); val s = session(ex, Events())
        s.start("$ROOT/Molina-Kerja")
        check("openDocument di luar terlihat = false", !s.openDocument("$ROOT/DCIM/z.py", "python", "x"))
        check("openDocument akses penuh = true", run {
            val ex2 = FakeExecutor(); val s2 = session(ex2, Events(), full = true)
            s2.start("$ROOT/Molina-Kerja"); s2.openDocument("$ROOT/DCIM/z.py", "python", "x")
        })
    }
    run { // sebelum start
        val s = session(FakeExecutor(), Events())
        check("openDocument sebelum start = false", !s.openDocument("$ROOT/Molina-Kerja/a.py", "python", "x"))
        check("state IDLE sebelum start", s.state == LspState.IDLE)
        s.stop(); s.kill()
        check("stop/kill sebelum start tidak melempar", true)
    }
    run { // stop rapi
        val ex = FakeExecutor(); val ev = Events(); val s = session(ex, ev)
        s.start("$ROOT/Molina-Kerja"); initOk(ex)
        s.stop()
        check("stop: shutdown terkirim", ex.handle.methods().last() == "shutdown" && s.state == LspState.SHUTTING_DOWN)
        check("stop: belum ditutup sebelum server menjawab", ev.closedCount == 0 && ex.handle.cancels == 0)
        ex.server("""{"jsonrpc":"2.0","id":2,"result":null}""")
        check("stop: exit terkirim, klien ditutup, proses dimatikan", ex.handle.methods().contains("exit") && ev.closedCount == 1 && ex.handle.cancels >= 1)
        check("openDocument setelah stop = false", !s.openDocument("$ROOT/Molina-Kerja/a.py", "python", "x"))
        ex.listener!!.onExit(StreamResult(0, true, 10))
        check("onExit sesudah tertutup tidak menggandakan onClosed", ev.closedCount == 1)
    }
    run { // kill saat server tak menjawab
        val ex = FakeExecutor(); val ev = Events(); val s = session(ex, ev)
        s.start("$ROOT/Molina-Kerja"); initOk(ex); s.stop(); s.kill()
        check("kill memanggil cancel", ex.handle.cancels == 1)
        ex.listener!!.onExit(StreamResult(-1, true, 50))
        check("kill lalu onExit: onClosed sekali dengan alasan dihentikan", ev.closedCount == 1 && ev.closed == "dihentikan")
    }
    run { // proses mati sendiri
        val ex = FakeExecutor(); val ev = Events(); val s = session(ex, ev)
        s.start("$ROOT/Molina-Kerja"); initOk(ex)
        ex.listener!!.onExit(StreamResult(139, false, 900))
        check("proses mati: onClosed dengan exit code", ev.closedCount == 1 && ev.closed == "server berhenti (exit=139)")
        check("proses mati: state CLOSED", s.state == LspState.CLOSED)
    }
    run { // gagal mulai sinkron (Debian belum terpasang)
        val ex = FakeExecutor(syncExit = StreamResult(-1, false, 0, "Debian belum terpasang."))
        val ev = Events(); val s = session(ex, ev)
        check("start sinkron gagal: tetap true, onClosed berisi pesan", s.start("$ROOT/Molina-Kerja") && ev.closed == "Debian belum terpasang." && ev.closedCount == 1)
        check("start sinkron gagal: tidak ada initialize terkirim", ex.handle.sent.isEmpty() && s.state == LspState.CLOSED)
    }
    run { // tulis gagal -> klien tertutup
        val ex = FakeExecutor(failWrites = true); val ev = Events(); val s = session(ex, ev)
        s.start("$ROOT/Molina-Kerja")
        check("tulis gagal: onClosed sekali, proses dimatikan", ev.closedCount == 1 && ev.closed!!.contains("gagal menulis") && ex.handle.cancels >= 1)
    }
    run { // stderr diteruskan
        val ex = FakeExecutor(); val ev = Events(); val s = session(ex, ev)
        s.start("$ROOT/Molina-Kerja")
        ex.listener!!.onStderr("peringatan "); ex.listener!!.onStderr("server")
        check("stderr diteruskan", ev.logs.toString() == "peringatan server")
    }
    run { // protokol rusak
        val ex = FakeExecutor(); val ev = Events(); val s = session(ex, ev)
        s.start("$ROOT/Molina-Kerja")
        val bad = "Content-Length: abc\r\n\r\n".toByteArray()
        ex.listener!!.onStdout(bad, bad.size)
        check("header rusak: sesi tertutup dan proses dimatikan", ev.closedCount == 1 && ex.handle.cancels >= 1)
    }
    run { // konkurensi: stdout dan panggilan UI serentak
        val ex = FakeExecutor(); val ev = Events(); val s = session(ex, ev)
        s.start("$ROOT/Molina-Kerja"); initOk(ex)
        val file = "$ROOT/Molina-Kerja/c.py"
        s.openDocument(file, "python", "0")
        val t1 = Thread { for (i in 1..300) s.changeDocument(file, "v$i") }
        val t2 = Thread {
            for (i in 1..300) ex.server("""{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":"file:///sdcard/Molina-Kerja/c.py","diagnostics":[]}}""")
        }
        t1.start(); t2.start(); t1.join(); t2.join()
        check("konkurensi: 300 diagnostik tiba, 300 didChange terkirim", ev.diags.size == 300 && ex.handle.methods().count { it == "textDocument/didChange" } == 300)
    }
}

fun main() {
    pathMapTests()
    pidFilterTests()
    sessionTests()
    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails > 0) System.exit(1)
}
