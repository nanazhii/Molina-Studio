package com.molina.code.ui

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files

/** Akhir baris berkas. Editor selalu memegang teks LF; Eol dipakai saat menulis ke disk. */
internal enum class Eol(val label: String, val seq: String) {
    LF("LF", "\n"),
    CRLF("CRLF", "\r\n");

    fun other(): Eol = if (this == LF) CRLF else LF
}

/** Hasil membaca berkas untuk dibuka. [text] sudah dinormalkan ke LF; null bila gagal (lihat [error]). */
internal class OpenResult(val text: String?, val eol: Eol, val hadBom: Boolean, val error: String?)

/** Satu entri folder pada panel direktori dan pemilih. */
internal class DirEntry(val file: File, val isDir: Boolean) {
    val hidden: Boolean get() = file.name.startsWith(".")
}

/**
 * I/O berkas editor (F3e, F4). Semua fungsi memblokir: panggil dari thread IO, bukan thread utama.
 * Enkode terkunci UTF-8 tanpa BOM (blueprint bagian 20.2). Akhir baris dipertahankan dari berkas
 * asli (blueprint bagian 31.1).
 */
internal object FileStore {
    const val STORAGE_ROOT = "/storage/emulated/0"
    const val MAX_OPEN_BYTES = 5L * 1024 * 1024
    private const val MAX_LIST_ENTRIES = 2000
    private const val MAX_NAME_BYTES = 240

    private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
    private const val FNV_PRIME = 1099511628211L

    /** Sidik jari teks kosong; baseline tab "Baru". */
    const val EMPTY_FP: Long = FNV_OFFSET

    /** Baseline khusus tab yatim (berkas dihapus dari disk): tidak akan pernah sama dengan isi apa pun. */
    const val ORPHAN_FP: Long = 0x5EEDDEADBEEFL

    /** Sidik jari FNV-1a 64-bit atas teks yang dinormalkan ke LF, sehingga CRLF dan LF dianggap sama. */
    fun fingerprint(s: String): Long {
        var h = FNV_OFFSET
        var len = 0L
        var i = 0
        val n = s.length
        while (i < n) {
            var c = s[i]
            if (c == '\r') {
                if (i + 1 < n && s[i + 1] == '\n') i++
                c = '\n'
            }
            h = (h xor c.code.toLong()) * FNV_PRIME
            len++
            i++
        }
        return h xor len
    }

    fun normalizeLf(s: String): String =
        if ('\r' !in s) s else s.replace("\r\n", "\n").replace('\r', '\n')

    /** CRLF bila baris berakhiran CRLF lebih banyak daripada LF murni; selain itu LF. */
    fun detectEol(s: String): Eol {
        var crlf = 0
        var lf = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') {
                crlf++
                i += 2
                continue
            }
            if (c == '\n') lf++
            i++
        }
        return if (crlf > lf) Eol.CRLF else Eol.LF
    }

    fun applyEol(text: String, eol: Eol): String {
        val lf = normalizeLf(text)
        return if (eol == Eol.CRLF) lf.replace("\n", "\r\n") else lf
    }

    /** Kunci pembanding path (tab duplikat, rename, hapus). Memakai path kanonik bila bisa. */
    fun keyOf(file: File): String = try {
        file.canonicalPath
    } catch (e: IOException) {
        file.absolutePath
    }

    /**
     * Baca berkas untuk dibuka: UTF-8 ketat, maks [MAX_OPEN_BYTES], tolak berkas biner. BOM dibuang
     * (tidak ditulis lagi saat menyimpan). Teks dikembalikan dalam LF; akhir baris asli di [OpenResult.eol].
     */
    fun readForOpen(file: File): OpenResult {
        fun fail(msg: String) = OpenResult(null, Eol.LF, false, msg)
        try {
            if (!file.isFile) return fail("Bukan berkas biasa")
            if (file.length() > MAX_OPEN_BYTES) return fail("Berkas terlalu besar (maks 5 MB)")
            val bytes = file.readBytes()
            val decoded = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (e: CharacterCodingException) {
                return fail("Bukan teks UTF-8, tidak dibuka")
            }
            val bom = decoded.startsWith("\uFEFF")
            val raw = if (bom) decoded.substring(1) else decoded
            if ('\u0000' in raw) return fail("Berkas biner, tidak dibuka")
            return OpenResult(normalizeLf(raw), detectEol(raw), bom, null)
        } catch (e: Exception) {
            return fail("Gagal membaca: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Isi folder: folder dulu, lalu berkas, A-Z tanpa peka huruf. Null bila tidak bisa dibaca. */
    fun listDir(dir: File): List<DirEntry>? {
        val arr = dir.listFiles() ?: return null
        return arr.asSequence()
            .map { DirEntry(it, it.isDirectory) }
            .sortedWith(compareBy<DirEntry>({ !it.isDir }, { it.file.name.lowercase() }))
            .take(MAX_LIST_ENTRIES)
            .toList()
    }

    /** Hapus berkas atau folder beserta isinya. Tautan simbolik dihapus sebagai tautan, isinya tidak disentuh. */
    fun deleteTree(f: File): Boolean {
        val link = try {
            Files.isSymbolicLink(f.toPath())
        } catch (e: Exception) {
            false
        }
        if (!link && f.isDirectory) {
            f.listFiles()?.forEach { if (!deleteTree(it)) return false }
        }
        return f.delete()
    }

    /**
     * Tulis atomik: ke berkas sementara di folder yang sama lalu rename, sehingga berkas lama
     * tidak rusak bila proses mati di tengah penulisan. Bila rename gagal, tulis langsung.
     */
    @Throws(IOException::class)
    fun writeUtf8Atomic(target: File, text: String) {
        val dir = target.absoluteFile.parentFile ?: throw IOException("Folder tidak valid")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("Tidak bisa membuat folder ${dir.path}")
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val tmp = File(dir, ".${target.name}.molina-tmp")
        var done = false
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            done = tmp.renameTo(target)
        } catch (e: IOException) {
            done = false
        }
        if (!done) {
            tmp.delete()
            target.writeBytes(bytes)
        }
    }

    fun fullName(name: String, ext: String): String {
        val n = name.trim()
        val e = ext.trim().trimStart('.')
        return if (e.isEmpty()) n else "$n.$e"
    }

    /** Pesan galat untuk nama berkas yang tidak boleh dipakai, atau null bila valid. */
    fun validate(name: String, ext: String): String? {
        val n = name.trim()
        val e = ext.trim().trimStart('.')
        return when {
            n.isEmpty() -> "Nama berkas kosong"
            n == "." || n == ".." -> "Nama berkas tidak valid"
            '/' in n || '/' in e || '\u0000' in n || '\u0000' in e -> "Nama tidak boleh mengandung /"
            fullName(name, ext).toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES -> "Nama terlalu panjang"
            else -> null
        }
    }
}

/** Satu tab dalam snapshot draf. [text] null = jangan sentuh berkas draf yang sudah ada. */
internal class DraftEntry(
    val id: Int,
    val title: String,
    val path: String?,
    val dirty: Boolean,
    val wrap: Boolean,
    val readOnly: Boolean,
    val text: String?,
    val eol: Eol,
    val baseEol: Eol,
    val baseFp: Long
)

/** [root] = folder utama panel direktori (F4). */
internal class DraftSnapshot(val active: Int, val next: Int, val tabs: List<DraftEntry>, val root: String?)

private fun eolOf(name: String): Eol = try {
    Eol.valueOf(name)
} catch (e: IllegalArgumentException) {
    Eol.LF
}

/**
 * Penyimpanan draf di folder privat aplikasi (tanpa izin storage): session.json berisi daftar
 * tab, dan d<id>.txt berisi isi tab yang belum disimpan. Best-effort: galat I/O diabaikan.
 */
internal class DraftStore(private val dir: File) {
    private val index: File get() = File(dir, "session.json")

    private fun textFile(id: Int) = File(dir, "d$id.txt")

    fun load(): DraftSnapshot? = try {
        if (!index.isFile) {
            null
        } else {
            val root = JSONObject(index.readText(Charsets.UTF_8))
            val arr = root.getJSONArray("tabs")
            val tabs = (0 until arr.length()).map { i ->
                val t = arr.getJSONObject(i)
                DraftEntry(
                    id = t.getInt("id"),
                    title = t.getString("title"),
                    path = if (t.isNull("path")) null else t.getString("path"),
                    dirty = t.optBoolean("dirty", false),
                    wrap = t.optBoolean("wrap", true),
                    readOnly = t.optBoolean("ro", false),
                    text = null,
                    eol = eolOf(t.optString("eol", "LF")),
                    baseEol = eolOf(t.optString("beol", "LF")),
                    baseFp = t.optLong("bfp", FileStore.EMPTY_FP)
                )
            }
            DraftSnapshot(
                root.optInt("active", -1),
                root.optInt("next", 1),
                tabs,
                if (root.isNull("root")) null else root.optString("root", "").ifEmpty { null }
            )
        }
    } catch (e: Exception) {
        null
    }

    fun readDraft(id: Int): String? = try {
        val f = textFile(id)
        if (f.isFile) f.readText(Charsets.UTF_8) else null
    } catch (e: Exception) {
        null
    }

    fun write(s: DraftSnapshot) {
        try {
            if (!dir.isDirectory) dir.mkdirs()
            val arr = JSONArray()
            for (t in s.tabs) {
                val body = t.text
                if (t.dirty && body != null) FileStore.writeUtf8Atomic(textFile(t.id), body)
                val o = JSONObject()
                o.put("id", t.id)
                o.put("title", t.title)
                o.put("path", t.path ?: JSONObject.NULL)
                o.put("dirty", t.dirty)
                o.put("wrap", t.wrap)
                o.put("ro", t.readOnly)
                o.put("eol", t.eol.name)
                o.put("beol", t.baseEol.name)
                o.put("bfp", t.baseFp)
                arr.put(o)
            }
            val root = JSONObject()
            root.put("v", 2)
            root.put("root", s.root ?: JSONObject.NULL)
            root.put("active", s.active)
            root.put("next", s.next)
            root.put("tabs", arr)
            FileStore.writeUtf8Atomic(index, root.toString())
            // Hapus draf milik tab yang sudah bersih atau ditutup.
            val keep = s.tabs.filter { it.dirty }.map { "d${it.id}.txt" }.toSet()
            dir.listFiles()?.forEach { f ->
                if (f.name.startsWith("d") && f.name.endsWith(".txt") && f.name !in keep) f.delete()
            }
        } catch (e: Exception) {
            // best-effort
        }
    }
}
