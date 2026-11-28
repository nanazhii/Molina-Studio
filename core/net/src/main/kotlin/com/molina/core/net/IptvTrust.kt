package com.molina.core.net

import java.net.URI

/** [IPTV-TRUST] Tingkat kepercayaan stream, dihitung di perangkat dari URL. Label dari feed sengaja tidak dipercaya. */
object IptvTrust {
    enum class Level { OFFICIAL, COMMUNITY, INSECURE }

    private val OFFICIAL_HOSTS = listOf(
        "tvri.go.id", "detik.com", "cnbcindonesia.com", "medcom.id", "rctiplus.id", "rctiplus.com",
        "vidio.com", "kompas.tv", "kompas.com", "metrotvnews.com", "indosiar.com", "emtek.co.id",
    )
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val SECRET_QUERY = Regex("(^|&)(token|auth|key|pass|password)=", RegexOption.IGNORE_CASE)

    /** HTTP, IP mentah, kredensial di URL, atau URL tak terbaca = INSECURE; HTTPS + domain penyiar = OFFICIAL; sisanya COMMUNITY. */
    fun of(url: String): Level {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return Level.INSECURE
        val host = u.host?.lowercase().orEmpty()
        if (!"https".equals(u.scheme, ignoreCase = true) || host.isEmpty() || host.contains(':') || IPV4.matches(host) ||
            u.userInfo != null || SECRET_QUERY.containsMatchIn(u.rawQuery.orEmpty())
        ) return Level.INSECURE
        return if (OFFICIAL_HOSTS.any { host == it || host.endsWith(".$it") }) Level.OFFICIAL else Level.COMMUNITY
    }

    /** [IPTV-TRUST-BLOCK] true bila opsi blokir menyala dan URL berkategori INSECURE. Dipakai di daftar dan di titik putar (fail-closed). */
    fun isBlocked(url: String, block: Boolean = NetworkPolicy.blockInsecureStreams): Boolean =
        block && of(url) == Level.INSECURE && url !in exempt // [IPTV-TRUST-EXEMPT]

    // [IPTV-TRUST-EXEMPT] Pengecualian sempit untuk channel nasional: hanya nama dalam daftar ini, hanya dari feed Molina
    // (pemanggil mendaftarkan), hanya HTTP polos tanpa kredensial/token. Memori proses saja, tidak disimpan ke disk.
    private val EXEMPT_NAMES = setOf("antv", "indosiar", "sctv", "tvri sport")
    private val exempt: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val confirmed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun normName(n: String): String = n.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun plainHttp(url: String): Boolean {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        return "http".equals(u.scheme, ignoreCase = true) && !u.host.isNullOrEmpty() && u.userInfo == null &&
            !SECRET_QUERY.containsMatchIn(u.rawQuery.orEmpty())
    }

    /** Panggil HANYA untuk hasil muat feed Molina. Menggantikan pendaftaran lama; konfirmasi untuk URL yang hilang ikut gugur. */
    fun registerTrustedFeed(channels: List<Pair<String, String>>) {
        val urls = channels.filter { (n, u) -> normName(n) in EXEMPT_NAMES && plainHttp(u) }.map { it.second }.toSet()
        exempt.retainAll(urls); exempt.addAll(urls); confirmed.retainAll(urls)
    }

    /** true bila channel pengecualian belum dikonfirmasi pada sesi ini. Titik putar wajib menolak selama true (fail-closed). */
    fun needsConfirm(url: String, block: Boolean = NetworkPolicy.blockInsecureStreams): Boolean =
        block && url in exempt && url !in confirmed && of(url) == Level.INSECURE

    /** Catat konfirmasi pengguna sampai proses berakhir. Diabaikan untuk URL yang bukan pengecualian. */
    fun confirm(url: String) { if (url in exempt) confirmed.add(url) }
}
