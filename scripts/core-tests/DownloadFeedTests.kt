import com.molina.core.common.*

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun feedAll(chunks: List<String>, finish: Boolean = true): DownloadRunState {
    val f = DownloadFeed()
    for (c in chunks) f.push(c, false)
    if (finish) f.finish(false, 0, null)
    return f.state
}

// Keluaran nyata molina_dl.py --json (2 URL, yt-dlp palsu gagal).
private val REAL = """{"v":1,"ev":"hello","script":"molina_dl","version":3}
{"v":1,"ev":"queue","total":2}
{"v":1,"ev":"item","i":1,"n":2,"url":"https://example.com/a"}
{"v":1,"ev":"error","i":1,"code":"info_failed","msg":"URL tidak didukung atau koneksi bermasalah."}
{"v":1,"ev":"item","i":2,"n":2,"url":"https://example.com/b"}
{"v":1,"ev":"error","i":2,"code":"info_failed","msg":"URL tidak didukung atau koneksi bermasalah."}
{"v":1,"ev":"summary","ok":0,"failed":2,"cancelled":false}
"""

private val FLOW = listOf(
    """{"v":1,"ev":"hello","script":"molina_dl","version":3}""",
    """{"v":1,"ev":"queue","total":2}""",
    """{"v":1,"ev":"item","i":1,"n":2,"url":"https://x.test/v1"}""",
    """{"v":1,"ev":"info","i":1,"title":"Judul Satu","id":"abc","duration":61,"uploader":"U","thumbnail":"https://x.test/t.jpg","heights":[1080,720]}""",
    """{"v":1,"ev":"progress","i":1,"stream":"video","pct":50.0,"done":500,"total":1000,"speed":100,"eta":5}""",
    """{"v":1,"ev":"progress","i":1,"stream":"audio","pct":100.0,"done":10,"total":10,"speed":null,"eta":null}""",
    """{"v":1,"ev":"stage","i":1,"stage":"merge"}""",
    """{"v":1,"ev":"done","i":1,"dir":"/sdcard/Molina/Media/Video","existed":false,"files":[{"name":"a.mp4","path":"/sdcard/Molina/Media/Video/a.mp4","size":1010}]}""",
    """{"v":1,"ev":"item","i":2,"n":2,"url":"https://x.test/v2"}""",
    """{"v":1,"ev":"progress","i":2,"stream":"single","pct":10.0,"done":1,"total":10,"speed":1,"eta":9}""",
    """{"v":1,"ev":"error","i":2,"code":"download_failed","msg":"gagal"}""",
    """{"v":1,"ev":"summary","ok":1,"failed":1,"cancelled":false}""",
)

fun main() {
    // ---- pemecahan baris
    val whole = feedAll(listOf(REAL))
    check("keluaran nyata: 2 item gagal + summary", whole.session.items.size == 2 &&
        whole.session.items.all { it.status == DownloadItemStatus.FAILED && it.errorCode == "info_failed" } &&
        whole.session.finished && whole.session.failed == 2 && whole.session.ok == 0)
    check("potongan 7 karakter = hasil sama", feedAll(REAL.chunked(7)).session == whole.session)
    check("potongan 1 karakter = hasil sama", feedAll(REAL.chunked(1)).session == whole.session)
    check("CRLF = hasil sama", feedAll(listOf(REAL.replace("\n", "\r\n"))).session == whole.session)

    // ---- alur lengkap
    val full = feedAll(listOf(FLOW.joinToString("\n") + "\n"))
    val i1 = full.session.items[0]; val i2 = full.session.items[1]
    check("item 1 selesai dengan judul dan berkas", i1.status == DownloadItemStatus.DONE && i1.title == "Judul Satu" && i1.files.size == 1 && i1.files[0].sizeBytes == 1010L)
    check("item 2 gagal dengan kode", i2.status == DownloadItemStatus.FAILED && i2.errorCode == "download_failed")
    check("ringkasan 1 ok 1 gagal", full.session.finished && full.session.ok == 1 && full.session.failed == 1)
    check("selesai: running=false, exitCode=0", !full.running && full.exitCode == 0)
    check("persen gabungan item DONE = 100", DownloadReducer.overallPercent(i1) == 100.0)

    // ---- baris tanpa newline di akhir
    val f = DownloadFeed()
    f.push("""{"v":1,"ev":"queue","total":1}""" + "\n", false)
    f.push("""{"v":1,"ev":"summary","ok":0,"failed":0,"cancelled":false}""", false)
    check("sisa baris belum diproses sebelum finish", !f.state.session.finished && f.state.running)
    f.finish(false, 0, null)
    check("finish menguras sisa baris", f.state.session.finished && !f.state.running)

    // ---- stdout/stderr bergantian
    val g = DownloadFeed()
    g.push("""{"v":1,"ev":"que""", false)
    g.push("ERR a\n", true)
    g.push("""ue","total":3}""" + "\n", false)
    check("buffer stdout dan stderr terpisah", g.state.session.items.size == 3 && g.state.stderrTail == listOf("ERR a"))

    // ---- baris yang diabaikan
    val h = DownloadFeed()
    h.push("halo dunia\n", false)
    h.push("""{"v":2,"ev":"summary","ok":1,"failed":0,"cancelled":false}""" + "\n", false)
    h.push("""{"v":1,"ev":"tidak_dikenal"}""" + "\n", false)
    h.push("\n\n   \n", false)
    check("non-JSON, versi lain, event asing, baris kosong diabaikan", !h.state.session.finished && h.state.session.items.isEmpty())

    // ---- baris terlalu panjang
    val k = DownloadFeed()
    k.push("x".repeat(70_000), false)
    k.push("\n" + """{"v":1,"ev":"summary","ok":0,"failed":0,"cancelled":false}""" + "\n", false)
    check("baris >64K dibuang, baris sesudahnya tetap terbaca", k.state.session.finished)

    // ---- stderr: samarkan + batas
    val s = DownloadFeed()
    s.push("ERROR: gagal https://x.test/v?token=SECRET123&a=b\n", true)
    check("stderr disamarkan (URL dan token)", s.state.stderrTail.size == 1 && !s.state.stderrTail[0].contains("SECRET123") && !s.state.stderrTail[0].contains("token=SECRET"))
    val t = DownloadFeed()
    for (n in 1..30) t.push("baris $n\n", true)
    check("stderr maksimal 20 baris, terbaru di akhir", t.state.stderrTail.size == 20 && t.state.stderrTail.last() == "baris 30" && t.state.stderrTail.first() == "baris 11")
    val u = DownloadFeed(); u.push("y".repeat(1000) + "\n", true)
    check("baris stderr dipangkas 300", u.state.stderrTail[0].length == 300)

    // ---- tuntaskan sesi yang menggantung
    val a = DownloadFeed()
    a.push("""{"v":1,"ev":"queue","total":2}""" + "\n" + """{"v":1,"ev":"item","i":1,"n":2,"url":"https://x.test/a"}""" + "\n" +
        """{"v":1,"ev":"progress","i":1,"stream":"single","pct":5.0,"done":1,"total":20,"speed":1,"eta":9}""" + "\n", false)
    a.finish(true, -1, null)
    check("dibatalkan: item berjalan jadi gagal 'cancelled', item antre tetap", a.state.session.items[0].status == DownloadItemStatus.FAILED &&
        a.state.session.items[0].errorCode == "cancelled" && a.state.session.items[1].status == DownloadItemStatus.QUEUED)
    check("dibatalkan: selesai, cancelled, tanpa galat global", a.state.session.finished && a.state.session.cancelled && a.state.session.fatalError == null && !a.state.running)

    val d = DownloadFeed()
    d.push("""{"v":1,"ev":"hello","script":"molina_dl","version":3}""" + "\n", false)
    d.push("Traceback (most recent call last):\nValueError: rusak\n", true)
    d.finish(false, 2, null)
    check("proses mati: galat global memuat kode dan baris stderr terakhir", d.state.session.fatalError == DownloadErrorText.STOPPED && d.state.session.fatalRaw == "proses berhenti (kode 2): ValueError: rusak" && d.state.exitCode == 2)

    val e = DownloadFeed()
    e.finish(false, -1, "Debian belum terpasang.")
    check("gagal mulai: galat global = pesan startError", e.state.session.fatalError == "Debian belum terpasang." && !e.state.running && e.state.session.finished)

    val z = DownloadFeed()
    z.push("""{"v":1,"ev":"summary","ok":3,"failed":0,"cancelled":false}""" + "\n", false)
    z.finish(false, 130, null)
    check("summary dari skrip tidak ditimpa saat finish", z.state.session.ok == 3 && z.state.session.fatalError == null)

    // ---- DownloadCommand
    val req = DownloadRequest(listOf("  https://a.test/1 ", "https://a.test/1", "", "http://b.test/2?x=1&y=2"))
    check("validate normal = null", DownloadCommand.validate(req) == null)
    check("args video bawaan", DownloadCommand.args(req) ==
        listOf("--json", "--mode", "video", "--ext", "mp4", "--", "https://a.test/1", "http://b.test/2?x=1&y=2"))
    check("args video 720 mkv + dir", DownloadCommand.args(req.copy(height = 720, videoExt = "mkv", dir = "/sdcard/Molina/Media/Video")) ==
        listOf("--json", "--mode", "video", "--height", "720", "--ext", "mkv", "--dir", "/sdcard/Molina/Media/Video", "--", "https://a.test/1", "http://b.test/2?x=1&y=2"))
    check("args audio", DownloadCommand.args(req.copy(mode = DownloadMode.AUDIO, audioFormat = "flac", bitrate = "320k")) ==
        listOf("--json", "--mode", "audio", "--audio-format", "flac", "--bitrate", "320k", "--", "https://a.test/1", "http://b.test/2?x=1&y=2"))
    fun bad(r: DownloadRequest) = DownloadCommand.validate(r) != null
    check("tolak daftar kosong", bad(DownloadRequest(emptyList())) && bad(DownloadRequest(listOf("  ", ""))))
    check("tolak skema selain http(s)", bad(DownloadRequest(listOf("file:///etc/passwd"))) && bad(DownloadRequest(listOf("javascript:alert(1)"))) && bad(DownloadRequest(listOf("ftp://x.test/a"))))
    check("tolak URL berawalan -", bad(DownloadRequest(listOf("--exec=sh"))) && bad(DownloadRequest(listOf("-o /x"))))
    check("tolak URL berspasi/kontrol", bad(DownloadRequest(listOf("https://a.test/a b"))) && bad(DownloadRequest(listOf("https://a.test/a\nb"))) && bad(DownloadRequest(listOf("https://a.test/a\u0000"))))
    check("tolak URL >2048", bad(DownloadRequest(listOf("https://a.test/" + "a".repeat(2048)))))
    check("batas 50 URL", !bad(DownloadRequest(List(50) { "https://a.test/$it" })) && bad(DownloadRequest(List(51) { "https://a.test/$it" })))
    check("tolak opsi tak dikenal", bad(req.copy(videoExt = "avi")) && bad(req.copy(audioFormat = "ogg")) && bad(req.copy(bitrate = "64k")) && bad(req.copy(height = -1)) && bad(req.copy(height = 5000)))
    check("tolak folder relatif/kontrol", bad(req.copy(dir = "unduhan")) && bad(req.copy(dir = "/a\u0000b")) && bad(req.copy(dir = "-rf")))
    check("allowLan -> --allow-lan sebelum --", DownloadCommand.args(req.copy(allowLan = true)) ==
        listOf("--json", "--mode", "video", "--ext", "mp4", "--allow-lan", "--", "https://a.test/1", "http://b.test/2?x=1&y=2"))
    check("galat netguard -> teks ramah", DownloadErrorText.friendly("blocked_local", "[MOLINA-NETGUARD] x") == DownloadErrorText.BLOCKED &&
        DownloadErrorText.friendly("info_failed", "ERROR: [MOLINA-NETGUARD] koneksi ditolak") == DownloadErrorText.BLOCKED)
    check("dir: izinkan akar Molina", !bad(req.copy(dir = "/sdcard/Molina")) && !bad(req.copy(dir = "/storage/emulated/0/Molina/Media/Audio/")) && !bad(req.copy(dir = "/sdcard/Molina/Workspace/unduhan")))
    check("dir: folder Molina-Kerja lama ditolak", bad(req.copy(dir = "/sdcard/Molina-Kerja/unduhan")) && bad(req.copy(dir = "/storage/emulated/0/Molina-Kerja")))
    check("dir: tolak di luar Molina", bad(req.copy(dir = "/etc")) && bad(req.copy(dir = "/root/.config")) && bad(req.copy(dir = "/sdcard")) && bad(req.copy(dir = "/sdcard/MolinaX")) && bad(req.copy(dir = "/sdcard/Download")))
    check("dir: tolak traversal", bad(req.copy(dir = "/sdcard/Molina/../Download")) && bad(req.copy(dir = "/sdcard/Molina/./a/..")) && bad(req.copy(dir = "/sdcard/Molina/..")))
    check("pesan galat URL tidak memuat query", DownloadCommand.validate(DownloadRequest(listOf("ftp://x.test/a?token=SECRET")))!!.contains("SECRET").not())

    if (fails > 0) { println("$fails uji GAGAL"); kotlin.system.exitProcess(1) }
    println("semua uji lulus")
}
