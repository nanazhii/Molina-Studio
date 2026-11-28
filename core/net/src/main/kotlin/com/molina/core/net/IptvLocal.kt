package com.molina.core.net

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files // [ERR-PRUNE]
import java.nio.file.StandardCopyOption

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

    /** [IPTV-BUNDLED] Playlist bawaan aplikasi (aset): jalur tafsir yang sama dengan berkas lokal. Tanpa I/O. */
    fun loadBundled(bytes: ByteArray): IptvLoad {
        if (bytes.size > IptvFetch.MAX_BYTES) return IptvLoad.Fail("Playlist terlalu besar (maks 5 MB).")
        return IptvFetch.interpret(M3uParser.parse(String(bytes, Charsets.UTF_8)))
    }

    /** [ERR-PRUNE] Hasil [prune]: [Done.removed] = jumlah baris URL yang dibuang dari berkas. */
    sealed class Prune {
        class Done(val removed: Int) : Prune()
        class Fail(val message: String) : Prune()
    }

    /**
     * [ERR-PRUNE] Buang entri ber-URL [urls] dari berkas playlist lokal. Aturan jalur sama dengan [load]. Berkas dibaca
     * dan ditulis sebagai ISO-8859-1 (bijektif per byte) supaya byte non-UTF-8 tidak rusak; cadangan disimpan di
     * "<nama>.bak" (bukan .m3u/.m3u8, jadi tidak muncul di daftar), lalu hasil ditulis ke berkas sementara dan menggantikan aslinya.
     * Memblokir: panggil dari thread IO.
     */
    fun prune(dir: File, file: File, urls: Set<String>): Prune {
        if (!isDirectChild(dir, file)) return Prune.Fail("Berkas harus berada di folder IPTV Molina.")
        if (file.extension.lowercase() !in EXTENSIONS) return Prune.Fail("Hanya berkas .m3u atau .m3u8.")
        if (!file.isFile) return Prune.Fail("Berkas tidak ditemukan.")
        if (urls.isEmpty()) return Prune.Done(0)
        return try {
            val bytes = readLimited(file) ?: return Prune.Fail("Playlist terlalu besar (maks 5 MB).")
            val raw = Charsets.ISO_8859_1
            val wanted = urls.mapTo(HashSet()) { String(it.toByteArray(Charsets.UTF_8), raw) }
            val r = M3uPrune.removeUrls(String(bytes, raw), wanted)
            if (r.removed == 0) return Prune.Done(0)
            file.copyTo(File(file.path + ".bak"), overwrite = true)
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(r.text.toByteArray(raw))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Prune.Done(r.removed)
        } catch (e: IOException) {
            Prune.Fail("Berkas tidak bisa ditulis (periksa izin penyimpanan).")
        }
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
