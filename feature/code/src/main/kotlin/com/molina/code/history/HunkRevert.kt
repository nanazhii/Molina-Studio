package com.molina.code.history

// [CODE-HISTORY7] Logika murni (tanpa Android): mengembalikan SATU blok perubahan pada teks target.
// Blok = deretan baris ADD/DEL berurutan pada hasil [LineDiff.diff]. Isi sisi baru blok (baris ADD) dicari di teks
// target bersama maks. [context] baris SAME di kiri-kanannya, lalu diganti dengan isi sisi lama (baris DEL).

object HunkRevert {
    /** Rentang indeks pada [lines] untuk tiap blok perubahan (deretan baris bukan SAME), urut dari atas. */
    fun runs(lines: List<DiffLine>): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].op == DiffOp.SAME) { i++; continue }
            var j = i
            while (j + 1 < lines.size && lines[j + 1].op != DiffOp.SAME) j++
            out.add(i..j)
            i = j + 1
        }
        return out
    }

    /** Kembalikan blok ke-[index] (urutan [runs]); null bila tidak ada atau teks target sudah berubah di blok itu. */
    fun revertAt(target: String, lines: List<DiffLine>, index: Int): String? =
        runs(lines).getOrNull(index)?.let { revert(target, lines, it) }

    /**
     * Teks target dengan blok [run] dikembalikan ke isi sisi lama. Null bila pola blok tidak ditemukan, atau ditemukan
     * lebih dari satu tempat tanpa posisi yang diharapkan cocok (lebih aman tidak mengubah apa pun).
     * Baris baru di akhir teks target dipertahankan apa adanya.
     */
    fun revert(target: String, lines: List<DiffLine>, run: IntRange, context: Int = 2): String? {
        if (run.isEmpty() || run.first < 0 || run.last >= lines.size) return null
        val adds = ArrayList<String>()
        val dels = ArrayList<String>()
        for (i in run) when (lines[i].op) {
            DiffOp.ADD -> adds.add(lines[i].text)
            DiffOp.DEL -> dels.add(lines[i].text)
            DiffOp.SAME -> return null
        }
        var yStart = 0 // posisi awal blok pada sisi baru (baris DEL tidak dihitung)
        for (i in 0 until run.first) if (lines[i].op != DiffOp.DEL) yStart++
        val before = ArrayList<String>()
        var i = run.first - 1
        while (i >= 0 && lines[i].op == DiffOp.SAME && before.size < context) { before.add(0, lines[i].text); i-- }
        val after = ArrayList<String>()
        var j = run.last + 1
        while (j < lines.size && lines[j].op == DiffOp.SAME && after.size < context) { after.add(lines[j].text); j++ }

        val pattern = before + adds + after
        val endsNl = target.endsWith("\n")
        val tl: List<String> = if (target.isEmpty()) emptyList() else (if (endsNl) target.dropLast(1) else target).split('\n')
        fun matchesAt(p: Int): Boolean {
            if (p < 0 || p + pattern.size > tl.size) return false
            for (k in pattern.indices) if (tl[p + k] != pattern[k]) return false
            return true
        }
        val expected = yStart - before.size
        val at: Int
        if (pattern.isEmpty()) {
            if (tl.size != lines.count { it.op != DiffOp.DEL }) return null
            at = 0
        } else if (matchesAt(expected)) {
            at = expected
        } else {
            var found = -1
            var count = 0
            for (p in 0..(tl.size - pattern.size)) if (matchesAt(p)) { found = p; count++ }
            if (count != 1) return null
            at = found
        }
        val s = at + before.size
        val res = ArrayList<String>(tl.size - adds.size + dels.size)
        res.addAll(tl.subList(0, s))
        res.addAll(dels)
        res.addAll(tl.subList(s + adds.size, tl.size))
        if (res.isEmpty()) return ""
        return res.joinToString("\n") + (if (endsNl || target.isEmpty()) "\n" else "")
    }
}
