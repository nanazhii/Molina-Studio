package com.molina.core.common

/**
 * Penyamaran data sensitif untuk log. URL stream sering memuat token atau
 * kredensial, jadi yang dicatat hanya skema + host (tanpa userinfo, path,
 * query). Fungsi murni tanpa dependensi Android, aman dari thread mana pun.
 */
object LogRedact {
    private val URL_RE = Regex("""[A-Za-z][A-Za-z0-9+.\-]*://[^\s"'<>]+""")
    private val SECRET_RE = Regex(
        """(?i)\b(token|access_token|api[_-]?key|key|password|passwd|pwd|""" +
            """secret|sig|signature|auth|session)=([^&\s"']+)"""
    )
    private val OPAQUE_SCHEMES = setOf("data", "memory", "edl", "lavf")
    private val TRAIL = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}')

    /** Samarkan satu URL atau path. Path lokal hanya menyisakan nama berkas. */
    fun target(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return t
        val idx = t.indexOf("://")
        if (idx <= 0) return localName(t)
        val scheme = t.substring(0, idx).lowercase()
        if (scheme in OPAQUE_SCHEMES) return "$scheme://..."
        val rest = t.substring(idx + 3)
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        val host = authority.substringAfterLast('@')
        return if (rest.length > authority.length) "$scheme://$host/..."
        else "$scheme://$host"
    }

    /** Samarkan semua URL dan pasangan rahasia (token=...) di teks bebas. */
    fun text(s: String): String {
        if (s.isEmpty()) return s
        val urls = URL_RE.replace(s) { m ->
            val core = m.value.trimEnd(*TRAIL)
            target(core) + m.value.substring(core.length)
        }
        return SECRET_RE.replace(urls) { "${it.groupValues[1]}=***" }
    }

    private fun localName(path: String): String {
        val p = path.trimEnd('/')
        val name = p.substringAfterLast('/')
        return if (name.length == p.length) name else ".../$name"
    }
}
