package com.molina.cinema

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Pembaca teks berkas subtitle lokal (dipakai SubConvertUi). Kotlin murni (java.io), panggil dari thread IO. */
internal object SubTextFiles {
    const val MAX_BYTES = 2_000_000L

    /** Baca sebagai UTF-8 ketat; bila bukan UTF-8 sah, pakai windows-1252 (lazim pada subtitle lama). */
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
