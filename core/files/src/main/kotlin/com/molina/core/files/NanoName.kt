package com.molina.core.files

/** [NANO-EXT] Pecah/gabung nama berkas + daftar ekstensi umum. Dotfile (.bashrc) dianggap tanpa ekstensi. */
object NanoName {
    val EXTENSIONS = listOf( // [NANO-TEMPLATE] urutan: skrip di atas; md/txt di bawah (sudah dipakai code editor)
        "sh", "py", "kt", "kts", "java", "json", "xml", "yml", "yaml", "toml", "ini", "conf",
        "log", "csv", "html", "css", "js", "c", "cpp", "h", "gradle", "properties", "md", "txt"
    )
    const val NONE = "(tanpa)"
    /** [NANO-TEMPLATE] Nama bawaan "Berkas baru" dari Terminal: tetap, tidak mengikuti berkas terakhir di Penjelajah. */
    const val TEMPLATE = "skrip.sh"

    /** "skrip.tar.gz" -> ("skrip.tar", "gz"); ".bashrc" -> (".bashrc", ""); "README" -> ("README", ""). */
    fun split(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) name to "" else name.substring(0, dot) to name.substring(dot + 1)
    }

    fun join(stem: String, ext: String): String {
        val e = ext.trim().trimStart('.')
        return if (e.isEmpty() || e == NONE) stem.trim() else stem.trim() + "." + e
    }

    /** Nama bawaan: mengikuti berkas terakhir di Penjelajah bila ada, bila tidak "catatan.txt". */
    fun initial(last: String?): Pair<String, String> = split(last ?: TEMPLATE)
}
