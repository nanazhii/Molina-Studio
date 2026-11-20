package com.molina.core.common

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

/** [FILE-CONTRACT] Urutan alami: "ep2" sebelum "ep10", huruf besar/kecil diabaikan. */
object NaturalOrder {
    private val NUM_PARTS = Regex("\\d+|\\D+")

    fun compare(a: String, b: String): Int {
        val x = NUM_PARTS.findAll(a.lowercase()).map { it.value }.toList()
        val y = NUM_PARTS.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                val pt = p.trimStart('0')
                val qt = q.trimStart('0')
                if (pt.length != qt.length) pt.length - qt.length else pt.compareTo(qt)
            } else p.compareTo(q)
            if (c != 0) return c
        }
        return x.size - y.size
    }
}

/** [FILE-CONTRACT] Urutan isi folder: folder dulu, lalu berkas, nama tanpa peka huruf. */
object DirOrder {
    fun <T> foldersFirst(isDir: (T) -> Boolean, name: (T) -> String): Comparator<T> =
        compareBy<T>({ !isDir(it) }, { name(it).lowercase(Locale.ROOT) })

    /** Isi [dir] terurut; kosong bila tidak terbaca. isDirectory dibaca sekali per berkas. Blocking: panggil dari IO. */
    fun list(dir: File, showHidden: Boolean = true): List<File> {
        val kids = dir.listFiles() ?: return emptyList()
        return kids.filter { showHidden || !FileTypes.isHidden(it.name) }
            .map { it to it.isDirectory }
            .sortedWith(foldersFirst<Pair<File, Boolean>>({ it.second }, { it.first.name }))
            .map { it.first }
    }
}
