import com.molina.core.common.DownloadUrlGuard

private var fails = 0
private var runs = 0
private fun check(name: String, ok: Boolean) { runs++; if (!ok) { fails++; println("GAGAL: $name") } }

private fun blocked(u: String, lan: Boolean = false) = DownloadUrlGuard.check(listOf(u), lan) != null

fun main() {
    // URL publik lolos
    for (u in listOf("https://www.youtube.com/watch?v=abc", "http://example.com/a.mp4", "https://sub.example.org:8443/x?y=1#z"))
        check("publik lolos: $u", !blocked(u))
    check("daftar kosong", DownloadUrlGuard.check(emptyList()) == null)
    check("entri kosong dilewati", DownloadUrlGuard.check(listOf("  ", "", "https://example.com/a")) == null)

    // loopback dan metadata ditolak, juga saat LAN diizinkan
    for (u in listOf("http://127.0.0.1/a", "http://127.1.2.3:8080/a", "http://localhost/a", "http://localhost:9000/a", "http://[::1]/a", "http://169.254.169.254/latest/meta-data")) {
        check("loopback ditolak: $u", blocked(u))
        check("loopback tetap ditolak walau LAN diizinkan: $u", blocked(u, lan = true))
    }

    // jaringan rumah: ditolak default, lolos bila LAN diizinkan
    for (u in listOf("http://192.168.1.1/admin", "http://10.0.0.5/x", "http://172.16.0.9/x", "http://nas.local/x", "http://[fd00::1]/x")) {
        check("LAN ditolak default: $u", blocked(u))
        check("LAN lolos bila diizinkan: $u", !blocked(u, lan = true))
    }

    // penyamaran alamat numerik
    for (u in listOf("http://2130706433/a", "http://0x7f.0.0.1/a", "http://0177.0.0.1/a", "http://127.1/a"))
        check("numerik samar ditolak: $u", blocked(u))

    // pesan: tanpa URL/query, menyebut urutan URL
    val msg = DownloadUrlGuard.check(listOf("https://example.com/ok", "http://127.0.0.1/a?token=SECRET"))
    check("pesan ada", msg != null)
    check("pesan tanpa rahasia", msg != null && !msg.contains("SECRET") && !msg.contains("127.0.0.1"))
    check("pesan menyebut URL ke-2", msg != null && msg.contains("URL ke-2"))

    // di luar cakupan guard ini: userinfo dan host non-ASCII tidak diblokir (agar unduhan sah tidak patah)
    check("userinfo tidak diblokir guard", !blocked("https://user:pw@example.com/x"))
    check("IDN tidak diblokir guard", !blocked("https://m\u00fcnchen.example/x"))

    println(if (fails == 0) "LULUS: $runs kasus" else "$fails dari $runs kasus GAGAL")
    if (fails != 0) System.exit(1)
}
