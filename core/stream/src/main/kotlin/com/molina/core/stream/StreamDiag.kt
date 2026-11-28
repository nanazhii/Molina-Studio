package com.molina.core.stream

import java.util.ArrayDeque

/**
 * [YTDLP-RETRY] Catatan diagnosis resolve terakhir (percobaan format, galat ringkas) untuk tombol
 * "Salin log" di kartu galat pemutar. Isi sudah disamarkan pemanggil (LogRedact). Ring buffer kecil, aman lintas thread.
 * [DUP-LITERAL] Waktu dicatat relatif (+ms sejak catatan pertama): tanpa pola jam sendiri, jadi tidak menggandakan LogTime.
 */
object StreamDiag {
    private const val MAX = 60
    private val lines = ArrayDeque<String>()
    private var t0 = 0L

    @Synchronized fun add(msg: String) {
        val now = System.currentTimeMillis()
        if (lines.isEmpty()) t0 = now
        if (lines.size >= MAX) lines.removeFirst()
        lines.addLast("+" + (now - t0) + "ms " + msg.take(400))
    }

    @Synchronized fun dump(): String = lines.joinToString("\n")

    @Synchronized fun clear() { lines.clear() }
}
