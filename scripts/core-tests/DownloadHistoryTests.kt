import com.molina.core.common.*

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun item(i: Int, url: String, st: DownloadItemStatus, title: String? = null, err: String? = null, files: List<DownloadedFile> = emptyList()) =
    DownloadItemState(i, url = url, title = title, status = st, errorMsg = err, files = files)

fun main() {
    val ok1 = "https://a.test/v?id=1&token=SECRET"
    val bad = "https://b.test/watch?v=2"
    val never = "https://c.test/3"
    val req = DownloadRequest(listOf(ok1, bad, never), mode = DownloadMode.AUDIO, audioFormat = "flac", bitrate = "320k")
    val session = DownloadSessionState(
        items = listOf(
            item(1, ok1, DownloadItemStatus.DONE, "Satu", files = listOf(DownloadedFile("satu.flac", "/sdcard/x/satu.flac", 10))),
            item(2, bad, DownloadItemStatus.FAILED, "Dua", err = "Gagal di https://b.test/watch?v=2&token=XYZ"),
        ),
        finished = true, ok = 1, failed = 1, cancelled = true,
    )
    val e = DownloadHistory.build(1_000L, req, session)!!

    check("build: satu item per URL permintaan (termasuk yang tak sempat mulai)", e.items.size == 3)
    check("build: hitung ok/gagal", e.okCount == 1 && e.failedCount == 2)
    check("build: item berhasil -> URL disamarkan (tanpa query/token)", e.items[0].url == "https://a.test/..." && "SECRET" !in e.items[0].url)
    check("build: item gagal menyimpan URL penuh untuk Ulangi", e.items[1].url == bad)
    check("build: item yang tak sempat mulai tetap bisa diulang", e.items[2].url == never && !e.items[2].ok)
    check("build: pesan galat disamarkan", e.items[1].error != null && "XYZ" !in e.items[1].error!!)
    check("build: nama berkas disimpan, path tidak", e.items[0].files == listOf("satu.flac"))
    check("build: pilihan dan status batal", e.choices == DownloadChoices(false, 0, "mp4", "flac", "320k") && e.cancelled)
    check("retryUrls: hanya yang belum berhasil, berurutan", e.retryUrls() == listOf(bad, never))
    val rr = e.retryRequest()
    check("retryRequest: mode/kualitas asli dan lolos validasi", rr.mode == DownloadMode.AUDIO && rr.audioFormat == "flac" && rr.bitrate == "320k" && DownloadCommand.validate(rr) == null)
    check("modeLabel: audio", e.modeLabel() == "Audio FLAC 320k")
    val v = DownloadHistory.build(2L, DownloadRequest(listOf(ok1), height = 1080, videoExt = "mkv"), DownloadSessionState())!!
    check("modeLabel: video 1080p dan Terbaik", v.modeLabel() == "Video 1080p MKV" && DownloadHistoryEntry(1, DownloadChoices(), false, null, v.items).modeLabel() == "Video Terbaik MP4")

    val fatal = DownloadHistory.build(3L, DownloadRequest(listOf(ok1, bad)), DownloadSessionState(fatalError = "yt-dlp tidak terpasang"))!!
    check("build: galat global -> semua URL gagal dan bisa diulang", fatal.fatal == "yt-dlp tidak terpasang" && fatal.items.size == 2 && fatal.retryUrls() == listOf(ok1, bad) && fatal.items[0].error == "yt-dlp tidak terpasang")
    check("build: tanpa URL -> null", DownloadHistory.build(4L, DownloadRequest(listOf("  ", "")), DownloadSessionState()) == null)
    check("build: duplikat dibuang seperti perintah asli", DownloadHistory.build(5L, DownloadRequest(listOf(ok1, ok1)), DownloadSessionState())!!.items.size == 1)

    // push
    var list = emptyList<DownloadHistoryEntry>()
    for (t in 1L..25L) list = DownloadHistory.push(list, DownloadHistoryEntry(t, DownloadChoices(), false, null, e.items))
    check("push: dibatasi MAX_ENTRIES, terbaru di depan", list.size == DownloadHistory.MAX_ENTRIES && list.first().at == 25L && list.last().at == 6L)
    check("push: waktu sama mengganti, bukan menggandakan", DownloadHistory.push(list, list[3].copy(cancelled = true)).count { it.at == list[3].at } == 1)

    // encode/decode
    val tricky = e.copy(
        fatal = "baris\nbaru \"kutip\" \\ garis",
        items = e.items.map { it.copy(title = "Judul \"ber-kutip\"\tdan \\ \u0001 ctrl äö 日本") },
    )
    val round = DownloadHistory.decode(DownloadHistory.encode(listOf(tricky, v)))
    check("json: bolak-balik identik (karakter khusus, kontrol, unicode)", round == listOf(tricky, v).sortedByDescending { it.at })
    check("json: kosong -> kosong", DownloadHistory.decode(DownloadHistory.encode(emptyList())).isEmpty())

    // ketahanan
    check("decode: sampah -> kosong", DownloadHistory.decode("bukan json").isEmpty() && DownloadHistory.decode("").isEmpty() && DownloadHistory.decode("[]").isEmpty())
    check("decode: versi tak dikenal -> kosong", DownloadHistory.decode("""{"v":99,"entries":[]}""").isEmpty())
    val partial = """{"v":1,"entries":[
        {"at":5,"items":[{"u":"https://x.test/1","ok":false}]},
        {"at":"rusak","items":[{"u":"https://x.test/2"}]},
        {"at":6,"items":[]},
        {"at":7,"video":"salah","h":123456,"vx":"avi","items":[{"u":"https://x.test/3","ok":false,"f":[1,"a.mp4"]},{"bukan":"item"}]},
        7
    ]}"""
    val p = DownloadHistory.decode(partial)
    check("decode: entri rusak/kosong dilewati, yang sehat tetap ada", p.map { it.at } == listOf(7L, 5L))
    check("decode: pilihan tidak valid jatuh ke bawaan, item rusak dilewati", p[0].choices == DownloadChoices() && p[0].items.size == 1 && p[0].items[0].files == listOf("a.mp4"))
    val huge = DownloadHistory.encode(List(40) { DownloadHistoryEntry(it.toLong(), DownloadChoices(), false, null, e.items) })
    check("encode/decode: tidak melebihi MAX_ENTRIES", DownloadHistory.decode(huge).size == DownloadHistory.MAX_ENTRIES)

    if (fails > 0) { println("$fails uji GAGAL"); kotlin.system.exitProcess(1) }
    println("semua uji lulus")
}
