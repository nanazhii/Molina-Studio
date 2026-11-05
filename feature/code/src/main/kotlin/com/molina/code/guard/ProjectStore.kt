package com.molina.code.guard

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

// [CODE-STORE] Penyimpanan isi berkas untuk commit proyek `.molina`, 100% lokal di perangkat (tanpa jaringan).
// Meniru pola Git: isi berkas disimpan sebagai blob gzip berdasarkan sha1 isinya (`.molina/objects/<sha>.gz`,
// tanpa duplikat antar-commit) dan tiap commit punya manifest path -> sha (`.molina/commits/<waktu>.tsv`).
// Dengan itu berkas dapat dilihat/dipulihkan per catatan. Kotlin murni (tanpa Android):
// scripts/code-tests/run_projstore.sh. Semua operasi best-effort: galat I/O tidak dilempar.

object ProjectStore {
    const val OBJECTS = "objects"
    const val COMMITS = "commits"
    const val SRC_RESTORE = "restore"

    /** Berkas lebih besar dari ini tidak disimpan isinya (tetap tercatat di log, tetapi tidak bisa dipulihkan). */
    const val MAX_BLOB_FILE = RunReport.MAX_BACKUP_FILE
    /** Batas total ukuran blob terkompresi di `.molina/objects`. */
    const val MAX_STORE_BYTES = 64L * 1024 * 1024
    /** Jumlah manifest (commit yang bisa dipulihkan) yang dipertahankan; yang lebih lama dibuang saat [gc]. */
    const val MAX_SNAPSHOTS = 30
    const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024

    private const val HEADER = "#molina-commit 1"
    private const val TMP = ".tmp"

    /** Semua operasi tulis (commit, gc) memakai kunci ini agar gc tidak menghapus blob yang sedang dipakai commit. */
    val LOCK = Any()

    enum class RestoreStatus { OK, NO_SNAPSHOT, NOT_IN_SNAPSHOT, NO_BLOB, BAD_PATH, FAILED }

    /** Hasil [capture]: jumlah +/- baris per path yang berubah, dan jumlah berkas yang isinya tidak tersimpan. */
    class Capture(val lines: Map<String, Pair<Int, Int>>, val unsaved: Int)

    // ------------------------------------------------------------------ blob

    private fun validSha(s: String): Boolean = s.length == 40 && s.all { it in '0'..'9' || it in 'a'..'f' }

    private fun objectsDir(root: File) = File(File(root, ProjectMarker.DIR), OBJECTS)
    private fun commitsDir(root: File) = File(File(root, ProjectMarker.DIR), COMMITS)
    private fun objectFile(root: File, sha: String) = File(objectsDir(root), "$sha.gz")

    fun sha1Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    fun has(root: File, sha: String): Boolean = validSha(sha) && objectFile(root, sha).isFile

    /**
     * Simpan [bytes] sebagai blob [sha] (harus sha1 isinya). Hasil: ukuran tersimpan (>0), 0 bila sudah ada,
     * -1 bila gagal, -2 bila melebihi [budget] (sisa jatah byte di toko).
     */
    fun put(root: File, sha: String, bytes: ByteArray, budget: Long): Long {
        if (!validSha(sha)) return -1
        val f = objectFile(root, sha)
        if (f.isFile) return 0
        val dir = objectsDir(root)
        val tmp = File(dir, "$sha$TMP")
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) return -1
            val bos = ByteArrayOutputStream(bytes.size / 2 + 64)
            GZIPOutputStream(bos).use { it.write(bytes) }
            val z = bos.toByteArray()
            if (z.size > budget) return -2
            tmp.writeBytes(z)
            if (!tmp.renameTo(f)) { tmp.delete(); return -1 }
            z.size.toLong()
        } catch (e: Exception) {
            tmp.delete()
            -1
        }
    }

    /** Isi blob [sha], atau null bila tidak ada/rusak (isi yang terbaca harus cocok dengan sha-nya). */
    fun get(root: File, sha: String): ByteArray? {
        if (!validSha(sha)) return null
        val f = objectFile(root, sha)
        if (!f.isFile) return null
        return try {
            val b = GZIPInputStream(f.inputStream()).use { it.readBytes() }
            if (sha1Hex(b) == sha) b else null
        } catch (e: Exception) {
            null
        }
    }

    /** Total ukuran blob di disk (byte). */
    fun storeSize(root: File): Long =
        objectsDir(root).listFiles()?.filter { it.isFile && it.name.endsWith(".gz") }?.sumOf { it.length() } ?: 0L

    // ------------------------------------------------------------------ manifest

    private fun manifestFile(root: File, time: Long) = File(commitsDir(root), "$time.tsv")

    fun hasSnapshot(root: File, time: Long): Boolean = manifestFile(root, time).isFile

    /** Waktu semua manifest yang ada (commit yang bisa dipulihkan). */
    fun snapshotTimes(root: File): Set<Long> =
        commitsDir(root).listFiles()?.mapNotNull { f ->
            if (f.isFile && f.name.endsWith(".tsv")) f.name.removeSuffix(".tsv").toLongOrNull() else null
        }?.toSet() ?: emptySet()

    /** Manifest terbaru yang lebih lama dari [time] di [snapshots], atau null (commit tertua yang tersisa). */
    fun previousOf(snapshots: Set<Long>, time: Long): Long? = snapshots.filter { it < time }.maxOrNull()

    fun previousSnapshot(root: File, time: Long): Long? = previousOf(snapshotTimes(root), time)

    /** Waktu terkecil >= [t] yang belum dipakai manifest (dua commit dalam milidetik yang sama). */
    fun freeTime(root: File, t: Long): Long {
        var x = t
        while (manifestFile(root, x).exists()) x++
        return x
    }

    /** Tulis manifest commit [time]: sha, ukuran, path tiap berkas. Atomik. */
    fun writeManifest(root: File, time: Long, files: Map<String, ProjectCommit.Stat>): Boolean {
        val dir = commitsDir(root)
        val f = manifestFile(root, time)
        val tmp = File(dir, "$time.tsv$TMP")
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val sb = StringBuilder(HEADER).append('\n')
            for (p in files.keys.sorted()) {
                val s = files.getValue(p)
                sb.append(s.sha).append('\t').append(s.size).append('\t').append(ProjectCommit.esc(p)).append('\n')
            }
            tmp.writeText(sb.toString(), Charsets.UTF_8)
            if (tmp.renameTo(f)) true else { tmp.delete(); false }
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    /** Manifest commit [time] (path -> Stat dengan mtime 0), atau null bila tidak ada/tidak terbaca. */
    fun readManifest(root: File, time: Long): Map<String, ProjectCommit.Stat>? {
        val f = manifestFile(root, time)
        if (!f.isFile) return null
        return try {
            val out = HashMap<String, ProjectCommit.Stat>()
            for (line in f.readLines(Charsets.UTF_8)) {
                if (line.isEmpty() || line.startsWith("#")) continue
                val p = line.split('\t', limit = 3)
                if (p.size != 3 || !validSha(p[0])) continue
                val size = p[1].toLongOrNull() ?: continue
                val path = ProjectCommit.unesc(p[2]) ?: continue
                out[path] = ProjectCommit.Stat(size, 0L, p[0])
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ capture (dipanggil saat commit)

    /**
     * Simpan isi berkas di [cur] yang belum ada blob-nya (<= [MAX_BLOB_FILE]) dan hitung +/- baris untuk berkas yang
     * berubah terhadap [prev] (A, M, D). Bila isi yang terbaca beda dari sha hasil pindai (berkas berubah di tengah
     * commit), entri [cur] diperbarui (mtime -1 = dihitung ulang). Memblokir: panggil dari thread IO.
     */
    fun capture(root: File, cur: MutableMap<String, ProjectCommit.Stat>, prev: Map<String, ProjectCommit.Stat>?): Capture {
        var total = storeSize(root)
        var unsaved = 0
        val lines = HashMap<String, Pair<Int, Int>>()
        for (path in cur.keys.toList()) {
            var st = cur.getValue(path)
            val before = prev?.get(path)
            if (before?.sha == st.sha && has(root, st.sha)) continue
            if (st.size > MAX_BLOB_FILE) {
                if (!has(root, st.sha)) unsaved++
                continue
            }
            val bytes = try { File(root, path).readBytes() } catch (e: Exception) { null }
            if (bytes == null || bytes.size > MAX_BLOB_FILE) { unsaved++; continue }
            val sha = sha1Hex(bytes)
            if (sha != st.sha) { st = ProjectCommit.Stat(bytes.size.toLong(), -1L, sha); cur[path] = st }
            val r = put(root, sha, bytes, MAX_STORE_BYTES - total)
            if (r > 0) total += r else if (r < 0) unsaved++
            if (prev?.get(path)?.sha != sha) lines[path] = countLines(root, prev?.get(path), bytes)
        }
        if (prev != null) for ((path, old) in prev) {
            if (path in cur) continue
            val b = get(root, old.sha) ?: continue
            if (isText(b)) lines[path] = 0 to lineCount(String(b, Charsets.UTF_8))
        }
        return Capture(lines, unsaved)
    }

    private fun countLines(root: File, old: ProjectCommit.Stat?, now: ByteArray): Pair<Int, Int> {
        if (!isText(now)) return 0 to 0
        val newText = String(now, Charsets.UTF_8)
        if (old == null) return lineCount(newText) to 0
        val ob = get(root, old.sha) ?: return 0 to 0
        if (!isText(ob)) return 0 to 0
        val r = com.molina.code.history.LineDiff.diff(String(ob, Charsets.UTF_8), newText)
        return r.added to r.removed
    }

    private fun lineCount(s: String): Int =
        if (s.isEmpty()) 0 else s.count { it == '\n' } + if (s.endsWith('\n')) 0 else 1

    /** Teks UTF-8 yang sah tanpa byte NUL (selain itu dianggap biner: tanpa hitungan baris). */
    fun isText(b: ByteArray): Boolean {
        if (b.any { it == 0.toByte() }) return false
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(b))
            true
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ diff per catatan

    enum class DiffStatus { OK, BINARY, NO_BASE, NO_BLOB }

    /** [result] terisi bila [status] = OK. */
    class FileDiff(val status: DiffStatus, val result: com.molina.code.history.LineDiff.Result? = null)

    /**
     * Perubahan [path] pada commit [time] terhadap commit sebelumnya (manifest terdekat yang lebih lama). [kind] dari
     * log: 'A' dibanding kosong, 'D' dibanding kosong dari sisi baru, 'M' dibanding versi sebelumnya. NO_BASE bila
     * versi sebelumnya sudah dibuang, NO_BLOB bila isi salah satu sisi tidak tersimpan, BINARY bila bukan teks.
     * Memblokir: panggil dari thread IO.
     */
    fun diffAt(root: File, time: Long, path: String, kind: Char): FileDiff {
        if (!safePath(path)) return FileDiff(DiffStatus.NO_BLOB)
        val empty = ByteArray(0)
        val prevT = previousSnapshot(root, time)
        val old: ByteArray
        val new: ByteArray
        when (kind) {
            'A' -> {
                old = empty
                new = contentAt(root, time, path) ?: return FileDiff(DiffStatus.NO_BLOB)
            }
            'D' -> {
                new = empty
                if (prevT == null) return FileDiff(DiffStatus.NO_BASE)
                old = contentAt(root, prevT, path) ?: return FileDiff(DiffStatus.NO_BLOB)
            }
            else -> {
                if (prevT == null) return FileDiff(DiffStatus.NO_BASE)
                new = contentAt(root, time, path) ?: return FileDiff(DiffStatus.NO_BLOB)
                old = contentAt(root, prevT, path) ?: return FileDiff(DiffStatus.NO_BLOB)
            }
        }
        if (!isText(old) || !isText(new)) return FileDiff(DiffStatus.BINARY)
        return FileDiff(DiffStatus.OK, com.molina.code.history.LineDiff.diff(String(old, Charsets.UTF_8), String(new, Charsets.UTF_8)))
    }

    // ------------------------------------------------------------------ gc

    /**
     * Buang manifest lama (> [MAX_SNAPSHOTS] atau melewati [MAX_MANIFEST_BYTES]; yang terbaru selalu dipertahankan),
     * berkas sementara, dan blob yang tidak dirujuk manifest tersisa. Bila ada manifest tersisa yang tak terbaca,
     * blob tidak disentuh (lebih baik boros daripada kehilangan).
     */
    fun gc(root: File) {
        synchronized(LOCK) {
            try {
                val cd = commitsDir(root)
                val all = cd.listFiles()?.toList() ?: emptyList()
                all.filter { it.name.endsWith(TMP) }.forEach { it.delete() }
                val mans = all.filter { it.isFile && it.name.endsWith(".tsv") && it.name.removeSuffix(".tsv").toLongOrNull() != null }
                    .sortedByDescending { it.name.removeSuffix(".tsv").toLong() }
                var bytes = 0L
                val kept = ArrayList<File>()
                for ((i, f) in mans.withIndex()) {
                    bytes += f.length()
                    if (i >= MAX_SNAPSHOTS || (i > 0 && bytes > MAX_MANIFEST_BYTES)) f.delete() else kept.add(f)
                }
                val refs = HashSet<String>()
                for (f in kept) {
                    val m = readManifest(root, f.name.removeSuffix(".tsv").toLong()) ?: return
                    for (s in m.values) refs.add(s.sha)
                }
                for (f in objectsDir(root).listFiles() ?: return) {
                    val n = f.name
                    if (n.endsWith(TMP) || !n.endsWith(".gz") || n.removeSuffix(".gz") !in refs) f.delete()
                }
            } catch (e: Exception) {
                // best-effort
            }
        }
    }

    // ------------------------------------------------------------------ restore

    /** Path relatif yang aman: bukan mutlak, tanpa `..`, dan tidak melewati folder terlarang ([RunReport.SKIP_DIRS]). */
    fun safePath(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/") || path.contains('\u0000') || path.contains('\\')) return false
        val parts = path.split('/')
        return parts.none { it.isEmpty() || it == "." || it == ".." || it in RunReport.SKIP_DIRS }
    }

    /** Isi [path] pada commit [time] (untuk pratinjau), atau null bila tidak tersedia. */
    fun contentAt(root: File, time: Long, path: String): ByteArray? {
        val sha = readManifest(root, time)?.get(path)?.sha ?: return null
        return get(root, sha)
    }

    /**
     * Kembalikan [path] ke isinya pada commit [time]: ditulis atomik (berkas sementara lalu rename) dan tidak
     * pernah menulis lewat tautan simbolik atau keluar dari [root]. Memblokir: panggil dari thread IO.
     */
    fun restore(root: File, time: Long, path: String): RestoreStatus {
        if (!safePath(path)) return RestoreStatus.BAD_PATH
        val man = readManifest(root, time) ?: return RestoreStatus.NO_SNAPSHOT
        val st = man[path] ?: return RestoreStatus.NOT_IN_SNAPSHOT
        val bytes = get(root, st.sha) ?: return RestoreStatus.NO_BLOB
        return try {
            val target = File(root, path)
            val parent = target.parentFile ?: return RestoreStatus.BAD_PATH
            val rootCanon = root.canonicalFile
            if (java.nio.file.Files.isSymbolicLink(target.toPath()) || target.isDirectory) return RestoreStatus.BAD_PATH
            if (!parent.exists() && !parent.mkdirs()) return RestoreStatus.FAILED
            val pc = parent.canonicalFile
            if (pc != rootCanon && !pc.path.startsWith(rootCanon.path + File.separator)) return RestoreStatus.BAD_PATH
            val tmp = File(parent, ".molina-restore$TMP")
            try {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) { tmp.delete(); return RestoreStatus.FAILED } }
            } catch (e: Exception) {
                tmp.delete()
                return RestoreStatus.FAILED
            }
            RestoreStatus.OK
        } catch (e: Exception) {
            RestoreStatus.FAILED
        }
    }
}
