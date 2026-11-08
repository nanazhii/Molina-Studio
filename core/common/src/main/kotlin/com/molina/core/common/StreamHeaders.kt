package com.molina.core.common

/**
 * Penyaring header HTTP dari sumber tak tepercaya (resolver, extra intent) sebelum diberikan ke mpv.
 * Fungsi murni. Header yang tidak valid dibuang diam-diam; pemanggil bisa membandingkan ukuran
 * map untuk mencatat jumlah yang ditolak (jangan mencatat nilainya).
 */
object StreamHeaders {
    const val MAX_HEADERS = 16
    const val MAX_VALUE_LENGTH = 2048

    // Header pembingkai/koneksi diatur mpv/stack HTTP sendiri; mengirimnya dari luar berisiko.
    private val BLOCKED = setOf("host", "content-length", "transfer-encoding", "connection", "upgrade")
    private val NAME = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$")

    fun isValidName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 128 && NAME.matches(name) && name.lowercase() !in BLOCKED

    fun isValidValue(value: String): Boolean =
        value.length <= MAX_VALUE_LENGTH && value.none { it == '\u007f' || (it < ' ' && it != '\t') }

    fun sanitize(raw: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in raw) {
            if (out.size >= MAX_HEADERS) break
            val name = k.trim()
            val value = v.trim()
            if (isValidName(name) && isValidValue(value)) out[name] = value
        }
        return out
    }
}
