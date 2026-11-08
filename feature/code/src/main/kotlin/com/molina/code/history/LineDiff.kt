package com.molina.code.history

// [CODE-HISTORY] Diff baris murni Kotlin (tanpa Android): awalan/akhiran sama dipangkas, bagian tengah dengan LCS.

enum class DiffOp { SAME, ADD, DEL }

/** [oldNo]/[newNo] basis 1; 0 bila baris tidak ada di sisi itu. */
class DiffLine(val op: DiffOp, val text: String, val oldNo: Int, val newNo: Int)

/** Baris tampilan ringkas: [line] terisi, atau [skipped] > 0 = jumlah baris sama yang disembunyikan. */
class DiffRow(val line: DiffLine?, val skipped: Int)

object LineDiff {
    /** Batas sel tabel LCS; di atasnya bagian tengah dianggap diganti seluruhnya ([Result.coarse]). */
    const val MAX_CELLS = 4_000_000L

    class Result(val lines: List<DiffLine>, val added: Int, val removed: Int, val coarse: Boolean) {
        val changed: Boolean get() = added + removed > 0
    }

    private fun split(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val l = s.split('\n')
        return if (s.endsWith('\n')) l.dropLast(1) else l
    }

    fun diff(old: String, new: String): Result {
        val a = split(old)
        val b = split(new)
        var p = 0
        while (p < a.size && p < b.size && a[p] == b[p]) p++
        var s = 0
        while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
        val am = a.subList(p, a.size - s)
        val bm = b.subList(p, b.size - s)

        val out = ArrayList<DiffLine>(a.size + b.size)
        var oldNo = 0
        var newNo = 0
        var added = 0
        var removed = 0
        fun same(t: String) { out.add(DiffLine(DiffOp.SAME, t, ++oldNo, ++newNo)) }
        fun del(t: String) { out.add(DiffLine(DiffOp.DEL, t, ++oldNo, 0)); removed++ }
        fun add(t: String) { out.add(DiffLine(DiffOp.ADD, t, 0, ++newNo)); added++ }

        for (i in 0 until p) same(a[i])
        var coarse = false
        val n = am.size
        val m = bm.size
        when {
            n == 0 -> bm.forEach { add(it) }
            m == 0 -> am.forEach { del(it) }
            n.toLong() * m > MAX_CELLS -> { coarse = true; am.forEach { del(it) }; bm.forEach { add(it) } }
            else -> {
                // dp[i][j] = panjang LCS am[i..] dan bm[j..]; min(n, m) <= 2000 sehingga muat di Short
                val w = m + 1
                val dp = ShortArray((n + 1) * w)
                for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
                    dp[i * w + j] = if (am[i] == bm[j]) (dp[(i + 1) * w + j + 1] + 1).toShort()
                    else maxOf(dp[(i + 1) * w + j], dp[i * w + j + 1])
                }
                var i = 0
                var j = 0
                while (i < n && j < m) {
                    when {
                        am[i] == bm[j] -> { same(am[i]); i++; j++ }
                        dp[(i + 1) * w + j] >= dp[i * w + j + 1] -> del(am[i++])
                        else -> add(bm[j++])
                    }
                }
                while (i < n) del(am[i++])
                while (j < m) add(bm[j++])
            }
        }
        for (i in a.size - s until a.size) same(a[i])
        return Result(out, added, removed, coarse)
    }

    /** Hanya baris berubah plus [context] baris di sekitarnya; baris sama di luar itu diringkas jadi satu DiffRow(skipped). */
    fun compact(r: Result, context: Int = 2): List<DiffRow> {
        val ls = r.lines
        if (!r.changed) return emptyList()
        val keep = BooleanArray(ls.size)
        for (i in ls.indices) if (ls[i].op != DiffOp.SAME) {
            for (k in maxOf(0, i - context)..minOf(ls.size - 1, i + context)) keep[k] = true
        }
        val rows = ArrayList<DiffRow>()
        var i = 0
        while (i < ls.size) {
            if (keep[i]) { rows.add(DiffRow(ls[i], 0)); i++ } else {
                var j = i
                while (j < ls.size && !keep[j]) j++
                rows.add(DiffRow(null, j - i))
                i = j
            }
        }
        return rows
    }
}
