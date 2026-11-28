package com.molina.explorer

import com.molina.core.fileops.ExtractTarget
import java.io.File

// [ARCHIVE-DEST] Aturan tujuan ekstrak, tanpa Compose/Android supaya mudah diuji. UI ada di ArchiveExtractDestSheet.kt.

internal object ExtractDest {
    /** "Ekstrak di sini": folder tempat arsip berada bila bisa ditulisi, jika tidak folder yang sedang dibuka Penjelajah. */
    fun baseDir(archive: File, fallback: File): File =
        archive.parentFile?.takeIf { it.isDirectory && it.canWrite() } ?: fallback

    /** Kotak centang "Buat folder ..." aktif = isi masuk subfolder bernama arsip; mati = langsung ke folder tujuan. */
    fun target(namedFolder: Boolean): ExtractTarget =
        if (namedFolder) ExtractTarget.NAMED_FOLDER else ExtractTarget.HERE

    /** Jalur hasil yang diperkirakan, ditampilkan sebelum ekstrak (nama akhir bisa bertambah " (1)" bila bentrok). */
    fun preview(base: File, folderName: String, namedFolder: Boolean): String =
        if (namedFolder) base.path.trimEnd('/') + "/" + folderName else base.path

    /** Galat tujuan (null = siap). */
    fun error(base: File?): String? = when {
        base == null -> "Pilih folder tujuan terlebih dahulu."
        !base.isDirectory -> "Folder tujuan tidak ditemukan."
        !base.canWrite() -> "Folder tujuan tidak bisa ditulisi."
        else -> null
    }

    /** Baris keterangan di dialog hasil. [files] = -1 berarti jumlah tidak dihitung mesin. */ // [ARCHIVE-DEST-2]
    fun resultLines(files: Int, renamed: Int): List<String> {
        val out = ArrayList<String>()
        out.add(if (files >= 0) files.toString() + " berkas diekstrak" else "Semua isi arsip diekstrak")
        if (renamed > 0) out.add(renamed.toString() + " berkas diberi nama baru karena nama sudah ada")
        return out
    }
}
