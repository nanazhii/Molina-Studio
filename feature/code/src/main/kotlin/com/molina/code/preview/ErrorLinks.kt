package com.molina.code.preview

/** [CODE-PALETTE] Lokasi berkas:baris di satu baris keluaran; [start] sampai [end] = rentang teks tautan di baris itu. */
class ErrLoc(val path: String, val line: Int, val col: Int, val start: Int, val end: Int)

/** Pencari lokasi error di keluaran skrip: Python (File "x", line N), bash (x: line N:), dan gaya x.ext:baris[:kolom]. */
object ErrorLinks {
    private val EXT = setOf(
        "py", "sh", "kt", "kts", "java", "js", "ts", "c", "cc", "cpp", "h", "hpp", "rs", "go", "rb", "lua", "pl",
        "php", "json", "yml", "yaml", "xml", "gradle", "md", "txt", "toml", "cfg", "ini", "html", "css"
    )
    private val PY = Regex("""File "([^"]+)", line (\d+)""")
    private val BASH = Regex("""^([^\s:][^:]*): line (\d+):""")
    private val GEN = Regex("""((?:file://)?[A-Za-z0-9_./~+@\-]+\.([A-Za-z0-9]{1,6})):(\d+)(?::(\d+))?""")

    fun find(text: String): List<ErrLoc> {
        if (text.length < 4 || text.length > 1000) return emptyList()
        val out = ArrayList<ErrLoc>()
        PY.find(text)?.let { m ->
            val line = m.groupValues[2].toIntOrNull()
            if (line != null && line > 0) out.add(ErrLoc(m.groupValues[1], line, 0, m.range.first, m.range.last + 1))
        }
        BASH.find(text)?.let { m ->
            val line = m.groupValues[2].toIntOrNull()
            if (line != null && line > 0) out.add(ErrLoc(m.groupValues[1], line, 0, 0, m.range.last))
        }
        for (m in GEN.findAll(text)) {
            if (m.groupValues[2].lowercase() !in EXT) continue
            if (m.groupValues[1].startsWith("//")) continue // bagian dari URL http://
            val s = m.range.first
            val e = m.range.last + 1
            val line = m.groupValues[3].toIntOrNull() ?: continue
            if (line < 1) continue
            if (out.any { s < it.end && e > it.start }) continue
            out.add(ErrLoc(m.groupValues[1], line, m.groupValues[4].toIntOrNull() ?: 0, s, e))
        }
        return out.sortedBy { it.start }
    }
}
