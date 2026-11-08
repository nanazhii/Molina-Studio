package com.molina.code.history

import java.util.IdentityHashMap

// [CODE-HISTORY3] Diff tingkat kata untuk layar Riwayat versi. Kotlin murni (tanpa Android), diuji di
// scripts/code-tests/run_history.sh. Baris DEL dan ADD yang berpasangan ditandai kata yang berubah,
// sehingga "awalnya apa, jadi apa" terlihat tanpa membaca ulang seluruh baris.

object WordDiff {
    /** Di atas jumlah token ini satu baris dianggap diganti seluruhnya (tanpa penanda kata). */
    const val MAX_TOKENS = 400

    /** Pasangan baris dengan kemiripan di bawah ini dianggap beda total: tidak diberi penanda kata. */
    const val MIN_SIMILARITY = 0.35

    private const val PAD = 30
    private const val MAX_BEST_PAIRS = 100L

    /** [old]/[new]: rentang karakter (inklusif) yang berubah pada baris lama/baru. [similarity] 0..1. */
    class Marks(val old: List<IntRange>, val new: List<IntRange>, val similarity: Double) {
        val similar: Boolean get() = similarity >= MIN_SIMILARITY
    }

    private fun kind(c: Char): Int = when {
        c.isLetterOrDigit() || c == '_' -> 0
        c.isWhitespace() -> 1
        else -> 2
    }

    /** Token: deretan huruf/angka/_, deretan spasi, atau satu tanda baca. */
    fun tokenize(s: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < s.length) {
            val k = kind(s[i])
            var j = i + 1
            if (k != 2) while (j < s.length && kind(s[j]) == k) j++
            out.add(i until j)
            i = j
        }
        return out
    }

    fun compare(old: String, new: String): Marks {
        val ta = tokenize(old)
        val tb = tokenize(new)
        if (ta.size > MAX_TOKENS || tb.size > MAX_TOKENS) return Marks(emptyList(), emptyList(), 0.0)
        val sa = ta.map { old.substring(it.first, it.last + 1) }
        val sb = tb.map { new.substring(it.first, it.last + 1) }
        var p = 0
        while (p < sa.size && p < sb.size && sa[p] == sb[p]) p++
        var s = 0
        while (s < sa.size - p && s < sb.size - p && sa[sa.size - 1 - s] == sb[sb.size - 1 - s]) s++
        val n = sa.size - p - s
        val m = sb.size - p - s
        val chA = BooleanArray(sa.size)
        val chB = BooleanArray(sb.size)
        when {
            n == 0 -> for (j in 0 until m) chB[p + j] = true
            m == 0 -> for (i in 0 until n) chA[p + i] = true
            else -> {
                val w = m + 1
                val dp = IntArray((n + 1) * w)
                for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
                    dp[i * w + j] = if (sa[p + i] == sb[p + j]) dp[(i + 1) * w + j + 1] + 1
                    else maxOf(dp[(i + 1) * w + j], dp[i * w + j + 1])
                }
                var i = 0
                var j = 0
                while (i < n && j < m) {
                    if (sa[p + i] == sb[p + j]) {
                        i++; j++
                    } else if (dp[(i + 1) * w + j] >= dp[i * w + j + 1]) {
                        chA[p + i] = true; i++
                    } else {
                        chB[p + j] = true; j++
                    }
                }
                while (i < n) { chA[p + i] = true; i++ }
                while (j < m) { chB[p + j] = true; j++ }
            }
        }
        var nsA = 0
        var nsB = 0
        var common = 0
        for (i in sa.indices) if (sa[i].isNotBlank()) { nsA++; if (!chA[i]) common++ }
        for (j in sb.indices) if (sb[j].isNotBlank()) nsB++
        val sim = if (nsA + nsB == 0) 1.0 else 2.0 * common / (nsA + nsB)
        return Marks(ranges(ta, chA, old), ranges(tb, chB, new), sim)
    }

    /** Gabungkan token berubah yang berurutan (atau hanya dipisah satu spasi) jadi rentang karakter; spasi di tepi dibuang. */
    private fun ranges(tokens: List<IntRange>, changed: BooleanArray, text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < tokens.size) {
            if (!changed[i]) { i++; continue }
            var j = i
            while (true) {
                val next = j + 1
                if (next < tokens.size && changed[next]) { j = next; continue }
                if (next + 1 < tokens.size && !changed[next] && changed[next + 1] &&
                    text.substring(tokens[next].first, tokens[next].last + 1).isBlank()
                ) { j = next + 1; continue }
                break
            }
            var a = tokens[i].first
            var b = tokens[j].last
            if (text.substring(a, b + 1).isNotBlank()) {
                while (a < b && text[a].isWhitespace()) a++
                while (b > a && text[b].isWhitespace()) b--
            }
            out.add(a..b)
            i = j + 1
        }
        return out
    }

    /**
     * Tandai kata berubah pada baris yang berpasangan. Dalam satu blok perubahan (DEL lalu ADD), tiap baris DEL
     * dipasangkan dengan baris ADD yang paling mirip (blok kecil) atau menurut urutan (blok besar). Pasangan
     * yang kurang mirip dilewati: seluruh barisnya memang berubah. Kunci peta = objek baris (identitas).
     */
    fun mark(lines: List<DiffLine>): IdentityHashMap<DiffLine, List<IntRange>> {
        val out = IdentityHashMap<DiffLine, List<IntRange>>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].op == DiffOp.SAME) { i++; continue }
            val dels = ArrayList<DiffLine>()
            val adds = ArrayList<DiffLine>()
            while (i < lines.size && lines[i].op != DiffOp.SAME) {
                if (lines[i].op == DiffOp.DEL) dels.add(lines[i]) else adds.add(lines[i])
                i++
            }
            if (dels.isEmpty() || adds.isEmpty()) continue
            if (dels.size.toLong() * adds.size <= MAX_BEST_PAIRS) {
                val used = BooleanArray(adds.size)
                for (d in dels) {
                    var best = -1
                    var bestMarks: Marks? = null
                    for (a in adds.indices) {
                        if (used[a]) continue
                        val mk = compare(d.text, adds[a].text)
                        if (bestMarks == null || mk.similarity > bestMarks.similarity) { best = a; bestMarks = mk }
                    }
                    if (best >= 0 && bestMarks != null && bestMarks.similar) {
                        used[best] = true
                        out[d] = bestMarks.old
                        out[adds[best]] = bestMarks.new
                    }
                }
            } else {
                for (k in 0 until minOf(dels.size, adds.size)) {
                    val mk = compare(dels[k].text, adds[k].text)
                    if (mk.similar) { out[dels[k]] = mk.old; out[adds[k]] = mk.new }
                }
            }
        }
        return out
    }

    /**
     * Potong baris panjang menjadi jendela <= [max] karakter agar penanda pertama tetap terlihat; bagian yang
     * terpotong diganti "…". Mengembalikan teks tampilan dan rentang penanda yang sudah digeser.
     */
    fun window(text: String, marks: List<IntRange>, max: Int = 160): Pair<String, List<IntRange>> {
        if (text.length <= max) return Pair(text, marks)
        val first = marks.firstOrNull()?.first ?: 0
        val start = if (first > max - PAD) first - PAD else 0
        val end = minOf(text.length, start + max)
        val sb = StringBuilder()
        var shift = -start
        if (start > 0) { sb.append('\u2026'); shift += 1 }
        sb.append(text, start, end)
        if (end < text.length) sb.append('\u2026')
        val rel = marks.mapNotNull { r ->
            val a = maxOf(r.first, start)
            val b = minOf(r.last, end - 1)
            if (a > b) null else (a + shift)..(b + shift)
        }
        return Pair(sb.toString(), rel)
    }
}

/** [CODE-HISTORY3] Ringkasan perubahan dua versi untuk daftar riwayat. */
object VersionDelta {
    /** Di atas ukuran ini (karakter) ringkasan tidak dihitung agar daftar tetap ringan. */
    const val MAX_CHARS = 300_000

    /** "−x +y" baris; "sama" bila identik; "baris kosong" bila hanya beda baris kosong akhir; "besar"; "" bila tak terbaca. */
    fun between(older: String?, newer: String?): String {
        if (older == null || newer == null) return ""
        if (older.length > MAX_CHARS || newer.length > MAX_CHARS) return "besar"
        val r = LineDiff.diff(older, newer)
        return when {
            r.changed -> "\u2212${r.removed} +${r.added}"
            older == newer -> "sama"
            else -> "baris kosong"
        }
    }
}
