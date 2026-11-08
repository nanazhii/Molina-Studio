package com.molina.cinema

import com.molina.core.common.AppLog
import com.molina.core.common.LogLevel
import com.molina.core.common.NetworkPolicy
import com.molina.core.common.ShieldPolicy
import com.molina.core.common.ShieldProxy
import com.molina.core.common.StreamHeaders
import com.molina.core.common.UrlPolicy
import java.net.InetAddress
import java.net.URI

/**
 * Menyusun argumen perintah mpv `loadfile` dengan opsi per-entri (header HTTP).
 * Opsi per-entri menempel pada entri playlist mpv, jadi tiap video antrean membawa headernya
 * sendiri tanpa antrean tambahan. Fungsi murni, tanpa Android.
 *
 * Sintaks mpv: `loadfile <url> <flag> <index> <opsi>`; <index> -1 = bawaan (butuh mpv >= 0.38).
 * Nilai memakai awalan panjang-tetap `%N%` (N = jumlah byte) agar koma/titik dua di dalam
 * nilai tidak dibaca sebagai pemisah. Berlapis: tiap header, lalu seluruh daftar.
 */
object MpvLoad {
    const val REPLACE = "replace"
    const val APPEND = "append"

    private const val MEMORY_HEADER = "memory://#EXTM3U"
    const val MAX_MEMORY_ENTRIES = 1000

    /** Satu-satunya protokol yang boleh dipakai ffmpeg untuk sumber http/https (termasuk redirect, segmen HLS/DASH). */
    // httpproxy wajib: FFmpeg memakainya untuk tunnel CONNECT saat https lewat Molina Shield.
    const val REMOTE_PROTOCOLS = "http,https,tls,tcp,crypto,data,httpproxy"

    /** Protokol ffmpeg yang tidak pernah dibutuhkan pemutar dan rawan disalahgunakan, berlaku untuk semua sumber. */
    const val GLOBAL_PROTOCOL_BLACKLIST = "concat,concatf,subfile,ftp,sftp,unix,md5,gopher,gophers,pipe,fd,tee"

    /** [PL-STREAM-GUARD] Tambahan blacklist bila opsi "Blokir rtmp di dalam playlist" menyala. rtsp tidak tercakup (bukan protokol ffmpeg biasa). */
    const val PLAYLIST_STREAM_BLACKLIST = "rtmp,rtmps,rtmpt,rtmpe,rtmpte,rtmpts"
    private val RTMP_SCHEMES = setOf("rtmp", "rtmps", "rtmpt", "rtmpe", "rtmpte", "rtmpts")

    fun loadfileArgs(url: String, flag: String, headers: Map<String, String>, title: String? = null): Array<String> {
        // Jaringan dianggap tidak tepercaya: URL yang melanggar kebijakan tidak pernah sampai ke mpv.
        val verdict = if (isMemoryPlaylist(url)) memoryPlaylistVerdict(url) else UrlPolicy.check(url)
        if (verdict is UrlPolicy.Verdict.Blocked) {
            AppLog.log("net", LogLevel.Warn, "policy", "ditolak: ${verdict.reason.name}")
            return arrayOf("show-text", "Diblokir: ${verdict.reason.message}", "5000")
        }
        val opts = listOfNotNull(headerOption(headers), titleOption(title)) + remoteProtocolOptions(url) + directStreamOptions(url)
        if (opts.isEmpty()) return arrayOf("loadfile", url, flag)
        return arrayOf("loadfile", url, flag, "-1", opts.joinToString(","))
    }

    private val STREAM_SCHEMES = setOf("rtmp", "rtmps", "rtsp")

    /** [DNS-CHECK] rtmp/rtsp tidak lewat Molina Shield, jadi namanya di-resolve dan tiap IP diperiksa sebelum diputar. */
    fun needsResolveCheck(url: String): Boolean = UrlPolicy.scheme(url) in STREAM_SCHEMES

    /** Memblokir (DNS): panggil dari thread latar. Gagal resolve = Allowed; mpv melaporkan galatnya sendiri. */
    fun resolveVerdict(url: String, allowLan: Boolean = NetworkPolicy.allowLan): UrlPolicy.Verdict {
        val host = runCatching { URI(url.trim()).host }.getOrNull()?.trim('[', ']')
        if (host.isNullOrEmpty()) return UrlPolicy.Verdict.Allowed
        val addrs = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return UrlPolicy.Verdict.Allowed
        for (a in addrs) {
            val v = ShieldPolicy.checkAddress(a, allowLan)
            if (v is UrlPolicy.Verdict.Blocked) return v
        }
        return UrlPolicy.Verdict.Allowed
    }

    /** Opsi `force-media-title=...` (maks 200 karakter, tanpa karakter kontrol) atau null. */
    fun titleOption(title: String?): String? {
        val t = title?.filter { it >= ' ' && it != '\u007f' }?.trim()?.take(200)
        if (t.isNullOrEmpty()) return null
        return "force-media-title=" + fixed(t)
    }

    /** Opsi `http-header-fields=...` atau null bila tidak ada header valid. */
    fun headerOption(headers: Map<String, String>): String? {
        val clean = StreamHeaders.sanitize(headers)
        if (clean.isEmpty()) return null
        val list = clean.entries.joinToString(",") { fixed("${it.key}: ${it.value}") }
        return "http-header-fields=" + fixed(list)
    }

    fun isMemoryPlaylist(url: String): Boolean = url.startsWith(MEMORY_HEADER, ignoreCase = true)

    /**
     * Playlist memory:// (bagikan banyak berkas) memuat baris baru, jadi tidak bisa diperiksa sebagai satu
     * URL. Tiap baris diperiksa UrlPolicy; skema memory di dalam isi ditolak sendiri oleh UrlPolicy.
     */
    fun memoryPlaylistVerdict(url: String): UrlPolicy.Verdict {
        if (!isMemoryPlaylist(url)) return UrlPolicy.Verdict.Blocked(UrlPolicy.Reason.BAD_SCHEME)
        val entries = url.substring(MEMORY_HEADER.length)
            .split('\n')
            .map { it.trimEnd('\r') }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        if (entries.isEmpty() || entries.size > MAX_MEMORY_ENTRIES) {
            return UrlPolicy.Verdict.Blocked(UrlPolicy.Reason.MALFORMED)
        }
        for (e in entries) {
            val v = UrlPolicy.check(e)
            if (v is UrlPolicy.Verdict.Blocked) return v
        }
        return UrlPolicy.Verdict.Allowed
    }

    /** Opsi ffmpeg `key=<daftar>` untuk `stream-lavf-o` / `demuxer-lavf-o` (daftar berawalan panjang-tetap). */
    fun lavfList(key: String, protocols: String): String = "$key=" + fixed(protocols)

    /** Nilai awal `stream-lavf-o` dan `demuxer-lavf-o` (global): blacklist protokol berbahaya. */
    fun globalProtocolOption(): String {
        val bl = if (NetworkPolicy.blockPlaylistStreams) GLOBAL_PROTOCOL_BLACKLIST + "," + PLAYLIST_STREAM_BLACKLIST else GLOBAL_PROTOCOL_BLACKLIST
        return lavfOptions(listOfNotNull("protocol_blacklist" to bl, proxyPair()))
    }

    /**
     * [PL-STREAM-GUARD] Bila opsi menyala, blacklist global memuat rtmp. Entri rtmp yang DIBUKA LANGSUNG diberi blacklist dasar
     * (tanpa rtmp) per entri, jadi tetap jalan; anak entri dari playlist memakai global dan ditolak.
     */
    fun directStreamOptions(url: String): List<String> {
        if (!NetworkPolicy.blockPlaylistStreams) return emptyList()
        if (UrlPolicy.scheme(url) !in RTMP_SCHEMES) return emptyList()
        val v = fixed(lavfOptions(listOfNotNull("protocol_blacklist" to GLOBAL_PROTOCOL_BLACKLIST, proxyPair())))
        return listOf("stream-lavf-o=$v", "demuxer-lavf-o=$v")
    }

    /**
     * Whitelist ketat per entri untuk http/https. Menimpa opsi global untuk entri itu saja. Skema lain
     * (berkas lokal, rtmp, rtsp) tidak diubah: tetap hanya kena blacklist global.
     */
    fun remoteProtocolOptions(url: String): List<String> {
        val scheme = UrlPolicy.scheme(url)
        if (scheme != "http" && scheme != "https") return emptyList()
        if (isPlaylistUrl(url)) return emptyList()
        val v = fixed(lavfOptions(listOfNotNull("protocol_whitelist" to REMOTE_PROTOCOLS, proxyPair())))
        return listOf("stream-lavf-o=$v", "demuxer-lavf-o=$v")
    }

    fun isPlaylistUrl(url: String): Boolean {
        val x = url.substringBefore("#").substringBefore("?").lowercase()
        return x.endsWith(".m3u") || x.endsWith(".m3u8") || x.endsWith(".pls")
    }

    /** Beberapa pasangan key=value untuk satu opsi `*-lavf-o`, tiap nilai berawalan panjang-tetap. */
    fun lavfOptions(pairs: List<Pair<String, String>>): String =
        pairs.joinToString(",") { it.first + "=" + fixed(it.second) }

    /** `http_proxy` Molina Shield bila proxy berjalan; null (tanpa proxy) bila tidak. */
    fun proxyPair(): Pair<String, String>? = ShieldProxy.proxyUrl()?.let { "http_proxy" to it }

    private fun fixed(s: String): String = "%${s.toByteArray(Charsets.UTF_8).size}%$s"
}
