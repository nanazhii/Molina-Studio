package com.molina.core.common

import java.util.Locale

/**
 * Kunci hasil pencarian ke bahasa Indonesia. YouTube tidak punya filter bahasa: hl/gl hanya
 * menerjemahkan antarmuka dan judul yang punya terjemahan otomatis, judul asli pengunggah tetap.
 * Maka hasil diurutkan/disaring di sisi aplikasi dengan heuristik kata. JVM murni (bisa diuji).
 */
object SearchLang {
    /** Dibaca dari thread mana pun; diisi dari pengaturan saat aplikasi mulai. */
    @Volatile
    var enabled: Boolean = true

    /** Pakai judul asli pengunggah (bukan terjemahan YouTube) pada hasil pencarian; lihat [OriginalTitles]. */
    @Volatile
    var originalTitles: Boolean = true

    /** Bila yang lolos kurang dari ini, tidak ada yang dibuang (hanya diurutkan) agar hasil tak kosong. */
    const val MIN_KEEP = 5

    enum class Verdict { ID, NEUTRAL, OTHER }

    private val ID_WORDS = hashSetOf(
        "yang", "dan", "dengan", "untuk", "dari", "ini", "itu", "tidak", "bukan", "cara", "terbaru",
        "terbaik", "lengkap", "kisah", "cerita", "apa", "kenapa", "mengapa", "bagaimana", "jangan",
        "saya", "aku", "kamu", "kita", "kami", "mereka", "bisa", "akan", "sudah", "belum", "sedang",
        "pada", "adalah", "atau", "juga", "semua", "banyak", "hari", "orang", "anak", "rumah", "resep",
        "belajar", "jadi", "lagi", "aja", "banget", "nih", "dong", "sih", "gak", "nggak", "pakai",
        "buat", "sama", "kalau", "kalo", "karena", "tapi", "tetapi", "dalam", "oleh", "sangat",
        "paling", "pertama", "terakhir", "malam", "pagi", "berita", "ibu", "bapak", "kakak", "sekolah",
        "indonesia", "indo", "bahasa", "lagu", "kajian", "ceramah", "pemula", "mudah", "gampang",
        "terjemahan", "cuma", "ada", "tahun", "bulan", "minggu", "sekarang", "kemarin", "besok",
        "dulu", "masih", "harus", "punya", "lebih", "kurang", "bikin", "lihat", "nonton", "tonton",
    )

    private val EN_WORDS = hashSetOf(
        "the", "and", "of", "to", "in", "for", "with", "how", "what", "why", "you", "your", "is", "are",
        "was", "were", "my", "this", "that", "from", "best", "official", "new", "by", "on", "at", "it",
        "we", "they", "he", "she", "his", "her", "our", "can", "will", "not", "all", "about", "when",
        "where", "who", "which", "than", "then", "there", "their", "just", "more", "most", "only",
        "into", "out", "over", "after", "before", "one", "two", "do", "does", "did", "be", "been",
        "have", "has", "had", "if", "so", "but", "or",
    )

    private val SPLIT = Regex("[^\\p{L}]+")

    /** ID = jelas Indonesia, OTHER = jelas Inggris/asing (aksara non-Latin), NEUTRAL = tidak bisa dipastikan. */
    fun classify(title: String): Verdict {
        var letters = 0
        var foreign = 0
        var i = 0
        while (i < title.length) {
            val cp = title.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            letters++
            if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.LATIN) foreign++
        }
        if (letters == 0) return Verdict.NEUTRAL
        if (foreign * 100 / letters > 30) return Verdict.OTHER
        val words = title.lowercase(Locale.ROOT).split(SPLIT).filter { it.isNotEmpty() }
        val id = words.count { it in ID_WORDS }
        val en = words.count { it in EN_WORDS }
        return when {
            id > 0 && id >= en -> Verdict.ID
            id == 0 && en >= 2 -> Verdict.OTHER
            id == 0 && en == 1 && words.size >= 5 -> Verdict.OTHER
            else -> Verdict.NEUTRAL
        }
    }

    /**
     * Urutan stabil: Indonesia, lalu netral, (OTHER dibuang). Urutan relevansi YouTube tetap dijaga
     * di dalam tiap kelompok. Dimatikan -> hanya dipotong sampai [limit].
     */
    fun apply(items: List<StreamSearchItem>, limit: Int): List<StreamSearchItem> {
        val cap = limit.coerceAtLeast(0)
        if (!enabled) return items.take(cap)
        val tagged = items.map { it to classify(it.title) }
        val kept = tagged.filter { it.second == Verdict.ID }.map { it.first } +
            tagged.filter { it.second == Verdict.NEUTRAL }.map { it.first }
        val out = if (kept.size >= MIN_KEEP) kept else kept + tagged.filter { it.second == Verdict.OTHER }.map { it.first }
        return out.take(cap)
    }
}
