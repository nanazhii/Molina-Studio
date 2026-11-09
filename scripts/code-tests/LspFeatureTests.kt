import com.molina.code.check.Diagnostic
import com.molina.code.lsp.LspClient
import com.molina.code.lsp.LspCompletionItem
import com.molina.code.lsp.LspCompletionUi
import com.molina.code.lsp.LspCompletions
import com.molina.code.lsp.LspTextEdit
import com.molina.code.lsp.LspDecoder
import com.molina.code.lsp.LspDefinitions
import com.molina.code.lsp.LspFraming
import com.molina.code.lsp.LspJson
import com.molina.code.lsp.LspListener
import com.molina.code.lsp.LspLocation

// [CODE-LSP4] Uji JVM murni: parser completion/definition dan permintaan lewat LspClient.

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private class Wire {
    val sent = ArrayList<Map<*, *>>()
    private val dec = LspDecoder()
    val send: (ByteArray) -> Unit = { b -> dec.feed(b, 0, b.size) { sent.add(LspJson.parse(it) as Map<*, *>) } }
    fun methods(): List<Any?> = sent.map { it["method"] }
    fun lastId(): Long = (sent.last()["id"] as Double).toLong()
}

private class Events : LspListener {
    var closed: String? = null
    override fun onClosed(reason: String) { closed = reason }
}

private fun server(o: Any?): ByteArray = LspFraming.encode(LspJson.write(o))
private fun reply(id: Long, result: Any?): ByteArray = server(mapOf("jsonrpc" to "2.0", "id" to id, "result" to result))
private fun replyErr(id: Long, msg: String): ByteArray =
    server(mapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to -32603.0, "message" to msg)))

private fun pos(l: Int, c: Int) = mapOf("line" to l.toDouble(), "character" to c.toDouble())
private fun range(l1: Int, c1: Int, l2: Int, c2: Int) = mapOf("start" to pos(l1, c1), "end" to pos(l2, c2))

private fun readyClient(): Triple<LspClient, Wire, Events> {
    val w = Wire(); val e = Events()
    val c = LspClient(w.send, e)
    c.start("file:///sdcard/p")
    c.feed(reply(w.lastId(), mapOf("capabilities" to emptyMap<String, Any?>())))
    return Triple(c, w, e)
}

fun main() {
    // ---------------------------------------------------------------- parser completion
    check("completion null -> kosong", LspCompletions.convert(null).isEmpty())
    check("completion tipe asing -> kosong", LspCompletions.convert("x").isEmpty())
    val arr = LspCompletions.convert(listOf(
        mapOf("label" to "pardir", "kind" to 6.0, "detail" to "str", "sortText" to "b"),
        mapOf("label" to "path", "insertText" to "path", "sortText" to "a"),
        mapOf("label" to "", "insertText" to "kosong"),
        mapOf("detail" to "tanpa label"),
        "bukan objek",
    ))
    check("completion: item tanpa label dibuang", arr.size == 2)
    check("completion: urut sortText", arr.map { it.label } == listOf("path", "pardir"))
    check("completion: kind dan detail terbaca", arr[1].kind == 6 && arr[1].detail == "str")
    check("completion: insertText jatuh ke label", arr[1].insertText == "pardir")
    val lst = LspCompletions.convert(mapOf("isIncomplete" to true, "items" to listOf(mapOf("label" to "join"))))
    check("completion: CompletionList", lst.size == 1 && lst[0].label == "join")
    val te = LspCompletions.convert(listOf(mapOf("label" to "listdir", "insertText" to "salah",
        "textEdit" to mapOf("range" to range(3, 5, 3, 7), "newText" to "listdir"))))[0]
    check("completion: textEdit menang atas insertText", te.insertText == "listdir" && te.edit?.startColumn == 5 && te.edit?.endColumn == 7)
    val ire = LspCompletions.convert(listOf(mapOf("label" to "x",
        "textEdit" to mapOf("insert" to range(1, 0, 1, 1), "replace" to range(1, 0, 1, 4), "newText" to "xyz"))))[0]
    check("completion: InsertReplaceEdit memakai replace", ire.edit?.endColumn == 4 && ire.insertText == "xyz")
    val many = LspCompletions.convert(List(500) { mapOf("label" to "i%03d".format(it)) })
    check("completion: dibatasi ${LspCompletions.MAX_ITEMS}", many.size == LspCompletions.MAX_ITEMS && many[0].label == "i000")
    val same = LspCompletions.convert(listOf(mapOf("label" to "b"), mapOf("label" to "a", "sortText" to "b")))
    check("completion: urutan stabil bila sortKey sama", same.map { it.label } == listOf("b", "a"))
    val neg = LspCompletions.convert(listOf(mapOf("label" to "n", "textEdit" to mapOf("range" to range(-1, -4, 0, 0), "newText" to "n"))))[0]
    check("completion: posisi negatif dijepit 0", neg.edit?.startLine == 0 && neg.edit?.startColumn == 0)

    // ---------------------------------------------------------------- parser definition
    check("definition null -> kosong", LspDefinitions.convert(null).isEmpty())
    val one = LspDefinitions.convert(mapOf("uri" to "file:///sdcard/a.py", "range" to range(9, 4, 9, 8)))
    check("definition: Location tunggal", one == listOf(LspLocation("file:///sdcard/a.py", 9, 4)))
    val link = LspDefinitions.convert(listOf(mapOf("targetUri" to "file:///sdcard/b.py",
        "targetRange" to range(1, 0, 5, 0), "targetSelectionRange" to range(2, 6, 2, 9))))
    check("definition: LocationLink pakai targetSelectionRange", link == listOf(LspLocation("file:///sdcard/b.py", 2, 6)))
    val dup = LspDefinitions.convert(listOf(
        mapOf("uri" to "file:///a.py", "range" to range(1, 1, 1, 2)),
        mapOf("uri" to "file:///a.py", "range" to range(1, 1, 1, 9)),
        mapOf("uri" to "file:///a.py"),
        mapOf("range" to range(0, 0, 0, 1)),
    ))
    check("definition: duplikat dan item rusak dibuang", dup.size == 1)
    val lots = LspDefinitions.convert(List(120) { mapOf("uri" to "file:///f$it.py", "range" to range(it, 0, it, 1)) })
    check("definition: dibatasi ${LspDefinitions.MAX_ITEMS}", lots.size == LspDefinitions.MAX_ITEMS)

    // ---------------------------------------------------------------- klien
    run {
        val w = Wire(); val e = Events()
        val c = LspClient(w.send, e)
        c.start(null)
        val caps = ((w.sent[0]["params"] as Map<*, *>)["capabilities"] as Map<*, *>)["textDocument"] as Map<*, *>
        val comp = caps["completion"] as Map<*, *>
        check("initialize mengumumkan completion tanpa snippet",
            ((comp["completionItem"] as Map<*, *>)["snippetSupport"]) == false)
        check("initialize mengumumkan definition tanpa linkSupport", (caps["definition"] as Map<*, *>)["linkSupport"] == false)
        check("completion sebelum siap = false", !c.completion("file:///sdcard/a.py", 0, 0) { _, _ -> })
    }
    run {
        val (c, w, _) = readyClient()
        check("dokumen tak dikenal = false", !c.completion("file:///sdcard/a.py", 0, 0) { _, _ -> })
        c.openDocument("file:///sdcard/a.py", "python", "import os\nos.pa")
        var got: List<LspCompletionItem>? = null; var err: String? = "belum"
        val n = w.sent.size
        check("completion terkirim", c.completion("file:///sdcard/a.py", 1, 5) { l, e -> got = l; err = e })
        check("didOpen mendahului completion", w.methods().takeLast(2) == listOf("textDocument/didOpen", "textDocument/completion"))
        val params = w.sent.last()["params"] as Map<*, *>
        check("parameter posisi benar", (params["position"] as Map<*, *>)["line"] == 1.0 && (params["position"] as Map<*, *>)["character"] == 5.0)
        check("tepat satu pesan baru selain open", w.sent.size == n + 1)
        c.feed(reply(w.lastId(), listOf(mapOf("label" to "path"))))
        check("balasan completion diparse", got?.map { it.label } == listOf("path") && err == null)

        var loc: List<com.molina.code.lsp.LspLocation>? = null
        c.definition("file:///sdcard/a.py", 1, 1) { l, _ -> loc = l }
        c.feed(reply(w.lastId(), listOf(mapOf("uri" to "file:///usr/lib/python3/os.py", "range" to range(10, 0, 10, 2)))))
        check("balasan definition diparse", loc == listOf(LspLocation("file:///usr/lib/python3/os.py", 10, 0)))

        var none: List<com.molina.code.lsp.LspLocation>? = null
        c.definition("file:///sdcard/a.py", 0, 0) { l, _ -> none = l }
        c.feed(reply(w.lastId(), null))
        check("definition null = daftar kosong (bukan galat)", none?.isEmpty() == true)

        var bad: String? = null
        c.completion("file:///sdcard/a.py", 0, 0) { _, e -> bad = e }
        c.feed(replyErr(w.lastId(), "jedi meledak"))
        check("galat server sampai ke callback", bad == "jedi meledak")

        var closedReason: String? = null
        c.completion("file:///sdcard/a.py", 0, 0) { _, e -> closedReason = e }
        c.transportClosed("proses mati")
        check("permintaan tertunda dijawab galat saat ditutup", closedReason == "proses mati")
        check("completion setelah ditutup = false", !c.completion("file:///sdcard/a.py", 0, 0) { _, _ -> })
    }
    run {
        val (c, w, _) = readyClient()
        c.openDocument("file:///sdcard/a.py", "python", "x")
        c.closeDocument("file:///sdcard/a.py")
        check("dokumen sudah ditutup = false", !c.completion("file:///sdcard/a.py", 0, 0) { _, _ -> })
        check("tidak ada permintaan terkirim untuk dokumen tertutup", w.methods().none { it == "textDocument/completion" })
        c.openDocument("file:///sdcard/b.py", "python", "y")
        check("posisi negatif dijepit", c.completion("file:///sdcard/b.py", -3, -9) { _, _ -> } &&
            ((w.sent.last()["params"] as Map<*, *>)["position"] as Map<*, *>).values.all { it == 0.0 })
    }

    // ---------------------------------------------------------------- aturan popup [CODE-LSP5]
    fun item(edit: LspTextEdit?) = LspCompletionItem("x", "x", null, null, "x", edit)
    check("prefix: tanpa textEdit memakai awalan pengenal", LspCompletionUi.prefixLength(item(null), 2, 6, 3) == 3)
    check("prefix: awalan dijepit ke kolom", LspCompletionUi.prefixLength(item(null), 2, 2, 9) == 2)
    check("prefix: textEdit berakhir di kursor", LspCompletionUi.prefixLength(item(LspTextEdit(2, 3, 2, 6, "x")), 2, 6, 1) == 3)
    check("prefix: textEdit melewati kursor diabaikan", LspCompletionUi.prefixLength(item(LspTextEdit(2, 3, 2, 9, "x")), 2, 6, 1) == 1)
    check("prefix: textEdit di baris lain diabaikan", LspCompletionUi.prefixLength(item(LspTextEdit(1, 3, 2, 6, "x")), 2, 6, 2) == 2)
    check("prefix: textEdit mulai setelah kursor diabaikan", LspCompletionUi.prefixLength(item(LspTextEdit(2, 8, 2, 6, "x")), 2, 6, 0) == 0)
    check("kind: 1 = Text, 9 = Module, 25 = TypeParameter",
        LspCompletionUi.kindName(1) == "Text" && LspCompletionUi.kindName(9) == "Module" && LspCompletionUi.kindName(25) == "TypeParameter")
    check("kind: di luar jangkauan = null", LspCompletionUi.kindName(0) == null && LspCompletionUi.kindName(26) == null && LspCompletionUi.kindName(null) == null)
    check("pemicu: awalan pengenal", LspCompletionUi.shouldTrigger("os.pa", 5))
    check("pemicu: tepat setelah titik", LspCompletionUi.shouldTrigger("os.", 3))
    check("pemicu: baris kosong tidak", !LspCompletionUi.shouldTrigger("", 0))
    check("pemicu: setelah spasi tidak", !LspCompletionUi.shouldTrigger("x = ", 4))
    check("pemicu: setelah kurung tidak", !LspCompletionUi.shouldTrigger("f(", 2))
    check("pemicu: kolom di luar baris dijepit", LspCompletionUi.shouldTrigger("abc", 99))

    if (fails > 0) { println("\n$fails uji GAGAL"); System.exit(1) } else println("\nSemua uji lulus")
}
