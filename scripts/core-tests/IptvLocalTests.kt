import com.molina.core.common.IptvLoad
import com.molina.core.common.IptvLocal
import java.io.File
import java.nio.file.Files

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}
private fun ok(r: IptvLoad) = r as? IptvLoad.Ok
private fun fail(r: IptvLoad) = (r as? IptvLoad.Fail)?.message

fun main() {
    val base = Files.createTempDirectory("iptvlocal").toFile().canonicalFile
    val dir = File(base, "Molina/Media/IPTV").also { it.mkdirs() }
    val outside = File(base, "lain.m3u").also { it.writeText("#EXTM3U\n#EXTINF:-1,X\nhttps://example.com/x.m3u8\n") }

    val good = File(dir, "Daftar TV.m3u")
    good.writeText(
        "\uFEFF#EXTM3U\n" +
            "#EXTINF:-1 group-title=\"Berita\",Kanal Satu\nhttps://stream.example.com/satu.m3u8\n" +
            "#EXTINF:-1 group-title=\"Film\",Kanal Dua\nhttp://stream.example.com/dua.ts\n" +
            "#EXTINF:-1,Lokal\n/sdcard/video.mp4\n"
    )
    File(dir, "b.M3U8").writeText("#EXTM3U\n#EXTINF:-1,B\nhttps://b.example.com/b\n")
    File(dir, "catatan.txt").writeText("bukan playlist")
    File(dir, "sub").mkdirs()
    File(dir, "sub/dalam.m3u").writeText("#EXTM3U\n#EXTINF:-1,D\nhttps://d.example.com/d\n")

    // list
    val l = IptvLocal.list(dir)
    check("list: hanya .m3u/.m3u8 (huruf besar ikut), tanpa subfolder dan .txt", l != null && l.map { it.file.name } == listOf("b.M3U8", "Daftar TV.m3u"))
    check("list: ukuran terisi", l != null && l.all { it.sizeBytes > 0 })
    check("list: folder tidak ada -> null", IptvLocal.list(File(base, "tidak-ada")) == null)
    check("list: jalur berkas biasa -> null", IptvLocal.list(good) == null)
    val many = File(base, "banyak").also { it.mkdirs() }
    repeat(IptvLocal.MAX_FILES + 20) { File(many, "p%03d.m3u".format(it)).writeText("#EXTM3U\n") }
    check("list: dibatasi MAX_FILES", IptvLocal.list(many)!!.size == IptvLocal.MAX_FILES)

    // load normal
    val r = ok(IptvLocal.load(dir, good))
    check("load: BOM diterima, 2 saluran stream", r != null && r.result.channels.size == 2)
    check("load: nama dan grup terbaca", r != null && r.result.channels[0].name == "Kanal Satu" && r.result.channels[0].group == "Berita")
    check("load: entri jalur lokal dilewati kebijakan (skipped=1)", r != null && r.result.skipped == 1)
    check("load: ekstensi huruf besar .M3U8 boleh", ok(IptvLocal.load(dir, File(dir, "b.M3U8"))) != null)

    // keamanan jalur
    check("load: berkas di luar folder ditolak", fail(IptvLocal.load(dir, outside)) != null)
    check("load: traversal '..' ditolak", fail(IptvLocal.load(dir, File(dir, "../../../lain.m3u"))) != null)
    check("load: subfolder ditolak (hanya anak langsung)", fail(IptvLocal.load(dir, File(dir, "sub/dalam.m3u"))) != null)
    val link = File(dir, "tautan.m3u")
    val linked = try { Files.createSymbolicLink(link.toPath(), outside.toPath()); true } catch (e: Exception) { false }
    if (linked) check("load: symlink keluar folder ditolak", fail(IptvLocal.load(dir, link)) != null)
    check("load: ekstensi salah ditolak", fail(IptvLocal.load(dir, File(dir, "catatan.txt"))) != null)
    check("load: berkas tidak ada -> pesan", fail(IptvLocal.load(dir, File(dir, "hilang.m3u")))?.contains("tidak ditemukan") == true)
    check("load: folder sebagai berkas ditolak", fail(IptvLocal.load(dir, File(dir, "sub"))) != null)

    // batas dan isi
    val big = File(dir, "besar.m3u")
    big.outputStream().use { o -> val line = "#EXTINF:-1,N\nhttps://a.example.com/x\n".toByteArray(); repeat(5 * 1024 * 1024 / line.size + 10) { o.write(line) } }
    check("load: > 5 MB ditolak", fail(IptvLocal.load(dir, big))?.contains("terlalu besar") == true)
    File(dir, "kosong.m3u").writeText("#EXTM3U\n")
    check("load: playlist kosong -> pesan", fail(IptvLocal.load(dir, File(dir, "kosong.m3u"))) != null)
    File(dir, "hls.m3u8").writeText("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg1.ts\n")
    check("load: playlist HLS satu stream -> pesan", fail(IptvLocal.load(dir, File(dir, "hls.m3u8")))?.contains("HLS") == true)
    check("isLocalRef", IptvLocal.isLocalRef("/sdcard/Molina/Media/IPTV/a.m3u") && !IptvLocal.isLocalRef("https://a.b/c.m3u"))

    base.deleteRecursively()
    println(if (fails == 0) "\nSEMUA LOLOS" else "\n$fails GAGAL")
    if (fails > 0) System.exit(1)
}
