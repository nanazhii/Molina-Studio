package com.molina.core.common

import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Aturan Molina Shield yang berlaku SETELAH DNS di-resolve. [UrlPolicy] hanya melihat teks URL;
 * di sini alamat IP hasil resolusi diperiksa dengan aturan yang sama, sehingga nama host publik
 * yang diarahkan ke alamat privat/loopback (DNS rebinding) ikut tertolak. Murni, tanpa Android.
 */
object ShieldPolicy {
    /** Port layanan sensitif yang tidak pernah dibutuhkan pemutar media (surel, basis data, SMB, RDP). */
    private val BLOCKED_PORTS = setOf(
        22, 23, 25, 110, 135, 139, 143, 445, 465, 587, 993, 995,
        1433, 3306, 3389, 5432, 6379, 27017,
    )

    fun portAllowed(port: Int): Boolean = port in 1..65535 && port !in BLOCKED_PORTS

    /** Periksa satu alamat IP hasil resolusi memakai aturan [UrlPolicy] (loopback, link-local, privat, dst.). */
    fun checkAddress(addr: InetAddress, allowLan: Boolean): UrlPolicy.Verdict {
        val lit = (addr.hostAddress ?: return UrlPolicy.Verdict.Blocked(UrlPolicy.Reason.MALFORMED))
            .substringBefore('%')
        val url = if (addr is Inet6Address) "https://[$lit]/" else "https://$lit/"
        return UrlPolicy.check(url, allowLan)
    }
}

/**
 * Daftar domain iklan, analitik, dan telemetri yang diblokir Shield. Pencocokan per sufiks
 * domain (sub.doubleclick.net cocok dengan doubleclick.net). [allowed] adalah pengecualian
 * dari pengguna dan menang atas daftar blokir.
 */
object TrackerList {
    private val DOMAINS = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com", "app-measurement.com",
        "crashlytics.com", "scorecardresearch.com", "adnxs.com", "taboola.com", "outbrain.com",
        "criteo.com", "criteo.net", "hotjar.com", "mixpanel.com", "segment.io", "segment.com",
        "amplitude.com", "appsflyer.com", "adjust.com", "branch.io", "applovin.com", "mopub.com",
        "moatads.com", "unityads.unity3d.com", "ads.yahoo.com", "advertising.com", "quantserve.com",
        "chartbeat.com", "newrelic.com", "bugsnag.com", "sentry.io", "mgid.com", "popads.net",
        "adsrvr.org", "rubiconproject.com", "pubmatic.com", "openx.net", "smaato.net", "inmobi.com",
    )

    @Volatile
    var allowed: Set<String> = emptySet()

    /** Sakelar pengguna: mati berarti tidak ada domain yang dianggap pelacak. */
    @Volatile
    var enabled: Boolean = true

    fun isTracker(host: String): Boolean {
        if (!enabled) return false
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty()) return false
        if (matches(allowed, h)) return false
        return matches(DOMAINS, h)
    }

    private fun matches(set: Set<String>, host: String): Boolean {
        var cur = host
        while (true) {
            if (cur in set) return true
            val dot = cur.indexOf('.')
            if (dot < 0) return false
            cur = cur.substring(dot + 1)
        }
    }
}

/** Statistik Shield untuk UI dan log: hanya host dan alasan, tidak pernah URL lengkap. */
object ShieldStats {
    data class Event(val atMs: Long, val host: String, val reason: String)

    private const val MAX_EVENTS = 50
    private val blocked = MutableStateFlow(0)
    private val passed = AtomicLong(0)
    private val events = ConcurrentLinkedDeque<Event>()

    /** Jumlah permintaan yang diblokir sejak proses mulai; bisa diamati UI. */
    val blockedCount: StateFlow<Int> get() = blocked

    fun passedCount(): Long = passed.get()

    fun recent(): List<Event> = events.toList()

    /** Host dibersihkan dari karakter kontrol dan dibatasi panjangnya sebelum dicatat. */
    fun cleanHost(host: String): String = host.filter { it > ' ' && it != '\u007f' }.take(100)

    fun recordPassed() {
        passed.incrementAndGet()
    }

    fun recordBlocked(host: String, reason: String) {
        blocked.update { it + 1 }
        events.addLast(Event(System.currentTimeMillis(), cleanHost(host), reason))
        while (events.size > MAX_EVENTS) events.pollFirst()
    }
}
