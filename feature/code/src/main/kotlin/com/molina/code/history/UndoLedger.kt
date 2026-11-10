package com.molina.code.history

import java.io.File

// [EXT-DIFF5] Buku catatan pemulihan yang tahan restart: untuk tiap berkas yang dipulihkan dari feed, simpan sidik jari
// isi sebelum dipulihkan (null = berkas belum ada) dan isi yang ditulis. Isi aslinya tetap di Riwayat (dicari lewat sidik jari).
// Kotlin murni agar bisa diuji di JVM: scripts/code-tests/run_undoledger.sh. Format: satu baris `jalur<TAB>sebelum<TAB>sesudah`.
class UndoLedger(private val file: File) {
    class Entry(val prevFp: String?, val writtenFp: String)

    @Synchronized
    fun get(path: String): Entry? = load()[path]

    @Synchronized
    fun put(path: String, prevFp: String?, writtenFp: String) {
        if (!valid(path) || writtenFp.isEmpty()) return
        val m = load()
        m.remove(path)
        m[path] = Entry(prevFp, writtenFp)
        while (m.size > MAX_ENTRIES) m.remove(m.keys.first())
        save(m)
    }

    @Synchronized
    fun remove(path: String) {
        val m = load()
        if (m.remove(path) != null) save(m)
    }

    private fun load(): LinkedHashMap<String, Entry> {
        val m = LinkedHashMap<String, Entry>()
        try {
            if (!file.isFile) return m
            for (line in file.readLines(Charsets.UTF_8)) {
                val p = line.split('\t')
                if (p.size != 3 || p[0].isEmpty() || p[2].isEmpty()) continue
                m[p[0]] = Entry(if (p[1] == NONE) null else p[1], p[2])
            }
        } catch (e: Exception) {
            // best-effort
        }
        return m
    }

    private fun save(m: Map<String, Entry>) {
        try {
            if (m.isEmpty()) { file.delete(); return }
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(m.entries.joinToString("\n", postfix = "\n") { "${it.key}\t${it.value.prevFp ?: NONE}\t${it.value.writtenFp}" }, Charsets.UTF_8)
            if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) tmp.delete() }
        } catch (e: Exception) {
            // best-effort
        }
    }

    companion object {
        const val MAX_ENTRIES = 200
        private const val NONE = "-"
        fun valid(path: String): Boolean = path.isNotEmpty() && '\t' !in path && '\n' !in path && '\r' !in path
    }
}
