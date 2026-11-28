package com.molina.code.guard

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

// [CODE-PROJLOG] Riwayat per proyek (commit lokal) untuk folder proyek bertanda `.molina`. Satu entri = satu
// peristiwa (mis. satu kali skrip jalan) berisi pesan dan daftar berkas yang berubah. Disimpan append-only di
// `.molina/log.jsonl` (satu entri JSON per baris, sehingga satu baris rusak tidak merusak yang lain).
// Kotlin murni (tanpa Android): scripts/code-tests/run_projlog.sh. Tidak pernah membuat `.molina` sendiri dan
// tidak pernah menulis ke repo Git (Git punya riwayatnya sendiri).

object ProjectLog {
    const val FILE = "log.jsonl"
    const val FORMAT = 1
    const val MAX_ENTRIES = 500
    const val MAX_FILES_PER_ENTRY = 200
    const val MAX_MESSAGE = 200
    private const val PRUNE_SLACK = 50

    const val SRC_SCRIPT = "script"
    const val SRC_UNDO = "undo"

    /** [kind]: 'A' ditambah, 'M' diubah, 'D' dihapus. */
    class FileChange(val path: String, val kind: Char, val added: Int, val removed: Int)

    /** [omitted] = jumlah berkas yang tidak dimuat di [files] karena melewati [MAX_FILES_PER_ENTRY]. */
    class Entry(val time: Long, val source: String, val message: String, val files: List<FileChange>, val omitted: Int = 0) {
        val fileCount: Int get() = files.size + omitted
        val addedFiles: Int get() = files.count { it.kind == 'A' }
        val modifiedFiles: Int get() = files.count { it.kind == 'M' }
        val deletedFiles: Int get() = files.count { it.kind == 'D' }
    }

    /** Entri dari hasil perbandingan [RunReport]. Pesan dipotong ke [MAX_MESSAGE]. */
    fun fromReport(rep: RunReport.Report, time: Long, source: String, message: String): Entry {
        val all = rep.changes.map {
            FileChange(
                it.path,
                when (it.kind) {
                    RunReport.Kind.ADDED -> 'A'
                    RunReport.Kind.MODIFIED -> 'M'
                    RunReport.Kind.DELETED -> 'D'
                },
                it.added, it.removed
            )
        }
        return Entry(time, source, clip(message), all.take(MAX_FILES_PER_ENTRY), maxOf(0, all.size - MAX_FILES_PER_ENTRY))
    }

    /** Entri tanpa daftar berkas (mis. catatan Urungkan). */
    fun note(time: Long, source: String, message: String): Entry = Entry(time, source, clip(message), emptyList())

    /** True bila [root] folder proyek bertanda `.molina` yang sudah ada (tidak dibuat di sini). */
    fun isEnabled(root: File): Boolean = File(root, ProjectMarker.DIR).isDirectory && !File(root, ".git").exists()

    /**
     * Tambahkan [e] ke log [root]. False bila [root] bukan proyek `.molina` atau penulisan gagal. Best-effort: galat
     * I/O tidak pernah dilempar. Memblokir: panggil dari thread IO.
     */
    @Synchronized
    fun append(root: File, e: Entry): Boolean {
        if (!isEnabled(root)) return false
        val f = File(File(root, ProjectMarker.DIR), FILE)
        return try {
            val needNl = f.isFile && f.length() > 0 && lastByte(f) != '\n'.code
            FileOutputStream(f, true).use { out ->
                val s = (if (needNl) "\n" else "") + encode(e) + "\n"
                out.write(s.toByteArray(Charsets.UTF_8))
            }
            prune(f)
            true
        } catch (ex: Exception) {
            false
        }
    }

    /** Semua entri [root], terbaru dulu. Baris rusak dilewati. Kosong bila belum ada log. */
    @Synchronized
    fun read(root: File): List<Entry> {
        val f = File(File(root, ProjectMarker.DIR), FILE)
        if (!f.isFile) return emptyList()
        return try {
            f.readLines(Charsets.UTF_8).mapNotNull { decode(it) }.asReversed()
        } catch (ex: Exception) {
            emptyList()
        }
    }

    private fun prune(f: File) {
        val lines = f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        if (lines.size <= MAX_ENTRIES + PRUNE_SLACK) return
        val keep = lines.takeLast(MAX_ENTRIES)
        val tmp = File(f.parentFile, "$FILE.tmp")
        try {
            tmp.writeText(keep.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) tmp.delete() }
        } catch (ex: Exception) {
            tmp.delete()
        }
    }

    private fun lastByte(f: File): Int = RandomAccessFile(f, "r").use { r -> r.seek(r.length() - 1); r.read() }

    private fun clip(s: String): String {
        val one = s.replace('\n', ' ').replace('\r', ' ').trim()
        return if (one.length <= MAX_MESSAGE) one else one.substring(0, MAX_MESSAGE - 1) + "\u2026"
    }

    // ------------------------------------------------------------------ JSON

    fun encode(e: Entry): String {
        val sb = StringBuilder()
        sb.append("{\"v\":").append(FORMAT)
        sb.append(",\"t\":").append(e.time)
        sb.append(",\"s\":\"").append(esc(e.source)).append('"')
        sb.append(",\"m\":\"").append(esc(e.message)).append('"')
        sb.append(",\"n\":").append(e.omitted)
        sb.append(",\"f\":[")
        e.files.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            sb.append("{\"p\":\"").append(esc(c.path)).append("\",\"k\":\"").append(c.kind)
                .append("\",\"a\":").append(c.added).append(",\"r\":").append(c.removed).append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Null bila [line] kosong, rusak, atau bukan entri yang dikenal. */
    fun decode(line: String): Entry? {
        if (line.isBlank()) return null
        val parsed: Any? = try { Parser(line.trim()).parseTop() } catch (ex: Exception) { null }
        val root = parsed as? Map<*, *> ?: return null
        val t = (root["t"] as? Long) ?: return null
        val s = (root["s"] as? String) ?: return null
        val m = (root["m"] as? String) ?: return null
        val n = ((root["n"] as? Long) ?: 0L).toInt().coerceAtLeast(0)
        val files = ArrayList<FileChange>()
        for (item in (root["f"] as? List<*>) ?: emptyList<Any?>()) {
            val o = item as? Map<*, *> ?: continue
            val p = o["p"] as? String ?: continue
            val k = (o["k"] as? String)?.singleOrNull() ?: continue
            if (k != 'A' && k != 'M' && k != 'D') continue
            files.add(FileChange(p, k, ((o["a"] as? Long) ?: 0L).toInt(), ((o["r"] as? Long) ?: 0L).toInt()))
        }
        return Entry(t, s, m, files, n)
    }

    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        return sb.toString()
    }

    /** Pengurai JSON kecil: objek -> Map, larik -> List, string -> String, bilangan bulat -> Long, lainnya -> Double/Boolean/null. */
    private class Parser(val s: String) {
        var i = 0
        private var depth = 0

        fun parseTop(): Any? {
            ws()
            val v = value()
            ws()
            if (i != s.length) throw IllegalArgumentException("sisa")
            return v
        }

        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun value(): Any? {
            if (i >= s.length || ++depth > 20) throw IllegalArgumentException("dalam")
            val v: Any? = when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
            depth--
            return v
        }

        private fun lit(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw IllegalArgumentException("lit")
            i += w.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            i++
            val m = HashMap<String, Any?>()
            ws()
            if (s.getOrNull(i) == '}') { i++; return m }
            while (true) {
                ws()
                if (s.getOrNull(i) != '"') throw IllegalArgumentException("kunci")
                val k = str()
                ws()
                if (s.getOrNull(i) != ':') throw IllegalArgumentException(":")
                i++
                ws()
                m[k] = value()
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw IllegalArgumentException("obj")
                }
            }
        }

        private fun arr(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (s.getOrNull(i) == ']') { i++; return l }
            while (true) {
                ws()
                l.add(value())
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> throw IllegalArgumentException("arr")
                }
            }
        }

        private fun str(): String {
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> { i++; return sb.toString() }
                    c == '\\' -> {
                        i++
                        when (val e = s.getOrNull(i) ?: throw IllegalArgumentException("esc")) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) throw IllegalArgumentException("u")
                                sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("esc")
                        }
                        i++
                    }
                    c < ' ' -> throw IllegalArgumentException("ctl")
                    else -> { sb.append(c); i++ }
                }
            }
            throw IllegalArgumentException("str")
        }

        private fun num(): Any {
            val st = i
            if (s.getOrNull(i) == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val t = s.substring(st, i)
            if (t.isEmpty() || t == "-") throw IllegalArgumentException("num")
            return t.toLongOrNull() ?: t.toDouble()
        }
    }
}
