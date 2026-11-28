package com.molina.cinema

/** [JUNK-GUARD] Manifest DASH/XML yang terbaca mpv sebagai playlist teks: tiap baris XML jadi entri palsu. */
internal object PlaylistJunk {
    fun isJunk(filename: String): Boolean { // [JUNK-HTML]
        val f = filename.trim()
        if (f.isEmpty()) return false
        if (MpvLoad.isMemoryPlaylist(f)) return false // [MEMORY-SAFE] [DUP-SSOT] satu sumber: MpvLoad playlist M3U in-memory bukan junk
        if (f.any { it == '<' || it == '>' || it == '"' || it == '{' || it == '}' }) return true
        // Baris teks biasa (CSS/HTML) bukan URL berskema dan bukan jalur absolut.
        val hasScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(f)
        return !hasScheme && !f.startsWith("/")
    }

    // [JUNK-CHILD] Baris halaman HTML/JS yang terbaca sebagai playlist disambung mpv ke direktori URL induk
    // (https://host/api/s/<baris>). Token path kanal sah tidak pernah memuat spasi, kurung, kutip, titik koma, dll.
    private val BAD_TAIL = Regex("""[\s(){}\[\]<>"'$|\\^`;]""")

    fun isJunkChild(filename: String, parentUrl: String?): Boolean {
        val p = parentUrl?.trim().orEmpty()
        if (p.isEmpty() || MpvLoad.isMemoryPlaylist(p)) return false // [DUP-SSOT]
        if (!(p.startsWith("http://", ignoreCase = true) || p.startsWith("https://", ignoreCase = true)) || filename == p) return false
        val dir = p.substringBefore('#').substringBefore('?').substringBeforeLast('/') + "/"
        if (!filename.startsWith(dir)) return false
        val tail = filename.substring(dir.length).substringBefore('#').substringBefore('?')
        return tail.isEmpty() || BAD_TAIL.containsMatchIn(tail)
    }

    // [JUNK-VIEW] Entri http(s) yang nama berkasnya (mentah, sebelum decode) memuat spasi/tag/kurung kurawal/kutip
    // hampir pasti baris CSS/HTML yang disambung ke URL induk. Hanya untuk tampilan daftar; tidak menghapus apa pun.
    private val MESSY_TAIL = Regex("""[\s<>{}"'|\\^`]""")

    fun isMessyRemoteEntry(filename: String): Boolean {
        val f = filename.trim()
        if (!(f.startsWith("http://", ignoreCase = true) || f.startsWith("https://", ignoreCase = true))) return false
        val tail = f.substringBefore('#').substringBefore('?').substringAfterLast('/')
        return MESSY_TAIL.containsMatchIn(tail)
    }

    /** Indeks entri palsu, urut turun supaya aman dihapus satu per satu. */
    fun junkIndices(filenames: List<String>, parentUrl: String? = null): List<Int> =
        filenames.indices.filter { isJunk(filenames[it]) || isJunkChild(filenames[it], parentUrl) }.sortedDescending()
}
