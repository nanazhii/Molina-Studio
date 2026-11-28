package com.molina.core.files

/** [FILE-CONTRACT] Urutan alami: "ep2" sebelum "ep10", huruf besar/kecil diabaikan. */
object NaturalOrder {
    private val NUM_PARTS = Regex("\\d+|\\D+")

    fun compare(a: String, b: String): Int {
        val x = NUM_PARTS.findAll(a.lowercase()).map { it.value }.toList()
        val y = NUM_PARTS.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                val pt = p.trimStart('0')
                val qt = q.trimStart('0')
                if (pt.length != qt.length) pt.length - qt.length else pt.compareTo(qt)
            } else p.compareTo(q)
            if (c != 0) return c
        }
        return x.size - y.size
    }
}
