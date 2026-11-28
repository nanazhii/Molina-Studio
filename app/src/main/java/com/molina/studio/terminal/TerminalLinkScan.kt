package com.molina.studio.terminal

/**
 * Pemindai tautan dari teks transkrip Terminal. JVM murni (tanpa API Android) agar bisa diuji di scripts/app-tests.
 * Hanya mengenali skema yang juga diterima Molina Cinema: http, https, rtsp, rtmp, rtmps.
 */
internal object TerminalLinkScan {
    private const val MAX_URL_LEN = 2048
    private val URL = Regex("""(?i)\b(?:https?|rtsp|rtmps?)://[^\s<>"'`\u0000-\u001f\u007f]+""")
    private val HOST_OK = Regex("""^(?:[^/?#@\s]*@)?(?:\[[0-9a-fA-F:.]+]|[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?)(?::\d{1,5})?(?:[/?#].*)?$""")

    /**
     * Tautan unik, urutan terbaru lebih dulu (paling akhir muncul di transkrip = paling atas), maksimal [max].
     */
    fun extract(text: String, max: Int = 30): List<String> {
        if (text.isEmpty() || max <= 0) return emptyList()
        val found = ArrayList<String>()
        for (m in URL.findAll(text)) clean(m.value)?.let { found.add(it) }
        val out = LinkedHashSet<String>()
        for (i in found.indices.reversed()) {
            out.add(found[i])
            if (out.size >= max) break
        }
        return out.toList()
    }

    /** Buang tanda baca penutup kalimat dan kurung tak berpasangan; null bila bukan tautan yang layak. */
    internal fun clean(raw: String): String? {
        var u = raw
        while (u.isNotEmpty()) {
            val c = u.last()
            val drop = when (c) {
                '.', ',', ';', ':', '!', '?' -> true
                ')' -> u.count { it == ')' } > u.count { it == '(' }
                ']' -> u.count { it == ']' } > u.count { it == '[' }
                '}' -> u.count { it == '}' } > u.count { it == '{' }
                else -> false
            }
            if (!drop) break
            u = u.dropLast(1)
        }
        if (u.length > MAX_URL_LEN) return null
        val rest = u.substringAfter("://", "")
        if (rest.isEmpty() || !HOST_OK.matches(rest)) return null
        return u
    }
}
