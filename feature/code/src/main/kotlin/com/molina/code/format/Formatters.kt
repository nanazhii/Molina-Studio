package com.molina.code.format

import com.molina.code.preview.RunPlans

/**
 * Cara memformat satu jenis berkas [CODE-FORMAT]. Dua jenis:
 * - [BuiltinPlan]: pemformat ringan bawaan (JSON, XML), jalan di aplikasi, tanpa Debian.
 * - [ExternalPlan]: formatter terpasang di Debian PRoot (black, shfmt, prettier). Teks masuk lewat
 *   stdin dan hasil dibaca dari stdout, jadi berkas di disk tidak pernah disentuh.
 */
sealed interface FormatPlan {
    val label: String
}

class BuiltinPlan(override val label: String, val format: (String) -> String) : FormatPlan

/** [argv] dijalankan di guest tanpa shell; argv[0] dicari di PATH guest. [installHint] tampil bila belum terpasang. */
class ExternalPlan(
    override val label: String,
    val argv: List<String>,
    val installHint: String,
) : FormatPlan

object Formatters {
    private val JSON = BuiltinPlan("JSON bawaan") { JsonFormatter.format(it) }
    private val XML = BuiltinPlan("XML bawaan") { XmlFormatter.format(it) }

    private val BLACK = ExternalPlan(
        "black",
        listOf("black", "-q", "-"),
        "black belum ada di Debian. Di Terminal jalankan: apt install -y black",
    )
    private val SHFMT_POSIX = shfmt("posix")
    private val SHFMT_BASH = shfmt("bash")

    private fun shfmt(dialect: String) = ExternalPlan(
        "shfmt",
        listOf("shfmt", "-i", "2", "-ln", dialect),
        "shfmt belum ada di Debian. Di Terminal jalankan: apt install -y shfmt",
    )

    private const val PRETTIER_HINT =
        "prettier belum ada di Debian. Di Terminal jalankan: apt install -y nodejs npm && npm install -g prettier"

    private val XML_EXT = setOf("xml", "svg", "xsd", "xsl", "xslt", "plist", "gpx", "kml", "rss", "atom", "wsdl")
    private val PRETTIER_EXT = setOf(
        "js", "jsx", "mjs", "cjs", "ts", "tsx", "css", "scss", "less",
        "html", "htm", "md", "markdown", "yaml", "yml", "vue",
    )

    /**
     * Rencana format dari nama berkas dan baris pertama (shebang menang atas ekstensi untuk skrip).
     * Null = jenis ini belum punya pemformat.
     */
    fun of(fileName: String?, firstLine: String?): FormatPlan? {
        val name = fileName?.substringAfterLast('/').orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        RunPlans.of(fileName, firstLine)?.let { run ->
            return when (run.label) {
                "python3" -> BLACK
                "bash" -> SHFMT_BASH
                else -> SHFMT_POSIX
            }
        }
        return when {
            ext == "json" -> JSON
            ext in XML_EXT -> XML
            ext in PRETTIER_EXT -> prettier(name)
            else -> null
        }
    }

    private fun prettier(name: String) = ExternalPlan(
        "prettier",
        listOf("prettier", "--stdin-filepath=$name"),
        PRETTIER_HINT,
    )

    /** Ekstensi yang didukung, untuk pesan "jenis belum didukung". */
    const val SUPPORTED_SUMMARY = "py, sh, json, xml/svg, js/ts, css, html, md, yaml"
}
