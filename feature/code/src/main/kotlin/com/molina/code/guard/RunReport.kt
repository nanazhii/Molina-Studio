package com.molina.code.guard

import com.molina.code.history.LineDiff
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

// [CODE-RUNREPORT] Laporan perubahan setelah skrip jalan. Kotlin murni (tanpa Android) agar bisa diuji di JVM:
// scripts/code-tests/run_runreport.sh. Alur: capture() sebelum skrip, compare() sesudahnya, undo() bila pengguna
// memilih Urungkan. Cadangan isi hanya untuk berkas kecil dan disimpan di memori selama laporan ada.

object RunReport {
    const val MAX_FILES = 20_000
    const val MAX_BACKUP_FILE = 512 * 1024
    const val MAX_BACKUP_TOTAL = 16 * 1024 * 1024
    private const val MTIME_SLACK_MS = 2_000L
    private const val SHRINK_MIN_BYTES = 200

    private val SKIP_DIRS = setOf(
        ".git", ".molina", ".gradle", "build", ".idea", ".cxx", ".kotlin", "node_modules", "__pycache__", ".venv", "venv"
    )
    private val BINARY_EXT = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "ico", "so", "apk", "aab", "jar", "class", "dex", "zip", "gz", "xz",
        "bz2", "7z", "mp3", "mp4", "mkv", "ogg", "wav", "flac", "ttf", "otf", "woff", "woff2", "pdf", "bin", "db",
        "sqlite", "pyc", "keystore", "jks"
    )

    class Meta(val size: Long, val mtime: Long)

    /** Keadaan sebelum skrip: metadata semua berkas + isi berkas kecil. [startedAt] = waktu pindai (ms). */
    class Snapshot(val root: File, val startedAt: Long, val meta: Map<String, Meta>, val backups: Map<String, ByteArray>,
        /** [CODE-SCANROOT] Penanda akar proyek yang dipakai (mis. ".git"); null = tanpa penanda. */
        val how: String? = null)

    enum class Kind { ADDED, MODIFIED, DELETED }

    enum class Issue(val label: String) {
        NOT_UTF8("bukan UTF-8 valid"),
        HAS_NUL("berisi byte NUL"),
        NEWLINE_LOST("baris baru di akhir berkas hilang"),
        SHRUNK("ukuran menyusut drastis"),
        EMPTIED("berkas jadi kosong"),
        BAD_JSON("JSON tidak valid"),
        BAD_XML("XML tidak valid")
    }

    /**
     * Satu berkas yang berubah. [path] relatif terhadap root dengan pemisah '/'. [afterFp] = sidik jari isi setelah
     * skrip (untuk memastikan berkas belum berubah lagi saat Urungkan), null untuk DELETED.
     */
    class Change(
        val path: String,
        val kind: Kind,
        val added: Int,
        val removed: Int,
        val undoable: Boolean,
        val afterFp: String?,
        val issues: List<Issue>
    )

    class Report(val root: File, val changes: List<Change>) {
        val isEmpty: Boolean get() = changes.isEmpty()
        val problems: Int get() = changes.count { it.issues.isNotEmpty() }
        val undoableCount: Int get() = changes.count { it.undoable }
    }

    class UndoResult(val restored: Int, val removed: Int, val skipped: List<String>, val failed: List<String>)

    /** [CODE-SCANROOT] Akar proyek hasil [findRootInfo]; [how] = nama penanda yang dipakai, null bila tidak ada penanda. */
    class RootInfo(val root: File, val how: String?)

    // Penanda kuat: dicari di semua tingkat (maks. 8), yang paling dekat menang. Penanda lemah hanya dipakai bila tidak ada penanda kuat.
    private val STRONG_MARKERS = listOf(".git", ".molina")
    private val WEAK_MARKERS = listOf(
        "settings.gradle.kts", "settings.gradle", "package.json", "pyproject.toml", "Cargo.toml", "go.mod", "pom.xml",
        "CMakeLists.txt", "Makefile"
    )
    private val STORAGE_ROOTS = setOf("/", "/sdcard", "/storage", "/storage/emulated", "/storage/emulated/0", "/mnt/sdcard")

    /**
     * Naik dari [start] (maks. 8 tingkat) mencari akar proyek. Urutan: folder berisi `.git` (repo Git) atau `.molina`
     * (penanda proyek lokal tanpa Git); bila tidak ada, folder terdekat berisi berkas proyek umum (settings.gradle,
     * package.json, pyproject.toml, dst.; tidak pernah akar penyimpanan bersama). Tidak ketemu: [start] sendiri.
     */
    fun findRootInfo(start: File): RootInfo {
        var weak: RootInfo? = null
        var d: File? = start
        var n = 0
        while (d != null && n < 8) {
            for (m in STRONG_MARKERS) if (File(d, m).exists()) return RootInfo(d, m)
            if (weak == null && d.path.trimEnd('/').ifEmpty { "/" } !in STORAGE_ROOTS) {
                for (m in WEAK_MARKERS) if (File(d, m).isFile) { weak = RootInfo(d, m); break }
            }
            d = d.parentFile
            n++
        }
        return weak ?: RootInfo(start, null)
    }

    fun findRoot(start: File): File = findRootInfo(start).root

    /** Pindai [root] dan simpan isi berkas kecil. Null bila folder terlalu besar (laporan dilewati). */
    fun capture(root: File, now: Long, how: String? = null): Snapshot? {
        val w = walk(root, true) ?: return null
        return Snapshot(root, now, w.first, w.second, how)
    }

    /** Bandingkan keadaan sekarang dengan [snap]. Null bila folder kini terlalu besar. */
    fun compare(snap: Snapshot): Report? {
        val after = walk(snap.root, false)?.first ?: return null
        val out = ArrayList<Change>()
        for ((p, m) in after) {
            val b = snap.meta[p]
            if (b == null) {
                analyze(snap, p, Kind.ADDED, m, null)?.let { out.add(it) }
                continue
            }
            val metaDiffers = b.size != m.size || b.mtime != m.mtime
            if (!metaDiffers && m.mtime < snap.startedAt - MTIME_SLACK_MS) continue
            analyzeModified(snap, p, m, metaDiffers)?.let { out.add(it) }
        }
        for (p in snap.meta.keys) {
            if (p in after) continue
            val old = snap.backups[p]
            val oldText = old?.let { decode(it) }
            val removed = if (oldText != null) LineDiff.diff(oldText, "").removed else 0
            out.add(Change(p, Kind.DELETED, 0, removed, old != null, null, emptyList()))
        }
        out.sortBy { it.path }
        return Report(snap.root, out)
    }

    private fun analyzeModified(snap: Snapshot, p: String, m: Meta, metaDiffers: Boolean): Change? {
        val old = snap.backups[p]
        if (old == null && !metaDiffers) return null // tanpa cadangan, hanya perubahan metadata yang bisa dipercaya
        return analyze(snap, p, Kind.MODIFIED, m, old)
    }

    /** Null bila isi sama dengan cadangan (berkas hanya disentuh, mis. waktu ubah berganti). */
    private fun analyze(snap: Snapshot, p: String, kind: Kind, m: Meta, old: ByteArray?): Change? {
        val cur = readSmall(File(snap.root, p), m.size)
        if (kind == Kind.MODIFIED && old != null && cur != null && old.contentEquals(cur)) {
            return null
        }
        val issues = ArrayList<Issue>()
        var added = 0
        var removed = 0
        var curText: String? = null
        if (cur != null) {
            if (cur.any { it == 0.toByte() }) issues.add(Issue.HAS_NUL)
            curText = decode(cur)
            if (curText == null && Issue.HAS_NUL !in issues) issues.add(Issue.NOT_UTF8)
            if (old != null) {
                if (old.isNotEmpty() && cur.isEmpty()) issues.add(Issue.EMPTIED)
                else if (old.size >= SHRINK_MIN_BYTES && cur.size < old.size / 2) issues.add(Issue.SHRUNK)
                if (old.isNotEmpty() && old.last() == '\n'.code.toByte() && cur.isNotEmpty() && cur.last() != '\n'.code.toByte()) {
                    issues.add(Issue.NEWLINE_LOST)
                }
            }
            if (curText != null) {
                val ext = p.substringAfterLast('.', "").lowercase()
                if (ext == "json" && !MiniJson.valid(curText)) issues.add(Issue.BAD_JSON)
                if ((ext == "xml" || ext == "svg") && !MiniXml.valid(curText)) issues.add(Issue.BAD_XML)
            }
        }
        val oldText = if (old != null) decode(old) else if (kind == Kind.ADDED) "" else null
        if (curText != null && oldText != null) {
            val d = LineDiff.diff(oldText, curText)
            added = d.added
            removed = d.removed
        }
        val undoable = kind == Kind.ADDED || old != null
        return Change(p, kind, added, removed, undoable, cur?.let { fp(it) } ?: "${m.size}:${m.mtime}", issues)
    }

    /**
     * Kembalikan keadaan sebelum skrip. Berkas yang sudah berubah lagi sejak laporan dilewati (tidak ditimpa).
     * MODIFIED/DELETED: tulis cadangan; ADDED: hapus berkas.
     */
    fun undo(snap: Snapshot, report: Report): UndoResult {
        var restored = 0
        var removed = 0
        val skipped = ArrayList<String>()
        val failed = ArrayList<String>()
        for (c in report.changes) {
            if (!c.undoable) { skipped.add(c.path); continue }
            val f = File(snap.root, c.path)
            try {
                when (c.kind) {
                    Kind.ADDED -> {
                        if (!f.exists()) continue
                        if (currentFp(f) != c.afterFp) { skipped.add(c.path); continue }
                        if (f.delete()) removed++ else failed.add(c.path)
                    }
                    Kind.MODIFIED -> {
                        val old = snap.backups[c.path]
                        if (old == null || !f.exists() || currentFp(f) != c.afterFp) { skipped.add(c.path); continue }
                        if (writeAtomic(f, old)) restored++ else failed.add(c.path)
                    }
                    Kind.DELETED -> {
                        val old = snap.backups[c.path]
                        if (old == null || f.exists()) { skipped.add(c.path); continue }
                        if (writeAtomic(f, old)) restored++ else failed.add(c.path)
                    }
                }
            } catch (e: Exception) {
                failed.add(c.path)
            }
        }
        return UndoResult(restored, removed, skipped, failed)
    }

    // ------------------------------------------------------------------ util

    private fun walk(root: File, backup: Boolean): Pair<Map<String, Meta>, Map<String, ByteArray>>? {
        val meta = HashMap<String, Meta>()
        val backups = HashMap<String, ByteArray>()
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.add(root)
        val prefix = root.path.trimEnd('/').length + 1
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val kids = dir.listFiles() ?: continue
            for (f in kids) {
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
                if (f.isDirectory) {
                    if (f.name !in SKIP_DIRS) stack.add(f)
                    continue
                }
                if (!f.isFile || f.name.endsWith(".pyc")) continue
                if (meta.size >= MAX_FILES) return null
                val rel = f.path.substring(prefix).replace(File.separatorChar, '/')
                val size = f.length()
                meta[rel] = Meta(size, f.lastModified())
                if (backup && size <= MAX_BACKUP_FILE && total + size <= MAX_BACKUP_TOTAL &&
                    f.name.substringAfterLast('.', "").lowercase() !in BINARY_EXT
                ) {
                    val b = try { f.readBytes() } catch (e: Exception) { null }
                    if (b != null) { backups[rel] = b; total += b.size }
                }
            }
        }
        return meta to backups
    }

    private fun readSmall(f: File, size: Long): ByteArray? =
        if (size > MAX_BACKUP_FILE) null else try { f.readBytes() } catch (e: Exception) { null }

    private fun currentFp(f: File): String? {
        val len = f.length()
        return if (len <= MAX_BACKUP_FILE) readSmall(f, len)?.let { fp(it) } else "$len:${f.lastModified()}"
    }

    private fun fp(b: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }.take(12)

    private fun decode(b: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun writeAtomic(f: File, bytes: ByteArray): Boolean {
        val dir = f.parentFile ?: return false
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return false
        val tmp = File(dir, ".${f.name}.molina-undo")
        return try {
            tmp.writeBytes(bytes)
            if (tmp.renameTo(f)) true else {
                f.delete()
                if (tmp.renameTo(f)) true else { tmp.delete(); false }
            }
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }
}

/** Pemeriksa sintaks JSON ringan (RFC 8259, tanpa komentar). */
object MiniJson {
    fun valid(text: String): Boolean {
        val s = text.removePrefix("\uFEFF")
        val p = P(s)
        p.ws()
        if (!p.value()) return false
        p.ws()
        return p.i == s.length
    }

    private class P(val s: String) {
        var i = 0
        private var depth = 0

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(): Boolean {
            if (i >= s.length || ++depth > 200) return false
            val ok = when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true")
                'f' -> lit("false")
                'n' -> lit("null")
                else -> num()
            }
            depth--
            return ok
        }

        private fun lit(w: String): Boolean {
            if (!s.startsWith(w, i)) return false
            i += w.length
            return true
        }

        private fun obj(): Boolean {
            i++
            ws()
            if (s.getOrNull(i) == '}') { i++; return true }
            while (true) {
                ws()
                if (s.getOrNull(i) != '"' || !str()) return false
                ws()
                if (s.getOrNull(i) != ':') return false
                i++
                ws()
                if (!value()) return false
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    '}' -> { i++; return true }
                    else -> return false
                }
            }
        }

        private fun arr(): Boolean {
            i++
            ws()
            if (s.getOrNull(i) == ']') { i++; return true }
            while (true) {
                ws()
                if (!value()) return false
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    ']' -> { i++; return true }
                    else -> return false
                }
            }
        }

        private fun str(): Boolean {
            i++
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> { i++; return true }
                    c == '\\' -> {
                        i++
                        val e = s.getOrNull(i) ?: return false
                        if (e == 'u') {
                            for (k in 1..4) {
                                val h = s.getOrNull(i + k) ?: return false
                                if (!(h in '0'..'9' || h in 'a'..'f' || h in 'A'..'F')) return false
                            }
                            i += 4
                        } else if (e !in "\"\\/bfnrt") return false
                        i++
                    }
                    c < ' ' -> return false
                    else -> i++
                }
            }
            return false
        }

        private fun digits(): Boolean {
            val st = i
            while (i < s.length && s[i] in '0'..'9') i++
            return i > st
        }

        private fun num(): Boolean {
            val st = i
            if (s.getOrNull(i) == '-') i++
            when (s.getOrNull(i)) {
                '0' -> i++
                in '1'..'9' -> digits()
                else -> return false
            }
            if (s.getOrNull(i) == '.') { i++; if (!digits()) return false }
            if (s.getOrNull(i) == 'e' || s.getOrNull(i) == 'E') {
                i++
                if (s.getOrNull(i) == '+' || s.getOrNull(i) == '-') i++
                if (!digits()) return false
            }
            return i > st
        }
    }
}

/** Pemeriksa kerapian tag XML ringan: tag berpasangan, satu elemen akar, kutip atribut tertutup. */
object MiniXml {
    fun valid(text: String): Boolean {
        val s = text
        val n = s.length
        var i = if (s.startsWith("\uFEFF")) 1 else 0
        val stack = ArrayList<String>()
        var roots = 0
        while (i < n) {
            if (s[i] != '<') {
                if (stack.isEmpty() && !s[i].isWhitespace()) return false
                i++
                continue
            }
            when {
                s.startsWith("<!--", i) -> {
                    val e = s.indexOf("-->", i + 4)
                    if (e < 0) return false
                    i = e + 3
                }
                s.startsWith("<![CDATA[", i) -> {
                    if (stack.isEmpty()) return false
                    val e = s.indexOf("]]>", i + 9)
                    if (e < 0) return false
                    i = e + 3
                }
                s.startsWith("<?", i) -> {
                    val e = s.indexOf("?>", i + 2)
                    if (e < 0) return false
                    i = e + 2
                }
                s.startsWith("<!", i) -> {
                    var d = 0
                    var j = i + 2
                    while (j < n) {
                        val ch = s[j]
                        if (ch == '[') d++ else if (ch == ']') d-- else if (ch == '>' && d <= 0) break
                        j++
                    }
                    if (j >= n) return false
                    i = j + 1
                }
                s.startsWith("</", i) -> {
                    val e = s.indexOf('>', i + 2)
                    if (e < 0) return false
                    val name = s.substring(i + 2, e).trim()
                    if (stack.isEmpty() || stack.removeAt(stack.size - 1) != name) return false
                    i = e + 1
                }
                else -> {
                    var j = i + 1
                    var q = '\u0000'
                    while (j < n) {
                        val ch = s[j]
                        if (q != '\u0000') { if (ch == q) q = '\u0000' }
                        else if (ch == '"' || ch == '\'') q = ch
                        else if (ch == '>') break
                        j++
                    }
                    if (j >= n) return false
                    val self = s[j - 1] == '/'
                    val inner = s.substring(i + 1, if (self) j - 1 else j)
                    val name = inner.takeWhile { !it.isWhitespace() }
                    if (name.isEmpty() || !(name[0].isLetter() || name[0] == '_' || name[0] == ':')) return false
                    if (stack.isEmpty()) { roots++; if (roots > 1) return false }
                    if (!self) stack.add(name)
                    i = j + 1
                }
            }
        }
        return stack.isEmpty() && roots == 1
    }
}
