package com.molina.code.preview

/** Jenis keluaran yang bisa dihasilkan tombol Play untuk sebuah berkas. */
enum class PreviewKind { NONE, MARKDOWN, JSON, HTML, SCRIPT }

/**
 * Penentu jenis dari nama berkas (dan baris pertama untuk shebang). Murni JVM, jadi bisa diuji
 * tanpa Android. Berkas tanpa nama (tab "Baru") hanya dikenali lewat shebang.
 */
object PreviewKinds {
    fun of(fileName: String?, firstLine: String? = null): PreviewKind {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when (ext) {
            "md", "markdown", "mdown" -> PreviewKind.MARKDOWN
            "json" -> PreviewKind.JSON
            "html", "htm", "xhtml" -> PreviewKind.HTML
            "sh", "bash", "py" -> PreviewKind.SCRIPT
            else -> if (RunPlans.of(fileName, firstLine) != null && firstLine?.startsWith("#!") == true) {
                PreviewKind.SCRIPT
            } else {
                PreviewKind.NONE
            }
        }
    }
}
