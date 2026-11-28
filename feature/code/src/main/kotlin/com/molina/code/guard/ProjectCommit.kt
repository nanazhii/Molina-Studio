package com.molina.code.guard

import java.io.File
import java.security.MessageDigest

// [CODE-COMMIT] Commit manual untuk proyek bertanda `.molina`: membandingkan pohon proyek dengan indeks commit
// terakhir (`.molina/index.tsv`: sha1, ukuran, waktu ubah, path per baris), lalu mencatat berkas yang berubah
// (A/M/D) di log proyek ([ProjectLog]) dengan pesan dari pengguna. [CODE-STORE] Isi berkas yang berubah juga disimpan
// lokal sebagai blob ([ProjectStore]) sehingga +/- baris tercatat dan berkas dapat dipulihkan per catatan.
// Kotlin murni (tanpa Android): scripts/code-tests/run_projcommit.sh. Hanya proyek `.molina`, bukan repo Git.

object ProjectCommit {
    const val INDEX = "index.tsv"
    const val SRC = "commit"
    const val DEFAULT_MESSAGE = "Commit"
    private const val HEADER = "#molina-index 1"

    /** Waktu ubah berkas yang lebih baru dari (waktu commit - RACY_MS) disimpan -1 agar dihitung ulang (seperti "racy git"). */
    private const val RACY_MS = 2_000L

    class Stat(val size: Long, val mtime: Long, val sha: String)

    enum class Status { COMMITTED, NOTHING, DISABLED, TOO_BIG, FAILED }

    /** [unsaved] = jumlah berkas yang isinya tidak tersimpan (terlalu besar atau penyimpanan penuh). */
    class Result(val status: Status, val entry: ProjectLog.Entry? = null, val unsaved: Int = 0)

    /**
     * Commit [root]: bandingkan dengan indeks terakhir, catat perubahannya di log, lalu perbarui indeks. Log ditulis
     * lebih dulu: bila indeks gagal ditulis hasilnya [Status.FAILED] dan commit berikutnya mungkin mencatat ulang
     * perubahan yang sama (lebih baik ganda daripada hilang). Memblokir: panggil dari thread IO.
     * [CODE-STORE] Isi berkas disimpan lokal di `.molina/objects` dan manifest commit di `.molina/commits/<waktu>.tsv`.
     */
    fun commit(root: File, message: String, now: Long, maxFiles: Int = RunReport.MAX_FILES, keepSnapshot: Long? = null): Result =
        synchronized(ProjectStore.LOCK) {
            if (!ProjectLog.isEnabled(root)) return@synchronized Result(Status.DISABLED)
            val prev = readIndex(root)
            val cur = scan(root, prev, maxFiles)?.toMutableMap() ?: return@synchronized Result(Status.TOO_BIG)
            val cap = ProjectStore.capture(root, cur, prev) // [CODE-STORE] simpan isi + hitung +/- baris
            val changes = diff(prev, cur).map { c ->
                cap.lines[c.path]?.let { ProjectLog.FileChange(c.path, c.kind, it.first, it.second) } ?: c
            }
            if (changes.isEmpty()) return@synchronized Result(Status.NOTHING)
            val text = message.ifBlank { DEFAULT_MESSAGE }
            val time = ProjectStore.freeTime(root, now)
            val base = ProjectLog.note(time, SRC, text) // memakai pemotongan pesan milik ProjectLog
            val entry = ProjectLog.Entry(
                time, SRC, base.message,
                changes.take(ProjectLog.MAX_FILES_PER_ENTRY), maxOf(0, changes.size - ProjectLog.MAX_FILES_PER_ENTRY)
            )
            // Manifest gagal ditulis: commit tetap dicatat, hanya tidak bisa dipulihkan.
            val manifest = ProjectStore.writeManifest(root, time, cur)
            if (!ProjectLog.append(root, entry)) {
                if (manifest) File(File(File(root, ProjectMarker.DIR), ProjectStore.COMMITS), "$time.tsv").delete()
                return@synchronized Result(Status.FAILED)
            }
            val stored = cur.mapValues { (_, s) -> if (s.mtime > now - RACY_MS) Stat(s.size, -1L, s.sha) else s }
            if (!writeIndex(root, stored)) return@synchronized Result(Status.FAILED)
            ProjectStore.gc(root, keepSnapshot)
            Result(Status.COMMITTED, entry, cap.unsaved)
        }

    /**
     * Kembalikan [path] ke isinya pada commit [time] dan catat di log (sumber [ProjectStore.SRC_RESTORE]). Indeks tidak
     * disentuh, jadi berkas yang dipulihkan muncul lagi sebagai perubahan belum di-commit. Memblokir: thread IO.
     */
    fun restoreFile(root: File, time: Long, path: String, now: Long): ProjectStore.RestoreStatus =
        synchronized(ProjectStore.LOCK) {
            if (!ProjectLog.isEnabled(root)) return@synchronized ProjectStore.RestoreStatus.BAD_PATH
            val st = ProjectStore.restore(root, time, path)
            if (st == ProjectStore.RestoreStatus.OK) {
                val base = ProjectLog.note(now, ProjectStore.SRC_RESTORE, "Pulihkan $path")
                ProjectLog.append(root, ProjectLog.Entry(now, ProjectStore.SRC_RESTORE, base.message, listOf(ProjectLog.FileChange(path, 'M', 0, 0))))
            }
            st
        }

    /** Jumlah berkas yang berubah sejak commit terakhir; null bila belum pernah commit, bukan proyek, atau terlalu besar. */
    fun pending(root: File, maxFiles: Int = RunReport.MAX_FILES): Int? {
        if (!ProjectLog.isEnabled(root)) return null
        val prev = readIndex(root) ?: return null
        val cur = scan(root, prev, maxFiles) ?: return null
        return diff(prev, cur).size
    }

    /** Perbedaan [prev] -> [cur], urut path. [prev] null = semua berkas dianggap baru. */
    fun diff(prev: Map<String, Stat>?, cur: Map<String, Stat>): List<ProjectLog.FileChange> {
        val out = ArrayList<ProjectLog.FileChange>()
        for ((p, s) in cur) {
            val b = prev?.get(p)
            if (b == null) out.add(ProjectLog.FileChange(p, 'A', 0, 0))
            else if (b.sha != s.sha) out.add(ProjectLog.FileChange(p, 'M', 0, 0))
        }
        if (prev != null) for (p in prev.keys) if (p !in cur) out.add(ProjectLog.FileChange(p, 'D', 0, 0))
        out.sortBy { it.path }
        return out
    }

    /**
     * Pindai [root]: path relatif ('/') -> ukuran, waktu ubah, sha1. Sidik jari [prev] dipakai ulang bila ukuran dan
     * waktu ubah sama (tanpa membaca isi). Null bila berkas melebihi [maxFiles]. Tautan simbolik dan folder di
     * [RunReport.SKIP_DIRS] (termasuk `.molina` dan `.git`) dilewati.
     */
    fun scan(root: File, prev: Map<String, Stat>?, maxFiles: Int = RunReport.MAX_FILES): Map<String, Stat>? {
        val out = HashMap<String, Stat>()
        val stack = ArrayDeque<File>()
        stack.add(root)
        val prefix = root.path.trimEnd('/').length + 1
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val kids = dir.listFiles() ?: continue
            for (f in kids) {
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
                if (f.isDirectory) {
                    if (f.name !in RunReport.SKIP_DIRS) stack.add(f)
                    continue
                }
                if (!f.isFile || f.name.endsWith(".pyc")) continue
                if (out.size >= maxFiles) return null
                val rel = f.path.substring(prefix).replace(File.separatorChar, '/')
                val size = f.length()
                val mtime = f.lastModified()
                val old = prev?.get(rel)
                val sha = if (old != null && old.size == size && old.mtime == mtime) old.sha else sha1(f) ?: continue
                out[rel] = Stat(size, mtime, sha)
            }
        }
        return out
    }

    /** Indeks commit terakhir, atau null bila belum ada/tidak terbaca. Baris rusak dilewati. */
    fun readIndex(root: File): Map<String, Stat>? {
        val f = File(File(root, ProjectMarker.DIR), INDEX)
        if (!f.isFile) return null
        return try {
            val out = HashMap<String, Stat>()
            for (line in f.readLines(Charsets.UTF_8)) {
                if (line.isEmpty() || line.startsWith("#")) continue
                val p = line.split('\t', limit = 4)
                if (p.size != 4 || p[0].length != 40 || !p[0].all { it in '0'..'9' || it in 'a'..'f' }) continue
                val size = p[1].toLongOrNull() ?: continue
                val mtime = p[2].toLongOrNull() ?: continue
                val path = unesc(p[3]) ?: continue
                out[path] = Stat(size, mtime, p[0])
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    /** Tulis indeks secara atomik (berkas sementara lalu rename). */
    fun writeIndex(root: File, files: Map<String, Stat>): Boolean {
        val dir = File(root, ProjectMarker.DIR)
        val f = File(dir, INDEX)
        val tmp = File(dir, "$INDEX.tmp")
        return try {
            val sb = StringBuilder(HEADER).append('\n')
            for (p in files.keys.sorted()) {
                val s = files.getValue(p)
                sb.append(s.sha).append('\t').append(s.size).append('\t').append(s.mtime).append('\t').append(esc(p)).append('\n')
            }
            tmp.writeText(sb.toString(), Charsets.UTF_8)
            if (tmp.renameTo(f)) true else {
                f.delete()
                if (tmp.renameTo(f)) true else { tmp.delete(); false }
            }
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    private fun sha1(f: File): String? = try {
        val md = MessageDigest.getInstance("SHA-1")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }

    internal fun esc(s: String): String {
        val sb = StringBuilder()
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '\t' -> sb.append("\\t")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    /** Kebalikan [esc]; null bila urutan escape tidak dikenal. */
    internal fun unesc(s: String): String? {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\') { sb.append(c); i++; continue }
            when (s.getOrNull(i + 1)) {
                '\\' -> sb.append('\\')
                't' -> sb.append('\t')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                else -> return null
            }
            i += 2
        }
        return sb.toString()
    }
}
