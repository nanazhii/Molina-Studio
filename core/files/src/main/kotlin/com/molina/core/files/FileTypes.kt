package com.molina.core.files

import java.io.File
import java.util.Locale

/**
 * [FILE-CONTRACT] Aturan berkas yang dipakai bersama Penjelajah, Code, Terminal, dan mpv.
 * Murni JVM (tanpa Android/Compose). Satu sumber untuk: jenis berkas (ekstensi), berkas tersembunyi,
 * heuristik teks, urutan alami, dan urutan isi folder. Modul fitur tidak boleh menyalin aturan ini lagi.
 */
object FileTypes {
    val AUDIO: Set<String> = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "ac3", "dts", "mka")
    val VIDEO: Set<String> = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "ts", "m2ts", "mts", "flv", "wmv", "mpg", "mpeg", "ogv", "vob", "rmvb"
    )
    val LISTS: Set<String> = setOf("m3u", "m3u8", "pls", "cue")
    /** Media yang ditampilkan Penjelajah: audio + video + daftar putar. */
    val MEDIA: Set<String> = AUDIO + VIDEO + LISTS

    fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    fun isHidden(name: String): Boolean = name.startsWith(".")
    fun isVideo(name: String): Boolean = extOf(name) in VIDEO
    fun isAudio(name: String): Boolean = extOf(name) in AUDIO
    fun isMedia(name: String): Boolean = extOf(name) in MEDIA

    /** Heuristik teks: berkas biasa, maks 5 MB, 4 KB pertama tanpa byte NUL. Blocking: panggil dari IO. */
    fun looksText(f: File): Boolean {
        if (!f.isFile || f.length() > 5_000_000L) return false
        return try {
            f.inputStream().use { s ->
                val b = ByteArray(4096)
                val n = s.read(b)
                (0 until maxOf(n, 0)).none { b[it] == 0.toByte() }
            }
        } catch (e: Exception) {
            false
        }
    }
}
