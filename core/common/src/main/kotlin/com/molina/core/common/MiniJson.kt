package com.molina.core.common

/** JSON kecil tanpa dependensi untuk keluaran alat luar (yt-dlp). Hanya objek; rusak -> null. */
object MiniJson {
    private const val MAX = 4_000_000
    private const val DEPTH = 32

    fun parseObject(t: String): Map<String, Any?>? {
        if (t.length > MAX) return null
        return try {
            val p = JsonCursor(t)
            p.ws()
            if (p.c() != '{') return null
            @Suppress("UNCHECKED_CAST")
            val v = value(p, 0) as Map<String, Any?>
            p.ws()
            if (p.atEnd) v else null
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun value(p: JsonCursor, d: Int): Any? {
        if (d > DEPTH) p.f()
        p.ws()
        return when (p.c()) {
            '{' -> obj(p, d)
            '[' -> arr(p, d)
            '"' -> p.str()
            't' -> p.lit("true", true)
            'f' -> p.lit("false", false)
            'n' -> p.lit("null", null)
            else -> p.num()
        }
    }

    private fun obj(p: JsonCursor, d: Int): Any {
        p.i++
        val m = LinkedHashMap<String, Any?>()
        if (p.end('}')) return m
        do {
            p.ws()
            if (p.c() != '"') p.f()
            val k = p.str()
            p.ws()
            if (p.c() != ':') p.f()
            p.i++
            m[k] = value(p, d + 1)
        } while (p.more('}'))
        return m
    }

    private fun arr(p: JsonCursor, d: Int): Any {
        p.i++
        val l = ArrayList<Any?>()
        if (p.end(']')) return l
        do { l.add(value(p, d + 1)) } while (p.more(']'))
        return l
    }
}
