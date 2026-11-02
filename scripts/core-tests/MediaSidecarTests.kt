import com.molina.core.common.*
import java.io.File
import java.nio.file.Files

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun put(dir: File, name: String, text: String = "x"): File = File(dir, name).also { it.writeText(text) }

fun main() {
    val d = Files.createTempDirectory("sidecar").toFile()
    try {
        // ---- find
        val v = put(d, "Film [abc].mp4")
        var f = MediaSidecar.find(v)
        check("find: tanpa pendamping -> null/null", f.poster == null && f.info == null)
        put(d, "Film [abc].webp")
        put(d, "Film [abc].info.json", "{}")
        f = MediaSidecar.find(v)
        check("find: webp dan info ditemukan", f.poster?.name == "Film [abc].webp" && f.info?.name == "Film [abc].info.json")
        put(d, "Film [abc].jpg")
        check("find: jpg didahulukan dari webp", MediaSidecar.find(v).poster?.name == "Film [abc].jpg")
        put(d, "Lain [zzz].jpg")
        check("find: poster video lain tidak ikut", MediaSidecar.find(put(d, "Lain2 [q].mp4")).poster == null)
        val dotted = put(d, "Mr. Film [id].mp4")
        put(d, "Mr. Film [id].png")
        check("find: judul bertitik memakai nama dasar yang benar", MediaSidecar.find(dotted).poster?.name == "Mr. Film [id].png")
        check("find: poster tidak dikira info", MediaSidecar.find(dotted).info == null)

        // ---- readInfo
        fun info(json: String) = MediaSidecar.readInfo(put(d, "t.info.json", json))
        val full = info(
            """{"title":"Pelajaran Matematika – Bab 1","channel":"Kanal Belajar","duration":3725,"upload_date":"20240131",""" +
                """"description":"Baris satu\n\nBaris   dua","webpage_url":"https://example.test/watch?v=abc","_molina_slim":1}"""
        )
        check("readInfo: judul unicode terbaca utuh", full?.title == "Pelajaran Matematika – Bab 1")
        check("readInfo: kanal, durasi, tahun", full?.channel == "Kanal Belajar" && full.durationSec == 3725 && full.year == 2024)
        check("readInfo: deskripsi dirapikan satu baris", full?.description == "Baris satu Baris dua")
        check("readInfo: URL https diterima", full?.webUrl == "https://example.test/watch?v=abc")

        val fb = info("""{"fulltitle":"Judul Penuh","uploader":"Pengunggah","release_year":1999,"upload_date":"20240131"}""")
        check("readInfo: cadangan fulltitle/uploader", fb?.title == "Judul Penuh" && fb.channel == "Pengunggah")
        check("readInfo: release_year lebih utama dari upload_date", fb?.year == 1999)
        check("readInfo: tahun mustahil diabaikan", info("""{"upload_date":"00000101"}""")?.year == null)
        check("readInfo: upload_date bukan angka diabaikan", info("""{"upload_date":"abcd0101"}""")?.year == null)
        check("readInfo: durasi pecahan dipotong", info("""{"duration":61.5}""")?.durationSec == 61)
        check("readInfo: durasi nol/negatif -> null", info("""{"duration":0}""")?.durationSec == null && info("""{"duration":-5}""")?.durationSec == null)
        check("readInfo: URL bukan http(s) ditolak", info("""{"webpage_url":"javascript:alert(1)"}""")?.webUrl == null)
        check("readInfo: kolom kosong -> null", info("""{"title":"   ","channel":""}""")?.title == null)

        val long = "kata ".repeat(1000)
        val clipped = info("""{"description":"$long"}""")?.description
        check("readInfo: deskripsi dipotong maks 500 + elipsis", clipped != null && clipped.length <= 500 && clipped.endsWith("\u2026"))

        check("readInfo: JSON rusak -> null", info("{rusak") == null)
        check("readInfo: akar bukan objek -> null", info("[1,2,3]") == null)
        check("readInfo: berkas tidak ada -> null", MediaSidecar.readInfo(File(d, "tidak-ada.info.json")) == null)
        check("readInfo: argumen null -> null", MediaSidecar.readInfo(null) == null)
        val big = File(d, "besar.info.json")
        big.writeText("""{"title":"x","pad":"""" + "a".repeat((MediaSidecar.MAX_INFO_BYTES + 10).toInt()) + """"}""")
        check("readInfo: berkas > batas ditolak tanpa dibaca", MediaSidecar.readInfo(big) == null)
        check("readInfo: direktori -> null", MediaSidecar.readInfo(d) == null)

        // ---- event done membawa sidecars
        val withSide = """{"v":1,"ev":"done","i":1,"dir":"/o","existed":false,"files":[{"name":"a.mp4","path":"/o/a.mp4","size":10}],""" +
            """"sidecars":[{"name":"a.jpg","path":"/o/a.jpg","size":3},{"name":"a.info.json","path":"/o/a.info.json","size":2},{"name":"tanpa-path"}]}"""
        val e1 = DownloadEventParser.parse(withSide) as DownloadEvent.Done
        check("event: files tetap terbaca", e1.files.map { it.name } == listOf("a.mp4"))
        check("event: sidecars terbaca, entri tanpa path dibuang", e1.sidecars.map { it.name } == listOf("a.jpg", "a.info.json") && e1.sidecars[0].sizeBytes == 3L)
        val e2 = DownloadEventParser.parse("""{"v":1,"ev":"done","i":1,"dir":"/o","existed":true,"files":[]}""") as DownloadEvent.Done
        check("event: tanpa kunci sidecars -> daftar kosong (kompatibel mundur)", e2.sidecars.isEmpty())
        val e3 = DownloadEventParser.parse("""{"v":1,"ev":"done","i":1,"dir":"/o","existed":true,"files":[],"sidecars":"rusak"}""") as DownloadEvent.Done
        check("event: sidecars bertipe salah -> kosong, tidak melempar", e3.sidecars.isEmpty())

        // ---- argumen perintah
        val urls = listOf("https://a.test/1")
        fun args(r: DownloadRequest) = DownloadCommand.args(r)
        check("args: bawaan tanpa --sidecar", !args(DownloadRequest(urls = urls)).contains("--sidecar"))
        val on = args(DownloadRequest(urls = urls, sidecar = true))
        check("args: video + sidecar memuat --sidecar sebelum --", on.contains("--sidecar") && on.indexOf("--sidecar") < on.indexOf("--"))
        check("args: audio mengabaikan sidecar", !args(DownloadRequest(urls = urls, mode = DownloadMode.AUDIO, sidecar = true)).contains("--sidecar"))
        check("args: sidecar lolos validasi", DownloadCommand.validate(DownloadRequest(urls = urls, sidecar = true)) == null)

        // ---- ThumbMath.sampleSize
        check("sampleSize: 1280x720 -> 160x90 = 8", ThumbMath.sampleSize(1280, 720, 160, 90) == 8)
        check("sampleSize: 640x360 -> 4", ThumbMath.sampleSize(640, 360, 160, 90) == 4)
        check("sampleSize: gambar sudah kecil -> 1", ThumbMath.sampleSize(160, 90, 160, 90) == 1 && ThumbMath.sampleSize(100, 50, 160, 90) == 1)
        check("sampleSize: sisi terpendek menentukan (4000x300 -> 2)", ThumbMath.sampleSize(4000, 300, 160, 90) == 2)
        val sizes = listOf(1280 to 720, 4000 to 3000, 1920 to 1080, 333 to 777, 90 to 5000)
        check("sampleSize: hasil dekode tidak pernah di bawah target", sizes.all { (w, h) -> val s = ThumbMath.sampleSize(w, h, 160, 90); s == 1 || (w / s >= 160 && h / s >= 90) })
        check("sampleSize: selalu pangkat dua", sizes.all { (w, h) -> val s = ThumbMath.sampleSize(w, h, 160, 90); s > 0 && (s and (s - 1)) == 0 })
        check("sampleSize: masukan tidak sah -> 1", ThumbMath.sampleSize(0, 100, 160, 90) == 1 && ThumbMath.sampleSize(100, -1, 160, 90) == 1 && ThumbMath.sampleSize(100, 100, 0, 90) == 1)
        check("sampleSize: dibatasi 4096", ThumbMath.sampleSize(Int.MAX_VALUE, Int.MAX_VALUE, 1, 1) == 4096)
    } finally {
        d.deleteRecursively()
    }
    if (fails > 0) { println("$fails uji GAGAL"); System.exit(1) }
    println("semua uji lulus")
}
