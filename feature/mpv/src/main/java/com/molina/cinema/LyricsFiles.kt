package com.molina.cinema

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * [LYRICS] Pencari dan pembaca berkas .lrc lokal. Kotlin murni (java.io), dipanggil dari thread IO.
 * Urutan pencarian: folder yang sama dengan lagu, lalu folder pustaka lirik Molina.
 * Tanpa jaringan: lirik online sengaja ditunda (butuh allowlist telemetry_gate + UrlPolicy).
 */
internal object LyricsFiles {
    const val MAX_BYTES = 2_000_000L
    const val USER_DIR = "/storage/emulated/0/Molina/Lyrics"

    /** Cari "<nama-lagu>.lrc" tanpa membedakan huruf besar/kecil. Mengembalikan null bila tidak ada. */
    fun find(mediaPath: String, userDir: String = USER_DIR): File? {
        val media = File(mediaPath)
        val name = media.nameWithoutExtension + ".lrc"
        if (name.length <= 4) return null // nama kosong: hanya ".lrc"
        for (dir in listOfNotNull(media.parentFile, File(userDir))) {
            val hit = dir.listFiles { f -> f.isFile && f.name.equals(name, ignoreCase = true) }
                ?.sortedBy { it.name }?.firstOrNull { it.length() in 1..MAX_BYTES }
            if (hit != null) return hit
        }
        return null
    }

    /** Baca sebagai UTF-8 ketat; bila bukan UTF-8 sah, pakai windows-1252 (lazim pada berkas .lrc lama). */
    fun readText(file: File): String? {
        if (!file.isFile || file.length() > MAX_BYTES) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, charset("windows-1252"))
        }
    }
}
