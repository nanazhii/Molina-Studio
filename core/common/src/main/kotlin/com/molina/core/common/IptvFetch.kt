package com.molina.core.common

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.SSLException

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

    /** Memblokir: panggil dari thread IO. [check] hanya diganti oleh uji. */
    fun load(url: String, check: (String) -> String? = ::problem): IptvLoad {
        var current = url.trim()
        for (hop in 0..MAX_REDIRECTS) {
            check(current)?.let { return IptvLoad.Fail(it) }
            val conn = try {
                URL(current).openConnection() as HttpURLConnection
            } catch (e: IOException) {
                return IptvLoad.Fail("Tautan tidak valid.")
            }
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("User-Agent", "MolinaCinema/1.0")
                conn.setRequestProperty("Accept", "*/*")
                val code = conn.responseCode
                if (code in 301..303 || code == 307 || code == 308) {
                    val loc = conn.getHeaderField("Location") ?: return IptvLoad.Fail("Pengalihan tanpa tujuan.")
                    current = try {
                        URL(URL(current), loc).toString()
                    } catch (e: IOException) {
                        return IptvLoad.Fail("Pengalihan tidak valid.")
                    }
                    continue
                }
                if (code != 200) return IptvLoad.Fail("Server menjawab HTTP $code.")
                val bytes = readLimited(conn) ?: return IptvLoad.Fail("Playlist terlalu besar (maks 5 MB).")
                return interpret(M3uParser.parse(String(bytes, Charsets.UTF_8)))
            } catch (e: SSLException) {
                return IptvLoad.Fail("Koneksi aman gagal (sertifikat server tidak valid).")
            } catch (e: IOException) {
                return IptvLoad.Fail("Gagal terhubung: periksa koneksi internet.")
            } finally {
                conn.disconnect()
            }
        }
        return IptvLoad.Fail("Terlalu banyak pengalihan.")
    }

    internal fun interpret(r: M3uResult): IptvLoad = when {
        r.isHlsStream -> IptvLoad.Fail("Ini playlist HLS satu stream, bukan daftar saluran. Putar langsung dari kolom pencarian.")
        r.channels.isEmpty() -> IptvLoad.Fail(
            if (r.skipped > 0) "Tidak ada saluran yang lolos kebijakan URL (${r.skipped} ditolak)."
            else "Tidak ada saluran di playlist ini."
        )
        else -> IptvLoad.Ok(r)
    }

    private fun readLimited(conn: HttpURLConnection): ByteArray? {
        conn.inputStream.use { ins ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                if (out.size() + n > MAX_BYTES) return null
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}

/** Menyusun playlist memory:// untuk pemutar (prev/next = ganti saluran). Murni Kotlin. */
object IptvPlayback {
    const val MAX_ENTRIES = 300
    /** Batas karakter total: extra Intent lewat Binder (batas sekitar 1 MB per transaksi). */
    const val MAX_CHARS = 200_000

    /**
     * Saluran terpilih jadi item pertama, lalu urutan setelahnya (berputar ke awal daftar).
     * Null bila [picked] tidak ada di [channels]. Nama sudah bersih (tanpa baris baru) dari [M3uParser].
     */
    fun memoryPlaylist(channels: List<IptvChannel>, picked: IptvChannel): String? {
        val i = channels.indexOfFirst { it.url == picked.url }
        if (i < 0) return null
        val sb = StringBuilder("memory://#EXTM3U\n")
        var n = 0
        for (c in channels.drop(i) + channels.take(i)) {
            val add = c.name.length + c.url.length + 14
            if (n >= MAX_ENTRIES || (n > 0 && sb.length + add > MAX_CHARS)) break
            sb.append("#EXTINF:-1,").append(c.name).append('\n').append(c.url).append('\n')
            n++
        }
        return sb.toString()
    }
}
