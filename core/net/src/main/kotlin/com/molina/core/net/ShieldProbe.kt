package com.molina.core.net

import java.io.IOException
import java.net.InetAddress
import java.net.URL

/**
 * [SHIELD-PROBE] Pemeriksaan Content-Type singkat untuk URL yang dikirim pengguna, dengan kebijakan Shield yang sama
 * seperti proxy: [UrlPolicy], port sensitif, daftar pelacak, dan IP hasil DNS ([ShieldPolicy]). Pengalihan diikuti
 * manual (maks [MAX_HOPS]) dan tiap lompatan diperiksa ulang. Bukan lewat proxy; sisa risiko: DNS di-resolve ulang
 * oleh HttpURLConnection (jendela rebinding sempit). Murni JVM, tanpa Android.
 */
object ShieldProbe {
    private const val MAX_HOPS = 3

    /** [SHIELD-HTTP] Content-Type dari GET Range 0-0 lewat [ShieldHttp], atau null bila ditolak kebijakan/galat. Memblokir: thread IO. */
    fun contentType(url: String, timeoutMs: Int): String? {
        val r = ShieldHttp.execute(
            ShieldHttp.Request(
                url, timeoutMs, mapOf("Range" to "bytes=0-0", HttpHeaderNames.USER_AGENT to "Mozilla/5.0"), // [HDR-NAMES]
                MAX_HOPS, label = "probe",
            )
        ) { isAllowed(it) }
        return (r as? ShieldHttp.Result.Ok)?.contentType
    }

    /** True bila [url] (http/https) lolos kebijakan Shield; [resolve] hanya diganti oleh uji. */
    fun isAllowed(
        url: String,
        resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    ): Boolean {
        val allowLan = NetworkPolicy.allowLan
        if (UrlPolicy.check(url, allowLan) is UrlPolicy.Verdict.Blocked) return false
        val u = try {
            URL(url)
        } catch (e: IOException) {
            return false
        }
        if (u.protocol != "http" && u.protocol != "https") return false
        val port = if (u.port > 0) u.port else u.defaultPort
        if (!ShieldPolicy.portAllowed(port)) return false
        val host = u.host.trim('[', ']')
        if (host.isEmpty() || TrackerList.isTracker(host)) return false
        val addrs = try {
            resolve(host)
        } catch (e: IOException) {
            return false
        }
        return addrs.isNotEmpty() && addrs.all { ShieldPolicy.checkAddress(it, allowLan) is UrlPolicy.Verdict.Allowed }
    }
}
