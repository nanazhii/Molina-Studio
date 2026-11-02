import com.molina.core.common.*

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private val U = listOf("https://a.test/1")

private fun req(c: DownloadChoices) = DownloadRequest(
    urls = U, mode = if (c.video) DownloadMode.VIDEO else DownloadMode.AUDIO, height = c.height, videoExt = c.videoExt,
    audioFormat = c.audioFmt, bitrate = c.bitrate, subs = c.subs, subsMode = c.subsMode, cover = c.cover,
    limitRate = c.limitRate.ifEmpty { null }, sidecar = c.sidecar,
)

fun main() {
    val base = DownloadChoices()
    for (p in DownloadPreset.values()) {
        val n = p.apply(base)
        check("${p.label}: hasil apply cocok dengan matches", p.matches(n))
        check("${p.label}: hasil apply lolos validasi perintah", DownloadCommand.validate(req(n)) == null)
        check("${p.label}: pilihan sudah sanitize-stabil", DownloadChoices.sanitize(n.video, n.height, n.videoExt, n.audioFmt, n.bitrate, n.subs, n.subsMode, n.cover, n.limitRate, n.sidecar) == n)
        check("${p.label}: idempoten", p.apply(n) == n)
        check("${p.label}: hanya satu preset aktif", DownloadPreset.values().count { it.matches(n) } == 1 && DownloadPreset.active(n) == p)
    }

    val c = DownloadPreset.CINEMA.apply(base)
    check("Cinema: 1080p MP4 + subtitle id,en embed", c.video && c.height == 1080 && c.subs == listOf("id", "en") && c.subsMode == "embed")
    val args = DownloadCommand.args(req(c))
    check("Cinema: argumen memuat --subs id,en --subs-mode embed", args.containsAll(listOf("--subs", "id,en", "--subs-mode", "embed")))
    val h = DownloadPreset.HEMAT.apply(c)
    check("Hemat: menghapus subtitle dari Cinema", h.subs.isEmpty() && h.height == 480)
    val m = DownloadPreset.MUSIK.apply(base.copy(cover = false))
    check("Musik: audio MP3 320k dan sampul dinyalakan", !m.video && m.audioFmt == "mp3" && m.bitrate == "320k" && m.cover)
    check("Musik: argumen audio tanpa --no-cover", !DownloadCommand.args(req(m)).contains("--no-cover"))

    // batas kecepatan dan opsi lain milik pengguna tidak disentuh
    val mine = DownloadChoices(limitRate = "2M", videoExt = "mkv", subsMode = "file", audioFmt = "flac")
    for (p in DownloadPreset.values()) check("${p.label}: batas kecepatan tidak berubah", p.apply(mine).limitRate == "2M")
    check("Musik: format video pilihan pengguna tidak disentuh", DownloadPreset.MUSIK.apply(mine).videoExt == "mkv")
    check("Hemat: format audio pilihan pengguna tidak disentuh", DownloadPreset.HEMAT.apply(mine).audioFmt == "flac")

    // chip tidak terpilih setelah pengguna menyimpang
    check("Cinema tidak aktif bila subtitle diubah", !DownloadPreset.CINEMA.matches(c.copy(subs = listOf("id"))))
    check("Cinema tidak aktif bila MKV", !DownloadPreset.CINEMA.matches(c.copy(videoExt = "mkv")))
    check("Cinema tidak aktif bila subtitle mode file", !DownloadPreset.CINEMA.matches(c.copy(subsMode = "file")))
    check("Hemat tidak aktif bila ada subtitle", !DownloadPreset.HEMAT.matches(h.copy(subs = listOf("en"))))
    check("Musik tidak aktif bila sampul dimatikan", !DownloadPreset.MUSIK.matches(m.copy(cover = false)))
    check("Musik tidak aktif bila 192k", !DownloadPreset.MUSIK.matches(m.copy(bitrate = "192k")))
    check("bawaan: tidak ada preset aktif", DownloadPreset.active(base) == null)
    check("batas kecepatan tidak memengaruhi status chip", DownloadPreset.CINEMA.matches(c.copy(limitRate = "5M")))
    check("hint memuat semua nama preset", DownloadPreset.values().all { DownloadPreset.HINT.contains(it.label) })

    // poster + info (sidecar)
    check("Cinema menyalakan poster + info", DownloadPreset.CINEMA.apply(base).sidecar)
    check("Hemat mematikan poster + info", !DownloadPreset.HEMAT.apply(c).sidecar)
    check("Musik tidak menyentuh poster + info", DownloadPreset.MUSIK.apply(base.copy(sidecar = true)).sidecar && !DownloadPreset.MUSIK.apply(base).sidecar)
    check("Cinema tidak aktif bila poster dimatikan", !DownloadPreset.CINEMA.matches(c.copy(sidecar = false)))
    check("Hemat tidak aktif bila poster dinyalakan", !DownloadPreset.HEMAT.matches(h.copy(sidecar = true)))
    check("Musik tetap aktif apa pun status poster", DownloadPreset.MUSIK.matches(m.copy(sidecar = true)))
    check("Cinema: argumen memuat --sidecar", DownloadCommand.args(req(c)).contains("--sidecar"))
    check("Hemat: argumen tanpa --sidecar", !DownloadCommand.args(req(h)).contains("--sidecar"))
    check("Musik: argumen audio tanpa --sidecar", !DownloadCommand.args(req(m.copy(sidecar = true))).contains("--sidecar"))
    check("hint menyebut poster", DownloadPreset.HINT.contains("poster"))
    check("preset tidak mengubah pilihan folder per kanal", DownloadPreset.values().all { p -> p.apply(DownloadChoices(perChannel = true)).perChannel && !p.apply(DownloadChoices()).perChannel })

    if (fails > 0) { println("$fails pemeriksaan GAGAL"); System.exit(1) }
    println("semua uji lulus")
}
