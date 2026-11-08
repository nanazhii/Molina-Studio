package com.molina.code.run

import com.molina.code.preview.ScriptLaunch
import java.io.File

/** Berkas skrip hasil pindai folder Scripts. [body] null = tidak berubah sejak pindai terakhir (cap sama). */
class ScanFile(val path: String, val name: String, val body: String?, val modified: Long)

/** Cap waktu ubah + ukuran berkas; dipakai agar berkas yang tidak berubah tidak dibaca ulang. */
class Stamp(val modified: Long, val size: Long)

/** Hasil pindai. [skipped] = berkas skrip yang dilewati (terlalu besar, biner, atau melebihi batas baris). */
class ScanResult(val files: List<ScanFile>, val stamps: Map<String, Stamp>, val skipped: Int)

/** Entri Script Execution yang sudah ada (dipetakan dari ScriptItem di :app). */
class KnownScript(val id: String, val path: String?, val sourceKey: String?, val title: String, val body: String)

/**
 * Rencana penyamaan daftar dengan folder.
 * [kept] path -> id entri yang berkasnya masih ada; [added] berkas baru tanpa entri;
 * [removed] id entri yang berkasnya hilang; [linked] id entri lama (tanpa berkas) -> path berkas yang ditautkan;
 * [exported] id entri lama yang harus ditulis jadi berkas baru.
 */
class FolderPlan(
    val kept: Map<String, String>,
    val added: List<ScanFile>,
    val removed: List<String>,
    val linked: Map<String, String>,
    val exported: List<String>
)

/**
 * Aturan murni (tanpa Android) agar daftar Script Execution mengikuti isi folder Molina/Scripts:
 * nama dan isi entri = nama dan isi berkas. Diuji di JVM (scripts/code-tests/run_script_folder.sh).
 */
object ScriptFolder {
    val EXTENSIONS = setOf("sh", "bash", "py")
    const val MAX_FILE_BYTES = 1_000_000L
    const val MAX_BODY_CHARS = 300_000

    /** Berkas skrip: ekstensi .sh/.bash/.py, bukan berkas tersembunyi (awalan titik), nama wajar. */
    fun isScriptName(name: String): Boolean {
        if (name.startsWith(".") || name.length > 120) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in EXTENSIONS && name.length > ext.length + 1
    }

    /** Judul -> nama berkas aman: karakter terlarang jadi `_`, ekstensi dilengkapi [fallbackExt] bila belum .sh/.bash/.py. */
    fun safeFileName(title: String, fallbackExt: String): String {
        val cleaned = title.trim()
            .map { c -> if (c < ' ' || c in "\\/:*?\"<>|") '_' else c }
            .joinToString("").trim().trim('.')
        val base = cleaned.ifEmpty { "skrip" }.take(80)
        return if (isScriptName(base)) base else "$base.$fallbackExt"
    }

    /** Nama yang belum dipakai: `a.sh` -> `a-2.sh`, `a-3.sh`, ... [isTaken] menentukan nama yang sudah ada. */
    fun uniqueName(name: String, isTaken: (String) -> Boolean): String {
        if (!isTaken(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (true) {
            val candidate = "$stem-$n$ext"
            if (!isTaken(candidate)) return candidate
            n++
        }
    }

    /**
     * Pindai [dir] (tanpa subfolder). null = folder tidak bisa dibaca (jangan diperlakukan sebagai kosong).
     * Berkas yang capnya sama dengan [known] tidak dibaca ulang (body = null).
     */
    fun scan(dir: File, known: Map<String, Stamp>): ScanResult? {
        val listing = dir.listFiles() ?: return null
        val files = ArrayList<ScanFile>()
        val stamps = HashMap<String, Stamp>()
        var skipped = 0
        for (f in listing.sortedBy { it.name.lowercase() }) {
            if (!f.isFile || !isScriptName(f.name)) continue
            val stamp = Stamp(f.lastModified(), f.length())
            if (stamp.size > MAX_FILE_BYTES) { skipped++; continue }
            val old = known[f.path]
            if (old != null && old.modified == stamp.modified && old.size == stamp.size) {
                files.add(ScanFile(f.path, f.name, null, stamp.modified))
                stamps[f.path] = stamp
                continue
            }
            val body = readBody(f)
            if (body == null) { skipped++; continue }
            files.add(ScanFile(f.path, f.name, body, stamp.modified))
            stamps[f.path] = stamp
        }
        return ScanResult(files, stamps, skipped)
    }

    private fun readBody(f: File): String? {
        val bytes = try { f.readBytes() } catch (e: Exception) { return null }
        if (bytes.any { it == 0.toByte() }) return null
        val text = ScriptLaunch.normalizeEol(String(bytes, Charsets.UTF_8))
        if (text.length > MAX_BODY_CHARS || ScriptLaunch.countLines(text) > ScriptLaunch.MAX_LINES) return null
        return text
    }

    /**
     * Cocokkan entri yang ada dengan hasil pindai. [folderPrefix] harus berakhir `/`.
     * Entri berpath yang berkasnya hilang dibuang; entri lama (tanpa path) ditautkan ke berkas yang sama
     * (path = sourceKey, atau nama + isi identik) atau diekspor jadi berkas baru, jadi tidak ada skrip yang hilang.
     */
    fun reconcile(existing: List<KnownScript>, scanned: List<ScanFile>, folderPrefix: String): FolderPlan {
        val byPath = scanned.associateBy { it.path }
        val kept = LinkedHashMap<String, String>()
        val removed = ArrayList<String>()
        val legacy = ArrayList<KnownScript>()
        for (e in existing) {
            val p = e.path
            when {
                p == null -> legacy.add(e)
                p in byPath -> if (p in kept) removed.add(e.id) else kept[p] = e.id
                p.startsWith(folderPrefix) -> removed.add(e.id)
                else -> legacy.add(e)
            }
        }
        val free = scanned.filter { it.path !in kept }.toMutableList()
        val linked = LinkedHashMap<String, String>()
        val exported = ArrayList<String>()
        for (e in legacy) {
            val key = e.sourceKey
            val hit = free.firstOrNull { key != null && it.path == key }
                ?: free.firstOrNull { it.name == e.title && it.body != null && it.body == e.body }
            if (hit != null) {
                linked[e.id] = hit.path
                free.remove(hit)
            } else {
                exported.add(e.id)
            }
        }
        return FolderPlan(kept, free, removed, linked, exported)
    }
}
