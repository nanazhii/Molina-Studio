package com.molina.core.net

/**
 * [HDR-NAMES] Satu sumber untuk nama header HTTP yang dipakai berulang. Modul lain cukup mengacu ke sini
 * (core:stream sudah api ke core:net) agar literalnya tidak tersebar dan dup_gate tetap hijau.
 */
object HttpHeaderNames {
    const val REFERER = "Referer"
    const val ORIGIN = "Origin"
    const val USER_AGENT = "User-Agent"
    const val ACCEPT_LANGUAGE = "Accept-Language"
}
