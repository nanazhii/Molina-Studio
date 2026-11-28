package com.molina.code.format

/**
 * [CODE-FORMAT] Bantu format seleksi: potongan kode yang menjorok (mis. isi sebuah kelas) dilepas dulu
 * dari jorokan bersamanya supaya formatter bisa mem-parse-nya, lalu jorokan dipasang kembali.
 * Murni; dua arah (dedent lalu reindent dengan jorokan sama mengembalikan teks semula).
 */
object FragmentIndent {
    /** Awalan spasi/tab terpanjang yang dimiliki SEMUA baris tak kosong; kosong bila tidak ada. */
    fun common(text: String): String {
        var prefix: String? = null
        for (line in text.split('\n')) {
            if (line.isBlank()) continue
            val lead = line.takeWhile { it == ' ' || it == '\t' }
            prefix = if (prefix == null) lead else prefix.commonPrefixWith(lead)
            if (prefix.isEmpty()) break
        }
        return prefix ?: ""
    }

    fun dedent(text: String, indent: String): String {
        if (indent.isEmpty()) return text
        return text.split('\n').joinToString("\n") { if (it.startsWith(indent)) it.substring(indent.length) else if (it.isBlank()) "" else it }
    }

    /** Pasang [indent] di tiap baris tak kosong; baris kosong tetap kosong (tanpa spasi sisa). */
    fun reindent(text: String, indent: String): String {
        if (indent.isEmpty()) return text
        return text.split('\n').joinToString("\n") { if (it.isBlank()) "" else indent + it }
    }
}
