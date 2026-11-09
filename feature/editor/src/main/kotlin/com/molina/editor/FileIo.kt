package com.molina.editor

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Akhir baris berkas. Editor selalu memegang teks LF; [Eol] dipakai saat menulis ke disk. */
internal enum class Eol(val label: String, val seq: String) { LF("LF", "\n"), CRLF("CRLF", "\r\n") }

/** Hasil membaca berkas. [text] sudah LF; null bila gagal (lihat [error]). */
internal class OpenResult(val text: String?, val eol: Eol, val error: String?)

/**
 * [EDITOR-IO] I/O berkas editor. Semua fungsi memblokir: panggil dari thread IO.
 * UTF-8 tanpa BOM, akhir baris asli dipertahankan, penulisan atomik.
 */
internal object FileIo {
    const val MAX_OPEN_BYTES = 5L * 1024 * 1024

    fun normalizeLf(s: String): String =
        if ('\r' !in s) s else s.replace("\r\n", "\n").replace('\r', '\n')

    /** CRLF bila baris berakhiran CRLF lebih banyak daripada LF murni; selain itu LF. */
    fun detectEol(s: String): Eol {
        var crlf = 0
        var lf = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') { crlf++; i += 2; continue }
            if (c == '\n') lf++
            i++
        }
        return if (crlf > lf) Eol.CRLF else Eol.LF
    }

    fun applyEol(text: String, eol: Eol): String {
        val lf = normalizeLf(text)
        return if (eol == Eol.CRLF) lf.replace("\n", "\r\n") else lf
    }

    /** Baca untuk dibuka: UTF-8 ketat, maks [MAX_OPEN_BYTES], tolak biner. BOM dibuang. */
    fun readForOpen(file: File): OpenResult {
        fun fail(msg: String) = OpenResult(null, Eol.LF, msg)
        return try {
            if (!file.isFile) return fail("Bukan berkas biasa")
            if (file.length() > MAX_OPEN_BYTES) return fail("Berkas terlalu besar (maks 5 MB)")
            val decoded = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(file.readBytes()))
                    .toString()
            } catch (e: CharacterCodingException) {
                return fail("Bukan teks UTF-8, tidak dibuka")
            }
            val raw = if (decoded.startsWith("\uFEFF")) decoded.substring(1) else decoded
            if ('\u0000' in raw) return fail("Berkas biner, tidak dibuka")
            OpenResult(normalizeLf(raw), detectEol(raw), null)
        } catch (e: Exception) {
            fail("Gagal membaca: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Tulis atomik: berkas sementara di folder yang sama lalu rename; bila rename gagal, tulis langsung. */
    @Throws(IOException::class)
    fun writeUtf8Atomic(target: File, text: String) {
        val dir = target.absoluteFile.parentFile ?: throw IOException("Folder tidak valid")
        val bytes = text.toByteArray(Charsets.UTF_8)
        val tmp = File(dir, ".${target.name}.molina-tmp")
        var done = false
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            done = tmp.renameTo(target)
        } catch (e: IOException) {
            done = false
        }
        if (!done) {
            tmp.delete()
            target.writeBytes(bytes)
        }
    }
}
