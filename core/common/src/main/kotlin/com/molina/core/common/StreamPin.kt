package com.molina.core.common

import java.net.Inet4Address
import java.net.InetAddress

/**
 * [SHIELD-PIN] Memeriksa tujuan rtmp/rtsp/rtmps SETELAH DNS dan, untuk rtmp/rtsp (tanpa TLS), mengganti host URL
 * dengan IP yang sudah lolos pemeriksaan. mpv/FFmpeg lalu tidak me-resolve ulang, sehingga DNS rebinding antara
 * pemeriksaan dan koneksi (TOCTOU) tidak mungkin. rtmps tidak di-pin: sertifikat TLS terikat ke nama host
 * (penyerang tidak bisa memalsukannya untuk layanan internal), jadi hanya diperiksa. Murni JDK, bisa diuji di JVM.
 */
object StreamPin {
    sealed interface Result {
        /** URL sudah diganti ke IP [ip] yang lolos; [host] adalah nama asli. */
        class Pinned(val url: String, val host: String, val ip: String) : Result
        /** Lolos tanpa perubahan (mis. rtmps atau host sudah berupa IP). */
        class Unchanged(val url: String) : Result
        class Blocked(val verdict: UrlPolicy.Verdict.Blocked) : Result
    }

    private val PINNED = setOf("rtmp", "rtsp")
    private val V4 = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")

    /** Host dari URL tanpa java.net.URI (URI melempar pada karakter yang masih diterima mpv). null bila tidak terbaca. */
    fun hostOf(url: String): String? {
        val i = url.indexOf("://")
        if (i <= 0) return null
        val authority = url.substring(i + 3).takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
        val host = if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']')
        else authority.substringBefore(':')
        return host.ifEmpty { null }
    }

    private fun isLiteral(host: String) = ':' in host || V4.matches(host)

    private fun blocked(r: UrlPolicy.Reason) = Result.Blocked(UrlPolicy.Verdict.Blocked(r))

    /**
     * Panggil dari thread latar. [dns] hanya diganti oleh uji. Aturan: URL harus lolos [UrlPolicy]; host tak terbaca
     * atau gagal resolve = ditolak; rtmp/rtsp dipin ke alamat pertama yang lolos (IPv4 didahulukan), rtmps
     * ditolak bila SALAH SATU alamat terlarang.
     */
    fun resolve(
        url: String,
        allowLan: Boolean,
        dns: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    ): Result {
        val u = url.trim()
        val v = UrlPolicy.check(u, allowLan)
        if (v is UrlPolicy.Verdict.Blocked) return Result.Blocked(v)
        val host = hostOf(u) ?: return blocked(UrlPolicy.Reason.NO_HOST)
        val addrs: List<InetAddress> = if (isLiteral(host)) {
            listOf(runCatching { InetAddress.getByName(host) }.getOrNull() ?: return blocked(UrlPolicy.Reason.MALFORMED))
        } else {
            runCatching { dns(host) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return blocked(UrlPolicy.Reason.MALFORMED)
        }
        val verdicts = addrs.map { it to ShieldPolicy.checkAddress(it, allowLan) }
        if (UrlPolicy.scheme(u) !in PINNED) {
            verdicts.firstOrNull { it.second is UrlPolicy.Verdict.Blocked }?.let { return Result.Blocked(it.second as UrlPolicy.Verdict.Blocked) }
            return Result.Unchanged(u)
        }
        val ok = verdicts.filter { it.second is UrlPolicy.Verdict.Allowed }.map { it.first }
            .sortedBy { if (it is Inet4Address) 0 else 1 }
        val pick = ok.firstOrNull() ?: return Result.Blocked(verdicts.map { it.second }.filterIsInstance<UrlPolicy.Verdict.Blocked>().first())
        if (isLiteral(host)) return Result.Unchanged(u)
        val lit = (pick.hostAddress ?: return blocked(UrlPolicy.Reason.MALFORMED)).substringBefore('%')
        val rewritten = rewriteHost(u, if (pick is Inet4Address) lit else "[$lit]") ?: return blocked(UrlPolicy.Reason.MALFORMED)
        return Result.Pinned(rewritten, host, lit)
    }

    /** Ganti host (dan pertahankan port, path, query) dengan [hostLiteral] (IPv6 sudah berkurung). */
    internal fun rewriteHost(url: String, hostLiteral: String): String? {
        val i = url.indexOf("://")
        if (i <= 0) return null
        val head = url.substring(0, i + 3)
        val rest = url.substring(i + 3)
        val authEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authEnd)
        if ('@' in authority) return null
        val port = if (authority.startsWith("[")) authority.substringAfter(']', "").takeIf { it.startsWith(":") }
        else authority.substringAfter(':', "").takeIf { authority.contains(':') }?.let { ":$it" }
        return head + hostLiteral + (port ?: "") + rest.substring(authEnd)
    }
}
