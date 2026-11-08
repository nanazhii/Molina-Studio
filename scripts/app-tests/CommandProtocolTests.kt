import com.molina.studio.cmd.CommandProtocol
import com.molina.studio.cmd.CommandProtocol.Parsed
import com.molina.studio.cmd.CommandProtocol.Verb

private var fails = 0
private var runs = 0
private fun check(name: String, ok: Boolean) { runs++; if (!ok) { fails++; println("GAGAL: $name") } }
private fun <T> eq(name: String, got: T, want: T) { runs++; if (got != want) { fails++; println("GAGAL: $name -> $got (seharusnya $want)") } }

private fun okReq(line: String): CommandProtocol.Request? = (CommandProtocol.parse(line) as? Parsed.Ok)?.req
private fun badCode(line: String): String? = (CommandProtocol.parse(line) as? Parsed.Bad)?.code

fun main() {
    // parse: perintah tanpa argumen
    eq("ping", okReq("ping\n")?.verb, Verb.PING)
    eq("version", okReq("version")?.verb, Verb.VERSION)
    eq("status CRLF", okReq("status\r\n")?.verb, Verb.STATUS)
    eq("stop", okReq("stop\n")?.verb, Verb.STOP)
    eq("huruf besar", okReq("PING\n")?.verb, Verb.PING)
    eq("ping tanpa arg", okReq("ping\n")?.arg, "")

    // parse: play dengan argumen (spasi di dalam path tetap utuh)
    eq("play verb", okReq("play\t/sdcard/a b.mkv\n")?.verb, Verb.PLAY)
    eq("play arg spasi", okReq("play\t/sdcard/a b.mkv\n")?.arg, "/sdcard/a b.mkv")
    eq("play arg dipangkas", okReq("play\t  /sdcard/x.mp4  \n")?.arg, "/sdcard/x.mp4")
    eq("play url", okReq("play\thttps://example.org/v?a=1\n")?.arg, "https://example.org/v?a=1")
    eq("play TAB di arg dipertahankan", okReq("play\t/sdcard/a\tb\n")?.arg, "/sdcard/a\tb")

    // parse: galat
    eq("kosong", badCode(""), "empty")
    eq("hanya newline", badCode("\n"), "empty")
    eq("spasi saja", badCode("   \n"), "empty")
    eq("play tanpa arg", badCode("play\n"), "usage")
    eq("play arg kosong", badCode("play\t \n"), "usage")
    eq("tidak dikenal", badCode("joget\n"), "unknown")
    eq("pemisah spasi bukan TAB", badCode("play /sdcard/a.mp4\n"), "unknown")

    // balasan: satu baris, tanpa TAB/CR/LF, dibatasi
    eq("ok kosong", CommandProtocol.ok(), "OK\n")
    eq("ok teks", CommandProtocol.ok("pong"), "OK pong\n")
    eq("ok newline disapu", CommandProtocol.ok("a\nb\tc\rd"), "OK a b c d\n")
    eq("err", CommandProtocol.err("path", "tidak valid"), "ERR path tidak valid\n")
    check("err sebaris", CommandProtocol.err("x", "a\nb").count { it == '\n' } == 1)
    check("oneLine batas 300", CommandProtocol.oneLine("x".repeat(1000)).length == 300)

    // guestToHost: pemetaan sah
    val root = "/storage/emulated/0"
    eq("sdcard akar", CommandProtocol.guestToHost("/sdcard", root), root)
    eq("storage akar", CommandProtocol.guestToHost("/storage/emulated/0", root), root)
    eq("sdcard berkas", CommandProtocol.guestToHost("/sdcard/Molina/Media/Video/a.mkv", root), "$root/Molina/Media/Video/a.mkv")
    eq("storage berkas", CommandProtocol.guestToHost("/storage/emulated/0/DCIM/x.mp4", root), "$root/DCIM/x.mp4")
    eq("garis ganda dan titik", CommandProtocol.guestToHost("/sdcard//Molina/./a.mp4", root), "$root/Molina/a.mp4")
    eq("garis miring akhir", CommandProtocol.guestToHost("/sdcard/Molina/", root), "$root/Molina")
    eq("root berakhiran slash", CommandProtocol.guestToHost("/sdcard/a.mp4", "$root/"), "$root/a.mp4")
    eq("spasi di nama", CommandProtocol.guestToHost("/sdcard/Film Baru/a b.mkv", root), "$root/Film Baru/a b.mkv")
    eq("root alternatif", CommandProtocol.guestToHost("/sdcard/a.mp4", "/sdcard"), "/sdcard/a.mp4")

    // guestToHost: penolakan
    eq("relatif", CommandProtocol.guestToHost("film.mp4", root), null)
    eq("kosong", CommandProtocol.guestToHost("", root), null)
    eq("titik-titik di tengah", CommandProtocol.guestToHost("/sdcard/Molina/../DCIM/a.mp4", root), null)
    eq("titik-titik keluar", CommandProtocol.guestToHost("/sdcard/../data/data/x", root), null)
    eq("akhiran titik-titik", CommandProtocol.guestToHost("/sdcard/..", root), null)
    eq("root guest", CommandProtocol.guestToHost("/root/.bashrc", root), null)
    eq("etc", CommandProtocol.guestToHost("/etc/passwd", root), null)
    eq("data aplikasi", CommandProtocol.guestToHost("/data/data/com.molina.studio/files/x", root), null)
    eq("awalan mirip", CommandProtocol.guestToHost("/sdcardx/a.mp4", root), null)
    eq("awalan mirip 2", CommandProtocol.guestToHost("/storage/emulated/01/a.mp4", root), null)
    eq("kartu SD", CommandProtocol.guestToHost("/storage/1234-ABCD/a.mp4", root), null)
    eq("NUL", CommandProtocol.guestToHost("/sdcard/a\u0000.mp4", root), null)
    eq("nama berawalan titik tetap sah", CommandProtocol.guestToHost("/sdcard/.hidden/a.mp4", root), "$root/.hidden/a.mp4")
    eq("nama dengan dua titik di dalamnya sah", CommandProtocol.guestToHost("/sdcard/a..b/c.mp4", root), "$root/a..b/c.mp4")

    // open: parse dan pemisah path
    eq("open verb", okReq("open\t/sdcard/a.py\n")?.verb, Verb.OPEN)
    eq("open arg", okReq("open\t/sdcard/a.py\n")?.arg, "/sdcard/a.py")
    eq("open tanpa arg", badCode("open\n"), "usage")
    eq("splitPaths dua", CommandProtocol.splitPaths("/sdcard/a b.py\t/sdcard/c.py"), listOf("/sdcard/a b.py", "/sdcard/c.py"))
    eq("splitPaths kosong", CommandProtocol.splitPaths(""), emptyList<String>())
    eq("splitPaths TAB saja", CommandProtocol.splitPaths("\t\t"), emptyList<String>())
    eq("splitPaths dipangkas", CommandProtocol.splitPaths("  /sdcard/a  "), listOf("/sdcard/a"))
    check("MAX_OPEN", CommandProtocol.MAX_OPEN == 10)

    // Bridge API: clip, notify, share (v2)
    fun clipPut(a: String) = (CommandProtocol.parseClip(a) as? CommandProtocol.ClipParse.Put)?.text
    fun clipBad(a: String) = (CommandProtocol.parseClip(a) as? CommandProtocol.ClipParse.Bad)?.code
    fun shareBad(a: String) = (CommandProtocol.parseShare(a) as? CommandProtocol.ShareParse.Bad)?.code
    val enc = CommandProtocol::encodeB64
    eq("b64 bolak-balik", CommandProtocol.decodeB64(enc("halo \u2713\nbaris2\t")), "halo \u2713\nbaris2\t")
    eq("b64 tidak sah", CommandProtocol.decodeB64("***"), null)
    eq("b64 bukan UTF-8", CommandProtocol.decodeB64("/w=="), null)
    check("clip get", CommandProtocol.parseClip("get") === CommandProtocol.ClipParse.Get)
    eq("clip set", clipPut("set\t" + enc("a b")), "a b")
    eq("clip set kosong", clipPut("set"), "")
    eq("clip get dengan sisa", clipBad("get x"), "usage")
    eq("clip kosong", badCode("clip\n"), "usage")
    eq("clip set base64 rusak", badCode("clip\tset\t***\n"), "usage")
    eq("clip set 64 KB sah", clipPut("set\t" + enc("x".repeat(CommandProtocol.MAX_CLIP)))?.length, CommandProtocol.MAX_CLIP)
    eq("clip set 64 KB + 1", clipBad("set\t" + enc("x".repeat(CommandProtocol.MAX_CLIP + 1))), "toolong")
    eq("clip set sangat besar", clipBad("set\t" + "A".repeat(100000)), "toolong")
    eq("clip lolos parse", okReq("clip\tset\t" + enc("y".repeat(20000)) + "\n")?.verb, Verb.CLIP)
    eq("notify verb", okReq("notify\tHalo\n")?.verb, Verb.NOTIFY)
    eq("notify tanpa arg", badCode("notify\n"), "usage")
    eq("notify judul bawaan", CommandProtocol.parseNotify("Halo")?.title, "Molina")
    eq("notify teks", CommandProtocol.parseNotify("Halo dunia")?.text, "Halo dunia")
    eq("notify judul+teks", CommandProtocol.parseNotify("Build\tselesai")?.let { it.title + "|" + it.text }, "Build|selesai")
    eq("notify judul kosong", CommandProtocol.parseNotify("\tisi")?.title, "Molina")
    eq("notify teks kosong", CommandProtocol.parseNotify("Judul\t  "), null)
    eq("notify judul dipotong", CommandProtocol.parseNotify("j".repeat(200) + "\tx")?.title?.length, CommandProtocol.MAX_NOTIFY_TITLE)
    eq("notify teks dipotong", CommandProtocol.parseNotify("t".repeat(900))?.text?.length, CommandProtocol.MAX_NOTIFY_TEXT)
    eq("notify buang bidi dan lebar nol", CommandProtocol.parseNotify("\u202eBuild\tsel\u200besai")?.let { it.title + "|" + it.text }, "Build|selesai") // [NOTIFY-SPOOF]
    eq("notify judul hanya karakter format -> bawaan", CommandProtocol.parseNotify("\u202e\u200b\tisi")?.title, "Molina")
    eq("notify teks hanya karakter format -> kosong", CommandProtocol.parseNotify("Judul\t\u200b\u202e"), null)
    eq("notify baris baru di teks tetap", CommandProtocol.parseNotify("Judul\tbaris1\nbaris2")?.text, "baris1\nbaris2")
    eq("notify kontrol di judul dibuang", CommandProtocol.parseNotify("Ju\u0007dul\tx")?.title, "Judul")
    eq("notify pemisah baris U+2028 dibuang", CommandProtocol.parseNotify("J\u2028t\tx")?.title, "Jt")
    eq("notify awalan", CommandProtocol.notifyHeadline("Build"), "CLI \u00b7 Build")
    check("notify awalan juga untuk judul yang meniru Molina", CommandProtocol.notifyHeadline("Molina Shield").startsWith(CommandProtocol.NOTIFY_PREFIX))
    check("notify judul dipotong sebelum awalan", CommandProtocol.notifyHeadline(CommandProtocol.parseNotify("j".repeat(200) + "\tx")!!.title).length == CommandProtocol.NOTIFY_PREFIX.length + CommandProtocol.MAX_NOTIFY_TITLE)
    eq("share tanpa arg", badCode("share\n"), "usage")
    eq("share file lolos", okReq("share\tfile\t/sdcard/a.png\n")?.verb, Verb.SHARE)
    eq("share file", (CommandProtocol.parseShare("file\t/sdcard/a b.png\t/sdcard/c.png") as? CommandProtocol.ShareParse.Files)?.paths,
        listOf("/sdcard/a b.png", "/sdcard/c.png"))
    eq("share file kosong", badCode("share\tfile\t\n"), "usage")
    eq("share file 11", shareBad("file\t" + List(11) { "/sdcard/$it" }.joinToString("\t")), "many")
    eq("share text", (CommandProtocol.parseShare("text\t" + enc("hai")) as? CommandProtocol.ShareParse.Text)?.text, "hai")
    eq("share text rusak", shareBad("text\t!!!"), "usage")
    eq("share text kosong", shareBad("text\t" + enc("  ")), "usage")
    eq("share jenis lain", shareBad("foo\tx"), "usage")
    eq("share text panjang lolos MAX_ARG", okReq("share\ttext\t" + enc("x".repeat(5000)) + "\n")?.verb, Verb.SHARE)
    eq("argumen biasa terlalu panjang", badCode("play\t" + "a".repeat(5000) + "\n"), "toolong")
    eq("okPayload kosong", CommandProtocol.okPayload(""), "OK\n")
    eq("okPayload", CommandProtocol.okPayload("abc=="), "OK abc==\n")
    eq("okPayload tidak dipotong", CommandProtocol.okPayload("A".repeat(1000)).length, 1004)
    check("batas baru", CommandProtocol.MAX_LINE == 131072 && CommandProtocol.MAX_ARG == 4096)
    eq("jumlah verb", Verb.entries.size, 9)

    check("konstanta", CommandProtocol.SOCKET_NAME == "molina.cmd" && CommandProtocol.VERSION == 2)
    check("verb unik", Verb.entries.map { it.wire }.toSet().size == Verb.entries.size)

    // [CLI-SCOPE] inScope + gapOk
    val sr = "/storage/emulated/0"
    val allow = listOf("Proyek", "Molina")
    check("scope: akses penuh lolos semua", CommandProtocol.inScope("$sr/DCIM/a.jpg", sr, true, allow))
    check("scope: folder kerja", CommandProtocol.inScope("$sr/Proyek/a.txt", sr, false, allow))
    check("scope: folder Molina", CommandProtocol.inScope("$sr/Molina/Media/Video/a.mkv", sr, false, allow))
    check("scope: akar folder sendiri", CommandProtocol.inScope("$sr/Molina", sr, false, allow))
    check("scope: DCIM ditolak", !CommandProtocol.inScope("$sr/DCIM/a.jpg", sr, false, allow))
    check("scope: Download ditolak", !CommandProtocol.inScope("$sr/Download/a.mkv", sr, false, allow))
    check("scope: awalan mirip Molina", !CommandProtocol.inScope("$sr/Molina2/a", sr, false, allow))
    check("scope: awalan mirip folder kerja", !CommandProtocol.inScope("$sr/Proyek-x/a", sr, false, allow))
    check("scope: akar storage ditolak", !CommandProtocol.inScope(sr, sr, false, allow))
    check("scope: huruf besar-kecil beda ditolak", !CommandProtocol.inScope("$sr/molina/a", sr, false, allow))
    check("scope: root berakhiran slash", CommandProtocol.inScope("$sr/Molina/a", "$sr/", false, allow))
    check("scope: daftar kosong menolak semua", !CommandProtocol.inScope("$sr/Molina/a", sr, false, emptyList()))
    check("scope: lewat guestToHost", CommandProtocol.inScope(CommandProtocol.guestToHost("/sdcard//Molina/./a.mkv", sr)!!, sr, false, allow))
    check("scope: guestToHost DCIM ditolak", !CommandProtocol.inScope(CommandProtocol.guestToHost("/sdcard/DCIM/a.jpg", sr)!!, sr, false, allow))
    check("gapOk: terlalu cepat", !CommandProtocol.gapOk(1000L, 1100L, 300L))
    check("gapOk: pas batas", CommandProtocol.gapOk(1000L, 1300L, 300L))
    check("gapOk: cukup lama", CommandProtocol.gapOk(1000L, 5000L, 300L))

    println(if (fails == 0) "LULUS: $runs kasus" else "$fails dari $runs kasus GAGAL")
    if (fails != 0) System.exit(1)
}
