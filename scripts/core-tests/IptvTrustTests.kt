// [IPTV-TRUST] uji IptvTrust
import com.molina.core.net.IptvTrust
import com.molina.core.net.IptvTrust.Level
import com.molina.core.net.NetworkPolicy // [IPTV-TRUST-BLOCK]

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

fun main() {
    check("resmi: HTTPS + domain penyiar", IptvTrust.of("https://ott-balancer.tvri.go.id/a.m3u8") == Level.OFFICIAL)
    check("resmi: subdomain rctiplus.id", IptvTrust.of("https://linier-mnctv.rctiplus.id/x.m3u8") == Level.OFFICIAL)
    check("komunitas: HTTPS domain bebas", IptvTrust.of("https://lingkartv.my.id/hls/a.m3u8") == Level.COMMUNITY)
    check("komunitas: domain mirip penyiar (akhiran palsu)", IptvTrust.of("https://detik.com.attacker.io/a.m3u8") == Level.COMMUNITY)
    check("komunitas: domain mirip penyiar (awalan palsu)", IptvTrust.of("https://notdetik.com/a.m3u8") == Level.COMMUNITY)
    check("tidak aman: HTTP walau domain penyiar", IptvTrust.of("http://video.detik.com/a.m3u8") == Level.INSECURE)
    check("tidak aman: IP mentah HTTP", IptvTrust.of("http://122.248.43.242:1935/a/playlist.m3u8") == Level.INSECURE)
    check("tidak aman: IP mentah HTTPS", IptvTrust.of("https://1.2.3.4/a.m3u8") == Level.INSECURE)
    check("tidak aman: IPv6", IptvTrust.of("https://[::1]/a.m3u8") == Level.INSECURE)
    check("tidak aman: token di query", IptvTrust.of("https://x.example/a.m3u8?token=abc") == Level.INSECURE)
    check("tidak aman: kredensial di URL", IptvTrust.of("https://u:p@x.example/a.m3u8") == Level.INSECURE)
    check("tidak aman: URL rusak", IptvTrust.of("ht!tp://%%") == Level.INSECURE)
    // [IPTV-TRUST-BLOCK]
    check("blokir: opsi mati, tidak ada yang diblokir", !IptvTrust.isBlocked("http://1.2.3.4/a.m3u8", block = false))
    check("blokir: opsi nyala, HTTP diblokir", IptvTrust.isBlocked("http://1.2.3.4/a.m3u8", block = true))
    check("blokir: opsi nyala, HTTPS resmi lolos", !IptvTrust.isBlocked("https://ott-balancer.tvri.go.id/a.m3u8", block = true))
    check("blokir: opsi nyala, HTTPS komunitas lolos", !IptvTrust.isBlocked("https://lingkartv.my.id/a.m3u8", block = true))
    NetworkPolicy.blockInsecureStreams = true
    check("blokir: bawaan membaca NetworkPolicy (nyala)", IptvTrust.isBlocked("http://x.example/a.m3u8"))
    NetworkPolicy.blockInsecureStreams = false
    check("blokir: bawaan membaca NetworkPolicy (mati)", !IptvTrust.isBlocked("http://x.example/a.m3u8"))
    // [IPTV-TRUST-EXEMPT]
    val antv = "http://103.58.160.157:8278/720-ANTV/playlist.m3u8"
    val jtv = "http://122.248.43.242:1935/JTVSURABAYA/_definst_/myStream/playlist.m3u8"
    val sctvTok = "http://x.example/sctv.m3u8?token=abc"
    val indoPlus = "http://a.example/i.m3u8"
    val sctv = "http://b.example/s.m3u8"
    check("pengecualian: sebelum didaftarkan tetap diblokir", IptvTrust.isBlocked(antv, block = true))
    IptvTrust.registerTrustedFeed(listOf("ANTV" to antv, "JTV" to jtv, "SCTV" to sctvTok, "Indosiar Plus" to indoPlus, "  sCtV  " to sctv))
    check("pengecualian: ANTV terdaftar tidak diblokir", !IptvTrust.isBlocked(antv, block = true))
    check("pengecualian: ANTV butuh konfirmasi", IptvTrust.needsConfirm(antv, block = true))
    check("pengecualian: nama normalisasi (spasi/huruf besar)", !IptvTrust.isBlocked(sctv, block = true))
    check("pengecualian: JTV tidak ada di daftar, tetap diblokir", IptvTrust.isBlocked(jtv, block = true) && !IptvTrust.needsConfirm(jtv, block = true))
    check("pengecualian: nama mirip (Indosiar Plus) tetap diblokir", IptvTrust.isBlocked(indoPlus, block = true))
    check("pengecualian: token di URL tetap diblokir", IptvTrust.isBlocked(sctvTok, block = true))
    check("pengecualian: opsi mati, tidak ada konfirmasi", !IptvTrust.needsConfirm(antv, block = false))
    IptvTrust.confirm(jtv)
    check("pengecualian: confirm pada non-pengecualian tidak berefek", IptvTrust.isBlocked(jtv, block = true))
    IptvTrust.confirm(antv)
    check("pengecualian: setelah confirm tidak perlu konfirmasi lagi", !IptvTrust.needsConfirm(antv, block = true) && !IptvTrust.isBlocked(antv, block = true))
    IptvTrust.registerTrustedFeed(emptyList())
    check("pengecualian: feed baru tanpa ANTV, kembali diblokir", IptvTrust.isBlocked(antv, block = true))
    IptvTrust.registerTrustedFeed(listOf("ANTV" to antv))
    check("pengecualian: terdaftar ulang, konfirmasi lama gugur", IptvTrust.needsConfirm(antv, block = true))
    IptvTrust.registerTrustedFeed(emptyList())
    if (fails > 0) { println("$fails GAGAL"); kotlin.system.exitProcess(1) }
    println("semua lulus")
}
