package com.molina.core.fileops

import java.util.Locale

/**
 * [ARCHIVE4] Format yang dibuka lewat 7z di Debian PRoot (bukan commons-compress). Murni (tanpa Android) supaya
 * bisa diuji JVM. ZIP/7z/tar tetap dikerjakan [ArchiveEngine]; kelas ini hanya untuk yang tidak dikuasainya.
 */
object DebianFormats {
    class Info(val label: String, val firstVolume: Boolean, val stem: String)

    const val NOT_FIRST_HINT = "Ini bukan bagian pertama arsip. Pilih bagian pertama (.001 atau .part1.rar)."

    private val SINGLE = Regex("""^(.+)\.(rar|iso|cab|wim|arj|lzh|lha|cpio|deb|rpm|dmg)$""", RegexOption.IGNORE_CASE)
    private val VOLUME = Regex("""^(.+)\.(7z|zip|rar)\.(\d{3})$""", RegexOption.IGNORE_CASE)
    private val PART_RAR = Regex("""^(.+)\.part(\d+)\.rar$""", RegexOption.IGNORE_CASE)

    fun detect(name: String): Info? {
        PART_RAR.matchEntire(name)?.let { m ->
            val n = m.groupValues[2].toIntOrNull() ?: return null
            return Info("RAR", n == 1, m.groupValues[1])
        }
        VOLUME.matchEntire(name)?.let { m ->
            val n = m.groupValues[3].toIntOrNull() ?: return null
            return Info(m.groupValues[2].uppercase(Locale.ROOT), n == 1, m.groupValues[1])
        }
        SINGLE.matchEntire(name)?.let { m ->
            return Info(m.groupValues[2].uppercase(Locale.ROOT), true, m.groupValues[1])
        }
        return null
    }

    fun supports(name: String): Boolean = detect(name) != null

    /** Nama subfolder tujuan ("a.part1.rar" -> "a"); "Ekstrak" bila kosong. */
    fun folderName(name: String): String = detect(name)?.stem?.ifEmpty { null } ?: ArchiveNames.folderName(name)
}
