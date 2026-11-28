import com.molina.cinema.ResLabel

// Tes JVM murni untuk ResLabel (label, aksen tier, fps, codec, dan teks ketuk indikator resolusi realtime).

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}
private fun eq(w: Int, h: Int, want: String) = check("${w}x$h -> $want", ResLabel.of(w, h) == want)

fun main() {
    eq(1920, 1080, "1080p")
    eq(1280, 720, "720p")
    eq(3840, 2160, "4K")
    eq(4096, 2160, "4K")
    eq(2560, 1440, "1440p")
    eq(7680, 4320, "8K")
    eq(854, 480, "480p")
    eq(640, 360, "360p")
    eq(960, 540, "540p")
    eq(256, 144, "144p")
    // sinema / 4:3 / portrait / terpotong
    eq(1920, 800, "1080p")
    eq(1280, 544, "720p")
    eq(1440, 1080, "1080p")
    eq(1080, 1920, "1080p")
    eq(720, 1280, "720p")
    eq(1916, 1076, "1080p")
    // belum diketahui
    eq(0, 0, "")
    eq(1920, 0, "")
    eq(-1, 1080, "")

    // tier (aksen warna)
    check("tier 4K emas", ResLabel.tier(3840, 2160) == 2)
    check("tier 8K emas", ResLabel.tier(7680, 4320) == 2)
    check("tier 1440p putih", ResLabel.tier(2560, 1440) == 1)
    check("tier 1080p putih", ResLabel.tier(1920, 1080) == 1)
    check("tier 720p redup", ResLabel.tier(1280, 720) == 0)
    check("tier 360p redup", ResLabel.tier(640, 360) == 0)
    check("tier tak diketahui redup", ResLabel.tier(0, 0) == 0)

    // fps: hanya laju tinggi
    check("fps 23.976 kosong", ResLabel.fpsSuffix(23.976) == "")
    check("fps 25 kosong", ResLabel.fpsSuffix(25.0) == "")
    check("fps 29.97 kosong", ResLabel.fpsSuffix(29.97) == "")
    check("fps 30 kosong", ResLabel.fpsSuffix(30.0) == "")
    check("fps 50", ResLabel.fpsSuffix(50.0) == "50")
    check("fps 59.94 -> 60", ResLabel.fpsSuffix(59.94) == "60")
    check("fps 120", ResLabel.fpsSuffix(120.0) == "120")
    check("fps NaN kosong", ResLabel.fpsSuffix(Double.NaN) == "")
    check("fps 0 kosong", ResLabel.fpsSuffix(0.0) == "")
    check("fps tak masuk akal kosong", ResLabel.fpsSuffix(1000.0) == "")

    // codec
    check("codec h264", ResLabel.codecName("h264") == "H.264")
    check("codec hevc", ResLabel.codecName("hevc") == "HEVC")
    check("codec h265", ResLabel.codecName("h265") == "HEVC")
    check("codec av1", ResLabel.codecName("av1") == "AV1")
    check("codec vp9 besar-kecil", ResLabel.codecName(" VP9 ") == "VP9")
    check("codec mpeg2video", ResLabel.codecName("mpeg2video") == "MPEG-2")
    check("codec tak dikenal huruf besar", ResLabel.codecName("prores") == "PRORES")
    check("codec null kosong", ResLabel.codecName(null) == "")
    check("codec kosong", ResLabel.codecName("  ") == "")

    // teks ketuk
    val dot = " \u00B7 "
    val x = "\u00D7"
    check("detail lengkap", ResLabel.detail(1920, 1080, 59.94, "hevc") == "1080p60" + dot + "HEVC" + dot + "1920" + x + "1080")
    check("detail tanpa fps tinggi", ResLabel.detail(1280, 720, 25.0, "h264") == "720p" + dot + "H.264" + dot + "1280" + x + "720")
    check("detail tanpa codec", ResLabel.detail(3840, 2160, 0.0, null) == "4K" + dot + "3840" + x + "2160")
    check("detail ukuran tak diketahui kosong", ResLabel.detail(0, 0, 30.0, "h264") == "")

    // [RES-HDR] lencana HDR
    check("hdr pq", ResLabel.hdrName("pq", "bt.2020-ncl") == "HDR")
    check("hdr hlg", ResLabel.hdrName("hlg", "bt.2020-ncl") == "HLG")
    check("hdr dolby vision", ResLabel.hdrName("pq", "dolbyvision") == "DV")
    check("hdr sdr bt.1886", ResLabel.hdrName("bt.1886", "bt.709") == "")
    check("hdr bt.2020 tanpa pq/hlg bukan HDR", ResLabel.hdrName("bt.1886", "bt.2020-ncl") == "")
    check("hdr besar-kecil dan spasi", ResLabel.hdrName(" PQ ", null) == "HDR")
    check("hdr null kosong", ResLabel.hdrName(null, null) == "")
    check("detail dengan hdr", ResLabel.detail(3840, 2160, 23.976, "hevc", "HDR") == "4K" + dot + "HEVC" + dot + "HDR" + dot + "3840" + x + "2160")
    check("detail hdr tanpa codec", ResLabel.detail(3840, 2160, 0.0, null, "HLG") == "4K" + dot + "HLG" + dot + "3840" + x + "2160")

    if (fails > 0) { println("TOTAL GAGAL: $fails"); kotlin.system.exitProcess(1) }
    println("semua lulus")
}
