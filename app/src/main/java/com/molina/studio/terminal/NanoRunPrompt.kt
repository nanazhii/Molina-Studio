package com.molina.studio.terminal

/**
 * [NANO-RUNFIX] Parser dialog "Molina · periksa skrip" milik molina-run. JVM murni agar bisa diuji di scripts/app-tests.
 * Dialog dicetak molina-run di terminal dengan baris akhir seperti "└ Pilih [1/2/a/3/e/x] (Enter = 1): ".
 * Daftar tombol dan bawaan (Enter) dibaca dari teks itu, jadi tombol selalu sama dengan opsi yang benar-benar ditawarkan
 * (bawaan 1, 3, atau x tergantung kasus; tanpa perbaikan hanya 3/e/x). Teks di molina-run.sh dan di sini WAJIB diubah bersamaan; kuncinya
 * dijaga oleh scripts/app-tests/run_nano_runprompt.sh yang menjalankan molina-run asli lewat pty.
 */
internal object NanoRunPrompt {
    /** Judul sesi yang dipasang molina-run selama dialog menunggu jawaban (dihapus lagi sesudahnya). */
    const val TITLE_PREFIX = "nano-run: "

    data class Choice(val key: Char, val label: String, val isDefault: Boolean)

    private const val VALID = "123aex"
    private val OPTIONS = Regex("""Pilih \[([^\]]*)\]""")
    private val DEFAULT = Regex("""Enter =\s*(\S)\)""")

    fun labelOf(key: Char): String = when (key) {
        '1' -> "PERBAIKI"   // perbaiki sementara, berkas tidak diubah
        '2' -> "BERKAS"     // perbaiki berkas (cadangan .bak)
        'a' -> "SELALU"     // seperti 1, dan jangan tanya lagi
        '3' -> "ADANYA"     // jalankan apa adanya
        'e' -> "EDIT"       // edit lagi di nano
        'x' -> "BATAL"
        else -> key.toString().uppercase()
    }

    /** Urutan yang dikirim ke terminal untuk satu pilihan: tombol + Enter (molina-run membaca satu baris). */
    fun sequenceOf(choice: Choice): String = "" + choice.key + "\r"

    /**
     * Cari prompt "Pilih [...]" terbawah di [rows] (baris layar). Prompt yang terlipat ke baris berikutnya (layar sempit)
     * disambung dengan baris sesudahnya. Hasil null bila tidak ada prompt yang sah (token di luar 1/2/a/3/e/x, atau
     * tanpa 3 dan x) agar tombol tidak pernah mengirim jawaban yang tidak ditawarkan.
     */
    fun parse(rows: List<String>): List<Choice>? {
        for (i in rows.indices.reversed()) {
            if (!rows[i].contains("Pilih")) continue
            val joined = rows[i] + rows.getOrElse(i + 1) { "" }
            val m = OPTIONS.find(joined) ?: continue
            val keys = m.groupValues[1].split('/')
            if (keys.any { it.length != 1 || it[0] !in VALID }) continue
            if (keys.distinct().size != keys.size) continue
            if ("3" !in keys || "x" !in keys) continue
            val def = DEFAULT.find(joined, m.range.last)?.groupValues?.get(1)?.get(0)
            return keys.map { Choice(it[0], labelOf(it[0]), it[0] == def) }
        }
        return null
    }
}
