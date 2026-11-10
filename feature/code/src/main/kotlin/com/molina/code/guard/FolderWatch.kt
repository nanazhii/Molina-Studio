package com.molina.code.guard

import java.io.File
import java.security.MessageDigest

// [CODE-FEED4] Sidik jari folder untuk mendeteksi perubahan di luar berkas yang terbuka di tab (git pull, edit lewat Terminal,
// aplikasi lain) saat layar Code tidak aktif. Hanya metadata (ukuran + waktu ubah), tanpa isi berkas. Baseline disimpan saat
// Code ditinggalkan, dibandingkan saat Code dibuka lagi. Berkas privat aplikasi: satu berkas per folder, baris pertama
// `FW1<TAB>waktu<TAB>folder`, lalu `ukuran<TAB>mtime<TAB>jalur-relatif`. Kotlin murni: scripts/code-tests/run_folderwatch.sh.
object FolderWatch {
    private const val TAG = "FW1"
    private const val MTIME_SLACK_MS = 2_000L
    const val MESSAGE = "Folder berubah saat Code tidak aktif"

    class Baseline(val time: Long, val root: String, val meta: Map<String, RunReport.Meta>)

    /** Metadata semua berkas di [root] (jalur relatif, '/'). Null bila berkas melebihi [RunReport.MAX_FILES]. Memblokir. */
    fun capture(root: File): Map<String, RunReport.Meta>? {
        val meta = HashMap<String, RunReport.Meta>()
        val stack = ArrayDeque<File>()
        stack.add(root)
        val prefix = root.path.trimEnd('/').length + 1
        while (stack.isNotEmpty()) {
            val kids = stack.removeLast().listFiles() ?: continue
            for (f in kids) {
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
                if (f.isDirectory) {
                    if (f.name !in RunReport.SKIP_DIRS) stack.add(f)
                    continue
                }
                if (!f.isFile || f.name.endsWith(".pyc")) continue
                if (meta.size >= RunReport.MAX_FILES) return null
                val rel = f.path.substring(prefix).replace(File.separatorChar, '/')
                if (rel.any { it == '\t' || it == '\n' || it == '\r' }) continue
                meta[rel] = RunReport.Meta(f.length(), f.lastModified())
            }
        }
        return meta
    }

    /** Perbedaan [old] -> [cur], urut menurut jalur. Diubah = ukuran beda atau waktu ubah beda lebih dari 2 detik. */
    fun diff(old: Map<String, RunReport.Meta>, cur: Map<String, RunReport.Meta>): List<ProjectLog.FileChange> {
        val out = ArrayList<ProjectLog.FileChange>()
        for ((p, m) in cur) {
            val o = old[p]
            if (o == null) out.add(ProjectLog.FileChange(p, 'A', 0, 0))
            else if (o.size != m.size || Math.abs(o.mtime - m.mtime) > MTIME_SLACK_MS) out.add(ProjectLog.FileChange(p, 'M', 0, 0))
        }
        for (p in old.keys) if (p !in cur) out.add(ProjectLog.FileChange(p, 'D', 0, 0))
        return out.sortedBy { it.path }
    }

    /** Butir feed untuk [changes] (tak boleh kosong). Daftar dipotong ke [ProjectLog.MAX_FILES_PER_ENTRY]. */
    fun entry(changes: List<ProjectLog.FileChange>, time: Long): ProjectLog.Entry =
        ProjectLog.Entry(time, ChangeFeed.SRC_EXTERNAL, MESSAGE, changes.take(ProjectLog.MAX_FILES_PER_ENTRY),
            maxOf(0, changes.size - ProjectLog.MAX_FILES_PER_ENTRY))

    /** Berkas baseline untuk [root] di dalam [dir] (nama dari hash jalur). */
    fun keyFile(dir: File, root: File): File {
        val h = MessageDigest.getInstance("SHA-1").digest(root.path.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return File(dir, h.take(12) + ".tsv")
    }

    /** Baseline tersimpan; null bila belum ada, rusak, atau untuk folder lain. Baris rusak dilewati. */
    fun load(file: File, root: File): Baseline? {
        if (!file.isFile) return null
        return try {
            val lines = file.readLines(Charsets.UTF_8)
            val head = lines.firstOrNull()?.split('\t', limit = 3) ?: return null
            if (head.size != 3 || head[0] != TAG || head[2] != root.path) return null
            val time = head[1].toLongOrNull() ?: return null
            val meta = HashMap<String, RunReport.Meta>()
            for (i in 1 until lines.size) {
                val p = lines[i].split('\t', limit = 3)
                if (p.size != 3 || p[2].isEmpty()) continue
                val size = p[0].toLongOrNull() ?: continue
                val mt = p[1].toLongOrNull() ?: continue
                meta[p[2]] = RunReport.Meta(size, mt)
            }
            Baseline(time, head[2], meta)
        } catch (e: Exception) {
            null
        }
    }

    /** Simpan baseline (tulis atomik). False bila gagal; tak pernah melempar. */
    fun save(file: File, b: Baseline): Boolean {
        if (b.root.any { it == '\t' || it == '\n' || it == '\r' }) return false
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            file.parentFile?.mkdirs()
            val sb = StringBuilder()
            sb.append(TAG).append('\t').append(b.time).append('\t').append(b.root).append('\n')
            for ((p, m) in b.meta) sb.append(m.size).append('\t').append(m.mtime).append('\t').append(p).append('\n')
            tmp.writeText(sb.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) { tmp.delete(); return false } }
            true
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }
}
