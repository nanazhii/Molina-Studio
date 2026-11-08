package com.molina.core.common

/**
 * [INTENT-TOKEN] Pembanding token untuk intent pintasan. MainActivity bersifat exported (wajib untuk launcher), jadi
 * aplikasi mana pun bisa mengirim extra molina_open_tab atau molina_run_script. Aksi yang punya efek samping
 * (putar terakhir, dialog jalankan skrip) hanya dijalankan bila intent membawa token acak per-pemasangan.
 * Batas: token ikut terbaca launcher bawaan (yang memang tepercaya untuk membaca pintasan), bukan aplikasi biasa.
 * Murni, tanpa Android.
 */
object IntentToken {
    /** 16 byte acak = 32 karakter heks. Token lebih pendek dianggap tidak sah. */
    const val MIN_LENGTH = 32

    /** True hanya bila [expected] sah (tidak null, panjang cukup) dan sama persis dengan [given]; bandingan waktu-konstan. */
    fun matches(expected: String?, given: String?): Boolean {
        if (expected == null || expected.length < MIN_LENGTH || given == null) return false
        return java.security.MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), given.toByteArray(Charsets.UTF_8))
    }

    fun encode(raw: ByteArray): String {
        val sb = StringBuilder(raw.size * 2)
        for (b in raw) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 15])
        }
        return sb.toString()
    }
}
