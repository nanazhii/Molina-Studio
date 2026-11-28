package com.molina.core.net

import java.net.InetAddress
import java.net.URL

/** Hasil mengambil playlist saluran: [Ok] selalu memuat minimal satu saluran. */
sealed interface IptvLoad {
    class Ok(val result: M3uResult) : IptvLoad
    class Fail(val message: String) : IptvLoad
}

/**
 * Pengambil playlist M3U lewat HTTPS. Murni JDK (tanpa Android) agar bisa diuji di JVM.
 * Lalu lintas Java/Kotlin aplikasi tanpa cleartext (network_security_config), jadi hanya https.
 * Pengalihan diikuti manual dan SETIAP lompatan diperiksa ulang (cegah pengalihan ke alamat lokal).
 */
object IptvFetch {
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_REDIRECTS = 5

    /** Pesan masalah bila [url] tidak boleh diambil aplikasi; null = boleh. */
    fun problem(url: String): String? {
        val u = url.trim()
        return when (UrlPolicy.scheme(u)) {
            "https" -> {
                val v = UrlPolicy.check(u)
                if (v is UrlPolicy.Verdict.Blocked) "Tautan ditolak: ${v.reason.message}." else null
            }
            "http" -> "Daftar harus lewat https (aplikasi memblokir http). Untuk .m3u http, tempel di kolom pencarian lalu Putar."
            else -> "Tautan playlist harus diawali https://"
        }
    }

    /** [SHIELD-IPTV] [problem] + cek alamat hasil DNS (DNS rebinding ke alamat privat/loopback). Memblokir: thread IO. */
    fun problemWithDns(url: String): String? {
        problem(url)?.let { return it }
        val host = runCatching { URL(url.trim()).host }.getOrNull()?.trim('[', ']')
        if (host.isNullOrEmpty()) return "Tautan tidak valid."
        val addrs = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return null
        for (a in addrs) {
            if (ShieldPolicy.checkAddress(a, NetworkPolicy.allowLan) is UrlPolicy.Verdict.Blocked) {
                return "Tautan ditolak: DNS mengarah ke alamat terlarang."
            }
        }
        return null
    }

    /**
     * [SHIELD-HTTP] Memblokir: panggil dari thread IO. Lewat [ShieldHttp] (fail-closed, pengalihan diperiksa ulang,
     * batas byte). [check] mengembalikan pesan masalah per lompatan; bawaannya DNS + kebijakan Shield (port, pelacak).
     */
    fun load(url: String, check: (String) -> String? = ::shieldProblem): IptvLoad {
        var problem: String? = null
        val r = ShieldHttp.execute(
            ShieldHttp.Request(
                url.trim(), 20_000, mapOf(HttpHeaderNames.USER_AGENT to "MolinaCinema/1.0", "Accept" to "*/*"), // [HDR-NAMES]
                MAX_REDIRECTS, MAX_BYTES + 1, "iptv",
            )
        ) { u -> problem = check(u); problem == null }
        return when (r) {
            is ShieldHttp.Result.Blocked -> IptvLoad.Fail(problem ?: "Tautan ditolak: ${r.reason}.")
            is ShieldHttp.Result.Failed -> IptvLoad.Fail(failMessage(r.reason))
            is ShieldHttp.Result.Ok -> when {
                r.code != 200 -> IptvLoad.Fail("Server menjawab HTTP ${r.code}.")
                r.body.size > MAX_BYTES -> IptvLoad.Fail("Playlist terlalu besar (maks 5 MB).")
                else -> interpret(M3uParser.parse(String(r.body, Charsets.UTF_8)))
            }
        }
    }

    private fun shieldProblem(url: String): String? =
        problemWithDns(url) ?: if (ShieldProbe.isAllowed(url)) null else "Tautan ditolak kebijakan Shield."

    private fun failMessage(reason: String): String = when {
        reason == "tautan tidak valid" -> "Tautan tidak valid."
        reason == "pengalihan tanpa tujuan" -> "Pengalihan tanpa tujuan."
        reason == "pengalihan tidak valid" -> "Pengalihan tidak valid."
        reason == "terlalu banyak pengalihan" -> "Terlalu banyak pengalihan."
        reason.startsWith("SSL") -> "Koneksi aman gagal (sertifikat server tidak valid)."
        else -> "Gagal terhubung: periksa koneksi internet."
    }

    internal fun interpret(r: M3uResult): IptvLoad = when {
        r.isHlsStream -> IptvLoad.Fail("Ini playlist HLS satu stream, bukan daftar saluran. Putar langsung dari kolom pencarian.")
        r.channels.isEmpty() -> IptvLoad.Fail(
            if (r.skipped > 0) "Tidak ada saluran yang lolos kebijakan URL (${r.skipped} ditolak)."
            else "Tidak ada saluran di playlist ini."
        )
        else -> IptvLoad.Ok(r)
    }

}

/** [ORDER-KEEP] uri = playlist urutan asli; startIndex = posisi terpilih. */
class IptvQueue(val uri: String, val startIndex: Int)

/** Menyusun playlist memory:// untuk pemutar (prev/next = ganti saluran). Murni Kotlin. */
object IptvPlayback {
    const val MAX_ENTRIES = 1000 // [ORDER-KEEP] sama dengan batas memory://
    /** Batas karakter total: extra Intent lewat Binder (batas sekitar 1 MB per transaksi). */
    const val MAX_CHARS = 200_000

    /**
     * Saluran terpilih jadi item pertama, lalu urutan setelahnya (berputar ke awal daftar).
     * Null bila [picked] tidak ada di [channels]. Nama sudah bersih (tanpa baris baru) dari [M3uParser].
     */
    fun memoryPlaylist(channels: List<IptvChannel>, picked: IptvChannel): String? =
        queue(channels, picked)?.uri

    /** [ORDER-KEEP] Urutan asli daftar (DRM dilewati). Di luar batas: jendela mulai dari terpilih. */
    fun queue(channels: List<IptvChannel>, picked: IptvChannel): IptvQueue? {
        if (picked.drm) return null // [DRM-FLAG]
        val ok = channels.filter { !it.drm && (!it.broken || it.url == picked.url) } // [CHIP-SPLIT]
        val pos = ok.indexOfFirst { it.url == picked.url }
        if (pos < 0) return null
        val first = build(ok, 0)
        if (pos < first.second) return IptvQueue(first.first, pos)
        return IptvQueue(build(ok, pos).first, 0)
    }

    private fun build(list: List<IptvChannel>, from: Int): Pair<String, Int> {
        val sb = StringBuilder("memory://#EXTM3U\n")
        var n = 0
        for (c in list.drop(from)) {
            val logo = logoAttr(c.logo) // [STATION-LOGO]
            val add = c.name.length + c.url.length + 14 + (if (logo.isEmpty()) 0 else logo.length + 12)
            if (n >= MAX_ENTRIES || (n > 0 && sb.length + add > MAX_CHARS)) break
            sb.append("#EXTINF:-1")
            if (logo.isNotEmpty()) sb.append(" tvg-logo=\"").append(logo).append('"')
            sb.append(',').append(c.name).append('\n').append(c.url).append('\n')
            n++
        }
        return Pair(sb.toString(), n)
    }

    /** [STATION-LOGO] Logo hanya https tanpa tanda kutip, koma, spasi, atau karakter kontrol (aman di atribut EXTINF). */
    private fun logoAttr(l: String): String =
        if (l.startsWith("https://") && l.none { it == '"' || it == ',' || it.isWhitespace() || it < ' ' }) l else ""
}
