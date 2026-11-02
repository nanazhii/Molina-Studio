package com.molina.code.ui

import java.io.File

// [CODE-SYMBOL] Berkas murni Kotlin (tanpa Compose/Android) agar bisa diuji di JVM: scripts/code-tests/run_symbols.sh.
// Pengurai struktur (Outline) dan fuzzyScore dipindah ke sini apa adanya dari Outline.kt dan CommandPalette.kt.

/** [CODE-SEARCH] Satu simbol di Struktur berkas. [line] basis 0, [depth] 0..4. */
internal class OutlineItem(val line: Int, val depth: Int, val kind: String, val name: String)

private class OSpec(val re: Regex, val nameGroup: Int, val kindGroup: Int, val fixedKind: String?)
private class OLang(val specs: List<OSpec>, val unit: Int)

/** Pengurai struktur ringan berbasis regex per ekstensi (tanpa parser penuh): kelas, fungsi, dan judul Markdown. */
internal object Outline {
    private const val MODS_KT = "public|private|protected|internal|open|abstract|override|suspend|inline|data|sealed|enum|annotation|companion|const|lateinit|operator|infix|tailrec|external|actual|expect|inner|value"
    private val KT = OLang(listOf(OSpec(Regex("""^(\s*)(?:(?:$MODS_KT)\s+)*?(class|interface|object|fun)\s+(?:<[^>]*>\s*)?([A-Za-z_][\w.]*)"""), 3, 2, null)), 4)
    private val PY = OLang(listOf(OSpec(Regex("""^(\s*)(class|async def|def)\s+(\w+)"""), 3, 2, null)), 4)
    private val JS = OLang(listOf(OSpec(Regex("""^(\s*)(?:export\s+)?(?:default\s+)?(?:async\s+)?(function\*?|class)\s+(\w+)"""), 3, 2, null)), 2)
    private val JAVA = OLang(
        listOf(
            OSpec(Regex("""^(\s*)(?:(?:public|private|protected|static|final|abstract|sealed)\s+)*(class|interface|enum|record)\s+(\w+)"""), 3, 2, null),
            OSpec(Regex("""^(\s*)(?:(?:public|private|protected|static|final|abstract|synchronized|default)\s+)+(?:<[^>]+>\s+)?[\w<>\[\],.?]+\s+(\w+)\s*\("""), 2, 0, "fun")
        ), 4
    )
    private val SH = OLang(
        listOf(
            OSpec(Regex("""^(\s*)function\s+([A-Za-z_][\w-]*)"""), 2, 0, "fun"),
            OSpec(Regex("""^(\s*)([A-Za-z_][\w-]*)\s*\(\s*\)\s*\{?"""), 2, 0, "fun")
        ), 2
    )
    private val MD_RE = Regex("""^(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val LANGS = mapOf(
        "kt" to KT, "kts" to KT, "py" to PY, "js" to JS, "jsx" to JS, "ts" to JS, "tsx" to JS, "mjs" to JS,
        "java" to JAVA, "sh" to SH, "bash" to SH
    )

    /** [CODE-SYMBOL] True bila [name] berekstensi bahasa kode yang dikenali (markdown tidak dihitung). */
    fun supportsCode(name: String?): Boolean = LANGS.containsKey(name?.substringAfterLast('.', "")?.lowercase().orEmpty())

    /** Null bila jenis berkas belum didukung. */
    fun extract(name: String?, text: String): List<OutlineItem>? {
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val md = ext == "md" || ext == "markdown"
        val lang = if (md) null else LANGS[ext]
        if (!md && lang == null) return null
        val specs = lang?.specs ?: emptyList()
        val unit = lang?.unit ?: 4
        val out = ArrayList<OutlineItem>()
        var inFence = false
        var n = 0
        for (raw in text.lineSequence()) {
            val i = n++
            if (n > 200_000 || out.size >= 800) break
            if (md) {
                if (raw.trimStart().startsWith("```")) { inFence = !inFence; continue }
                if (inFence) continue
                val m = MD_RE.find(raw) ?: continue
                out.add(OutlineItem(i, m.groupValues[1].length - 1, "#", m.groupValues[2].trim()))
            } else {
                val line = if (raw.length > 300) raw.substring(0, 300) else raw
                for (sp in specs) {
                    val m = sp.re.find(line) ?: continue
                    val indent = m.groupValues[1].replace("\t", "    ").length
                    val kind = sp.fixedKind ?: m.groupValues[sp.kindGroup].let { if (it.startsWith("async")) "def" else it.removeSuffix("*") }
                    out.add(OutlineItem(i, minOf(indent / unit, 4), kind, m.groupValues[sp.nameGroup]))
                    break
                }
            }
        }
        return out
    }
}

/**
 * Skor pencocokan fuzzy (subsequence, tanpa beda huruf besar/kecil): huruf berurutan dan awal kata
 * (setelah / . _ - spasi, atau huruf besar camelCase) bernilai lebih. -1 = tidak cocok; query kosong = 0.
 */
internal fun fuzzyScore(query: String, text: String): Int {
    if (query.isEmpty()) return 0
    val ql = query.lowercase()
    val tl = text.lowercase()
    val orig = if (text.length == tl.length) text else tl
    var qi = 0
    var score = 0
    var prev = -2
    for (i in tl.indices) {
        if (qi < ql.length && tl[i] == ql[qi]) {
            score += 1
            if (i == prev + 1) score += 5
            if (i == 0 || orig[i - 1] in "/._- :" || (orig[i].isUpperCase() && orig[i - 1].isLowerCase())) score += 8
            prev = i
            qi++
        }
    }
    if (qi < ql.length) return -1
    return score * 10 - minOf(text.length, 90)
}

/** [CODE-SYMBOL] Satu simbol di indeks lintas berkas. [line] basis 0; [container] = nama simbol induk ("" bila tingkat atas). */
internal class SymbolEntry(val file: File, val line: Int, val depth: Int, val kind: String, val name: String, val container: String)

/**
 * Indeks simbol seluruh folder utama (Pergi ke simbol). Murni Kotlin, regex per ekstensi lewat [Outline].
 * Hasil per berkas disimpan berdasarkan (lastModified, length) sehingga pembukaan berikutnya hanya membaca ulang berkas yang berubah.
 * Teks tab yang terbuka ([overrides], kunci = path) selalu didahulukan atas isi disk dan tidak masuk cache.
 */
internal class SymbolIndex {
    internal class Result(val entries: List<SymbolEntry>, val files: Int, val capped: Boolean)

    private class Cached(val stamp: Long, val len: Long, val items: List<SymbolEntry>)

    private val cache = HashMap<String, Cached>()

    /** Jumlah berkas yang dibaca dari disk pada [build] terakhir (untuk uji dan status). */
    @Volatile
    var lastDiskReads: Int = 0
        private set

    @Synchronized
    fun build(files: List<File>, overrides: Map<String, String>, active: () -> Boolean): Result {
        val out = ArrayList<SymbolEntry>()
        val seen = HashSet<String>()
        var scanned = 0
        var reads = 0
        var capped = false
        for (f in files) {
            if (!active()) break
            if (out.size >= MAX_ENTRIES) { capped = true; break }
            if (!Outline.supportsCode(f.name)) continue
            val key = f.path
            seen.add(key)
            val over = overrides[key]
            val items: List<SymbolEntry>
            if (over != null) {
                items = parse(f, over)
            } else {
                val len = f.length()
                if (len == 0L || len > MAX_BYTES) { cache.remove(key); continue }
                val stamp = f.lastModified()
                val hit = cache[key]
                if (hit != null && hit.stamp == stamp && hit.len == len) {
                    items = hit.items
                } else {
                    val text = try { String(f.readBytes(), Charsets.UTF_8) } catch (e: Exception) { cache.remove(key); continue }
                    reads++
                    items = parse(f, text)
                    cache[key] = Cached(stamp, len, items)
                }
            }
            scanned++
            if (items.isNotEmpty()) {
                val room = MAX_ENTRIES - out.size
                if (items.size > room) { out.addAll(items.subList(0, room)); capped = true } else out.addAll(items)
            }
        }
        if (active()) cache.keys.retainAll(seen) // pangkas berkas yang sudah tidak ada di pindaian
        lastDiskReads = reads
        return Result(out, scanned, capped)
    }

    @Synchronized
    fun clear() = cache.clear()

    private fun parse(f: File, text: String): List<SymbolEntry> {
        val items = Outline.extract(f.name, text) ?: return emptyList()
        val res = ArrayList<SymbolEntry>(items.size)
        val stack = ArrayList<OutlineItem>()
        for (o in items) {
            while (stack.isNotEmpty() && stack.last().depth >= o.depth) stack.removeAt(stack.size - 1)
            res.add(SymbolEntry(f, o.line, o.depth, o.kind, o.name, stack.lastOrNull()?.name.orEmpty()))
            stack.add(o)
        }
        return res
    }

    companion object {
        const val MAX_BYTES = 1_000_000L
        const val MAX_ENTRIES = 20_000
    }
}

/** [CODE-SYMBOL] Peringkat hasil Pergi ke simbol. */
internal object SymbolSearch {
    private val TYPE_KINDS = setOf("class", "interface", "object", "enum", "record")

    /**
     * Urutan: nama persis > awalan > fuzzy; tipe (class/interface/...) sedikit di atas fungsi;
     * simbol di berkas aktif [current] mendapat bonus. Query kosong = simbol berkas aktif lebih dulu (urutan baris).
     */
    fun rank(entries: List<SymbolEntry>, query: String, limit: Int, current: File?): List<SymbolEntry> {
        val q = query.trim()
        val curPath = current?.path
        val scored = ArrayList<Pair<SymbolEntry, Int>>()
        for (e in entries) {
            val inCur = curPath != null && e.file.path == curPath
            var s: Int
            if (q.isEmpty()) {
                if (!inCur) continue
                s = 0
            } else {
                s = fuzzyScore(q, e.name)
                if (s < 0) continue
                if (e.name.equals(q, ignoreCase = true)) s += 2000
                else if (e.name.startsWith(q, ignoreCase = true)) s += 400
                if (e.kind in TYPE_KINDS) s += 15
                s -= e.depth * 3
            }
            if (inCur) s += 40
            scored.add(Pair(e, s))
        }
        return scored
            .sortedWith(compareByDescending<Pair<SymbolEntry, Int>> { it.second }.thenBy { it.first.file.path }.thenBy { it.first.line })
            .take(limit)
            .map { it.first }
    }
}
