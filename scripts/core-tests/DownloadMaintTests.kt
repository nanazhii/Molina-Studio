import com.molina.core.common.*

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun doctor(
    yt: String? = "2025.10.22", method: String? = "pipx", ff: String? = "7.1", probe: Boolean = true,
    mutagen: Boolean = true, free: Long? = 2048, issues: List<String> = emptyList(),
) = DownloadEvent.Doctor(yt, method, ff, probe, mutagen, "3.12.0", "/sdcard/Movies/Molina", "/sdcard/Music/Molina", true, free, issues)

fun main() {
    check("args: doctor", DownloadMaintenance.args(MaintenanceKind.DOCTOR) == listOf("--json", "--doctor"))
    check("args: update", DownloadMaintenance.args(MaintenanceKind.UPDATE) == listOf("--json", "--update-ytdlp"))
    check("args: tanpa URL / tanpa '--'", MaintenanceKind.values().all { k -> DownloadMaintenance.args(k).none { it.startsWith("http") || it == "--" } })

    val ok = DownloadMaintenance.doctorLines(doctor())
    check("doctor: sehat semua ok", ok.all { it.ok })
    check("doctor: yt-dlp memuat versi dan metode", ok.first { it.label == "yt-dlp" }.value == "2025.10.22 (pipx)")
    check("doctor: ruang kosong diformat", ok.first { it.label == "Ruang kosong" }.value == "2.00 GB")
    check("doctor: nada GOOD bila tanpa isu", DownloadMaintenance.doctorTone(doctor()) == MaintenanceTone.GOOD)

    val bad = DownloadMaintenance.doctorLines(doctor(yt = null, method = null, ff = null, probe = false, free = 100, issues = listOf("no_ytdlp", "no_ffmpeg", "no_ffprobe", "low_space")))
    check("doctor: yt-dlp hilang = tidak ok", bad.first { it.label == "yt-dlp" }.let { !it.ok && it.value == "tidak terpasang" })
    check("doctor: ffmpeg/ffprobe hilang = tidak ok", bad.filter { it.label == "ffmpeg" || it.label == "ffprobe" }.none { it.ok })
    check("doctor: low_space menandai ruang kosong", !bad.first { it.label == "Ruang kosong" }.ok)
    check("doctor: nada WARN bila ada isu wajib", DownloadMaintenance.doctorTone(doctor(issues = listOf("no_ffmpeg"))) == MaintenanceTone.WARN)
    check("doctor: no_mutagen saja tetap GOOD", DownloadMaintenance.doctorTone(doctor(mutagen = false, issues = listOf("no_mutagen"))) == MaintenanceTone.GOOD)
    check("doctor: mutagen opsional tidak dianggap rusak", DownloadMaintenance.doctorLines(doctor(mutagen = false)).first { it.label.startsWith("Sampul") }.ok)
    check("doctor: freeMb negatif tidak meledak", DownloadMaintenance.doctorLines(doctor(free = -5)).first { it.label == "Ruang kosong" }.value == "0 B")
    check("doctor: tanpa python/folder/ruang tidak membuat baris", DownloadMaintenance.doctorLines(
        DownloadEvent.Doctor(null, null, null, false, false, null, null, null, false, null, emptyList())).size == 4)

    check("hint: kode dikenal memuat perintah apt", DownloadMaintenance.issueHint("no_ffmpeg").contains("apt install -y ffmpeg"))
    check("hint: no_ca menyebut Starter", DownloadMaintenance.issueHint("no_ca").contains("Starter"))
    check("doctor: no_ca menambah baris tidak ok dan nada WARN", DownloadMaintenance.doctorLines(doctor(issues = listOf("no_ca"))).first { it.label == "Sertifikat HTTPS" }.let { !it.ok } &&
        DownloadMaintenance.doctorTone(doctor(issues = listOf("no_ca"))) == MaintenanceTone.WARN)
    check("doctor: tanpa no_ca tidak ada baris sertifikat", DownloadMaintenance.doctorLines(doctor()).none { it.label == "Sertifikat HTTPS" })
    check("hint: kode tak dikenal tetap tampil", DownloadMaintenance.issueHint("aneh_x") == "Catatan: aneh_x")
    check("hint: duplikat dibuang", DownloadMaintenance.issueHints(doctor(issues = listOf("low_space", "low_space"))).size == 1)

    fun up(s: String, m: String? = "pip", b: String? = "2025.01.01", a: String? = "2025.10.22", msg: String? = null) = DownloadEvent.Update(s, m, b, a, msg)
    check("update: ok", DownloadMaintenance.updateText(up("ok")) == "yt-dlp diperbarui: 2025.01.01 → 2025.10.22")
    check("update: start menyebut metode", DownloadMaintenance.updateText(up("start", a = null)).startsWith("Memperbarui yt-dlp lewat pip"))
    check("update: latest tanpa msg", DownloadMaintenance.updateText(up("latest")).contains("sudah terbaru"))
    check("update: failed dengan pesan", DownloadMaintenance.updateText(up("failed", msg = "jaringan putus")) == "Pembaruan gagal: jaringan putus")
    check("update: failed tanpa pesan", DownloadMaintenance.updateText(up("failed")) == "Pembaruan gagal.")
    check("update: status asing aman", DownloadMaintenance.updateText(up("???")) == "Status pembaruan tidak dikenal.")
    check("update: nada", listOf("ok", "latest", "failed", "start", "x").map { DownloadMaintenance.updateTone(up(it)) } ==
        listOf(MaintenanceTone.GOOD, MaintenanceTone.GOOD, MaintenanceTone.BAD, MaintenanceTone.BUSY, MaintenanceTone.WARN))
    check("update: pesan panjang dipangkas", DownloadMaintenance.updateText(up("failed", msg = "x".repeat(500))).length <= 240)

    // Alur nyata: baris JSON skrip -> parser -> formatter.
    val dl = DownloadEventParser.parse("""{"v":1,"ev":"doctor","ytdlp":"2025.10.22","ytdlp_method":"apt","ffmpeg":"7.1","ffprobe":true,"mutagen":false,"python":"3.12.0","video_dir":"/sdcard/Movies/Molina","audio_dir":"/sdcard/Music/Molina","root_bound":true,"free_mb":900,"issues":["no_mutagen"]}""")
    check("e2e: doctor dari JSON", dl is DownloadEvent.Doctor && DownloadMaintenance.doctorTone(dl) == MaintenanceTone.GOOD &&
        DownloadMaintenance.doctorLines(dl).first().value == "2025.10.22 (apt)")
    val ul = DownloadEventParser.parse("""{"v":1,"ev":"update","status":"failed","method":"pip","before":"2025.01.01","after":"2025.01.01","msg":"Tidak ada jaringan"}""")
    check("e2e: update dari JSON", ul is DownloadEvent.Update && DownloadMaintenance.updateText(ul) == "Pembaruan gagal: Tidak ada jaringan")

    // Reducer: event perawatan tidak menyentuh daftar item dan sesi selesai lewat settle.
    var feed = DownloadFeed()
    feed.push("""{"v":1,"ev":"hello","version":1}""" + "\n", false)
    feed.push("""{"v":1,"ev":"update","status":"start","method":"pip","before":"1","after":null,"msg":null}""" + "\n", false)
    feed.push("""{"v":1,"ev":"update","status":"ok","method":"pip","before":"1","after":"2","msg":null}""" + "\n", false)
    feed.finish(false, 0, null)
    val st = feed.state
    check("feed: update terakhir dipertahankan", st.session.update?.status == "ok" && st.session.items.isEmpty())
    check("feed: selesai tanpa galat fatal", !st.running && st.session.finished && st.session.fatalError == null)
    feed = DownloadFeed()
    feed.finish(false, 2, null)
    check("feed: proses mati tanpa hasil = galat fatal", feed.state.session.fatalError != null && feed.state.session.update == null)
    check("summary: berjalan", DownloadMaintenance.summaryText(MaintenanceKind.DOCTOR, true, false, false) == "Memeriksa lingkungan…")
    check("summary: gagal", DownloadMaintenance.summaryText(MaintenanceKind.UPDATE, false, true, false).endsWith("gagal dijalankan"))
    check("summary: selesai", DownloadMaintenance.summaryText(MaintenanceKind.DOCTOR, false, false, true) == "Periksa lingkungan selesai")

    if (fails > 0) { println("$fails pemeriksaan GAGAL"); System.exit(1) }
    println("semua uji lulus")
}
