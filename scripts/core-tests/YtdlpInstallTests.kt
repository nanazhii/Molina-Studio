import com.molina.core.download.YtdlpInstall as Y
import kotlin.system.exitProcess

private var runs = 0
private var fails = 0
private fun check(name: String, ok: Boolean) { runs++; if (!ok) { fails++; println("GAGAL: $name") } }

fun main() {
    check("github.com tepercaya", Y.isTrustedHost("github.com") && Y.isTrustedHost("GitHub.com") && Y.isTrustedHost("github.com."))
    check("aset rilis githubusercontent tepercaya", Y.isTrustedHost("objects.githubusercontent.com") && Y.isTrustedHost("release-assets.githubusercontent.com") && Y.isTrustedHost("githubusercontent.com"))
    check("host mirip ditolak", !Y.isTrustedHost("evilgithub.com") && !Y.isTrustedHost("github.com.evil.io") && !Y.isTrustedHost("notgithubusercontent.com"))
    check("null/kosong/IP ditolak", !Y.isTrustedHost(null) && !Y.isTrustedHost("") && !Y.isTrustedHost("127.0.0.1"))

    val shebang = "#!/usr/bin/env python3\n".toByteArray(Charsets.ISO_8859_1)
    val pk = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0)
    check("zipapp dengan shebang lolos", Y.looksLikeZipapp(shebang + pk))
    check("zip murni lolos", Y.looksLikeZipapp(pk))
    check("HTML ditolak", !Y.looksLikeZipapp("<html><body>404</body></html>".toByteArray()))
    check("PK tanpa shebang di depan ditolak", !Y.looksLikeZipapp("xx".toByteArray() + pk))
    check("PK terlalu jauh (di luar 256 byte) ditolak", !Y.looksLikeZipapp(shebang + ByteArray(300) { 0x20 } + pk))
    check("kosong/pendek ditolak", !Y.looksLikeZipapp(ByteArray(0)) && !Y.looksLikeZipapp(byteArrayOf(0x50, 0x4B, 0x03)))

    val h = "a".repeat(64)
    check("expectedSha baca baris yt-dlp", Y.expectedSha("$h  yt-dlp\n" + "b".repeat(64) + "  yt-dlp.exe\n") == h)
    check("expectedSha tolak hash pendek", Y.expectedSha("abc  yt-dlp\n") == null)

    println("YtdlpInstallTests: $runs uji, $fails gagal")
    if (fails > 0) exitProcess(1)
}
