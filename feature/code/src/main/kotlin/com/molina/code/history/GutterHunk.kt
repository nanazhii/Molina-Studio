package com.molina.code.history

// [CODE-HISTORY8] Logika murni (tanpa Android): memetakan baris gutter yang diketuk ke blok perubahan terhadap isi awal.
// Indeks baris = basis 0 pada teks editor sekarang. Blok = deretan baris bukan SAME pada hasil [LineDiff.diff] (urutan [HunkRevert.runs]).

/** Satu blok perubahan: [index] = nomor blok untuk [HunkRevert.revertAt], [newFirst] = baris pertama di isi sekarang (basis 0), [oldLines]/[newLines] = isi sisi awal/sekarang. */
class GutterHunk(val index: Int, val newFirst: Int, val oldLines: List<String>, val newLines: List<String>) {
    /** Blok murni penghapusan: tidak ada baris di isi sekarang. */
    val pureDelete: Boolean get() = newLines.isEmpty()
    /** Nomor baris basis 1 yang ditampilkan: "12" atau "12–14"; blok penghapusan murni dilaporkan di titik hapusnya. */
    fun lineLabel(): String {
        val a = newFirst + 1
        return if (newLines.size <= 1) a.toString() else "$a\u2013${a + newLines.size - 1}"
    }
}

object GutterHunks {
    /** Semua blok dari hasil diff [lines], urut dari atas. */
    fun all(lines: List<DiffLine>): List<GutterHunk> {
        val out = ArrayList<GutterHunk>()
        HunkRevert.runs(lines).forEachIndexed { idx, r ->
            var first = 0
            for (i in 0 until r.first) if (lines[i].op != DiffOp.DEL) first++
            val dels = ArrayList<String>()
            val adds = ArrayList<String>()
            for (i in r) if (lines[i].op == DiffOp.DEL) dels.add(lines[i].text) else adds.add(lines[i].text)
            out.add(GutterHunk(idx, first, dels, adds))
        }
        return out
    }

    /**
     * Blok yang dimaksud ketukan pada [line] (basis 0) di editor dengan [lineCount] baris. Blok yang punya baris baru
     * mencakup barisnya; blok penghapusan murni ditandai di baris [GutterHunk.newFirst] (atau di baris terakhir
     * bila penghapusan terjadi tepat setelahnya). Null bila tidak ada blok di baris itu.
     */
    fun at(hunks: List<GutterHunk>, line: Int, lineCount: Int): GutterHunk? {
        for (h in hunks) {
            if (h.pureDelete) {
                if (h.newFirst == line || (h.newFirst == lineCount && line == lineCount - 1)) return h
            } else if (line >= h.newFirst && line < h.newFirst + h.newLines.size) return h
        }
        return null
    }

    /** Teks isi sekarang -> awal untuk satu blok; null bila tidak ada baris yang terkena. */
    fun find(base: String, cur: String, line: Int, lineCount: Int): GutterHunk? {
        val res = LineDiff.diff(base, cur)
        if (res.coarse || !res.changed) return null
        return at(all(res.lines), line, lineCount)
    }
}
