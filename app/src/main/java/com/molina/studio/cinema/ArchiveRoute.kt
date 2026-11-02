package com.molina.studio.cinema

/**
 * [ARCHIVE5] Keputusan satu pintu: berkas ini arsip? lewat mesin mana? Murni (tanpa Android) supaya bisa diuji JVM.
 * Mesin bawaan ([ArchiveEngine]: ZIP, 7z, tar, gz/bz2/xz) SELALU didahulukan; [DebianFormats] hanya untuk sisanya
 * (RAR, ISO, CAB, multi-bagian, ...). Dengan begitu perilaku format lama tidak berubah sama sekali.
 */
internal object ArchiveRoute {
    fun supports(name: String): Boolean = ArchiveNames.detect(name) != null || DebianFormats.supports(name)

    /** True bila berkas hanya bisa dibuka lewat 7z di Debian. */
    fun viaDebian(name: String): Boolean = ArchiveNames.detect(name) == null && DebianFormats.supports(name)

    /** Label format untuk judul dialog ("7Z", "RAR", ...), atau null bila bukan arsip. */
    fun label(name: String): String? = ArchiveNames.detect(name)?.label ?: DebianFormats.detect(name)?.label

    /** Nama subfolder tujuan ekstrak. */
    fun folderName(name: String): String = if (viaDebian(name)) DebianFormats.folderName(name) else ArchiveNames.folderName(name)

    /** Apakah dialog perlu menawarkan kata sandi bila isi terenkripsi. Format Debian (RAR/7z/ZIP multi-bagian) bisa berkata sandi. */
    fun canPassword(name: String): Boolean = ArchiveNames.detect(name)?.hasPassword ?: DebianFormats.supports(name)

    /**
     * [ARCHIVE6] True bila kompres harus lewat `7z` di Debian: HANYA 7z bersandi (pustaka bawaan tidak bisa mengenkripsi 7z).
     * Semua format lain, termasuk 7z tanpa sandi dan ZIP AES, tetap lewat [ArchiveEngine] seperti sebelumnya.
     */
    fun compressViaDebian(opt: CompressOptions): Boolean =
        opt.format == CompressFormat.SEVEN_Z && !opt.password.isNullOrEmpty()
}
