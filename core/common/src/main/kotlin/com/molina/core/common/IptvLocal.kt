package com.molina.core.common

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Playlist M3U dari berkas lokal di folder IPTV Molina (mis. /sdcard/Molina/Media/IPTV). Murni JDK agar bisa
 * diuji di JVM. Hanya berkas .m3u/.m3u8 yang berada LANGSUNG di folder IPTV yang boleh dibaca; jalur yang
 * tersimpan di preferensi tidak dipercaya (cegah membaca berkas lain lewat ".." atau symlink).
 * Isi playlist tetap lewat [M3uParser], jadi kebijakan URL saluran sama dengan playlist dari internet.
 */
object IptvLocal {
    const val MAX_FILES = 100
    private val EXTENSIONS = setOf("m3u", "m3u8")

    class Entry(val file: File, val sizeBytes: Long)

    /** Sumber lokal disimpan sebagai jalur absolut (diawali '/'); sumber jarak jauh berupa URL https. */
    fun isLocalRef(ref: String): Boolean = ref.startsWith("/")

    /** Berkas playlist di [dir] (tanpa subfolder), urut nama. null = folder tidak ada atau tidak bisa dibaca. */
    fun list(dir: File): List<Entry>? {
        val files = try {
            dir.listFiles()
        } catch (e: SecurityException) {
            null
        } ?: return null
        return files
            .filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
            .sortedBy { it.name.lowercase() }
            .take(MAX_FILES)
            .map { Entry(it, it.length()) }
    }

    /** true bila [file] adalah anak langsung [dir] setelah ".." dan symlink diselesaikan. */
    fun isDirectChild(dir: File, file: File): Boolean = try {
        file.canonicalFile.parentFile == dir.canonicalFile
    } catch (e: IOException) {
        false
    }

    /** Memblokir: panggil dari thread IO. */
    fun load(dir: File, file: File): IptvLoad {
        if (!isDirectChild(dir, file)) return IptvLoad.Fail("Berkas harus berada di folder IPTV Molina.")
        if (file.extension.lowercase() !in EXTENSIONS) return IptvLoad.Fail("Hanya berkas .m3u atau .m3u8.")
        if (!file.isFile) return IptvLoad.Fail("Berkas tidak ditemukan.")
        val bytes: ByteArray?
        try {
            bytes = readLimited(file)
        } catch (e: IOException) {
            return IptvLoad.Fail("Berkas tidak bisa dibaca (periksa izin penyimpanan).")
        }
        if (bytes == null) return IptvLoad.Fail("Playlist terlalu besar (maks 5 MB).")
        return IptvFetch.interpret(M3uParser.parse(String(bytes, Charsets.UTF_8)))
    }

    private fun readLimited(file: File): ByteArray? {
        file.inputStream().use { ins ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                if (out.size() + n > IptvFetch.MAX_BYTES) return null
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
