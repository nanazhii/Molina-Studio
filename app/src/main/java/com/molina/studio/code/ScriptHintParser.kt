package com.molina.studio.code

/**
 * [SCRIPT-HINT] Parser keluaran `molina-run --scan <folder>` (JVM murni, diuji di scripts/app-tests).
 * Satu baris per skrip yang punya saran: `nama<TAB>fix|warn<TAB>pesan`. Baris lain diabaikan.
 */
object ScriptHintParser {
    enum class Kind { Fix, Warn }

    class Hint(val kind: Kind, val message: String)

    /** Nama berkas (tanpa folder) -> saran. Nama kembar: baris terakhir menang. */
    fun parse(output: String): Map<String, Hint> {
        val out = LinkedHashMap<String, Hint>()
        for (raw in output.lineSequence()) {
            val line = raw.trimEnd('\r')
            val parts = line.split('\t', limit = 3)
            if (parts.size < 2) continue
            val name = parts[0]
            if (name.isEmpty() || '/' in name) continue
            val kind = when (parts[1]) {
                "fix" -> Kind.Fix
                "warn" -> Kind.Warn
                else -> continue
            }
            out[name] = Hint(kind, parts.getOrElse(2) { "" }.trim().take(MAX_MESSAGE))
        }
        return out
    }

    /**
     * Tanda tangan keadaan folder: berubah bila ada berkas ditambah/dihapus/diubah atau sakelar tips berganti.
     * [files] = pasangan (nama, waktu ubah + ukuran). Dipakai agar pemindaian PRoot hanya jalan saat ada perubahan.
     */
    fun signature(files: List<Triple<String, Long, Int>>, notesOn: Boolean): String =
        (if (notesOn) "on" else "off") + "|" +
            files.sortedBy { it.first }.joinToString("|") { "${it.first}:${it.second}:${it.third}" }

    private const val MAX_MESSAGE = 300
}
