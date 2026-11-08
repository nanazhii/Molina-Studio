package com.molina.studio.cinema

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale

/** [ARCHIVE] Format arsip yang dikenali Penjelajah Molina. */
internal enum class ArchiveFormat(val label: String) {
    ZIP("ZIP"), SEVEN_Z("7Z"), TAR("TAR"), TAR_GZ("TAR.GZ"), TAR_BZ2("TAR.BZ2"), TAR_XZ("TAR.XZ"),
    GZ("GZ"), BZ2("BZ2"), XZ("XZ");

    val isTar: Boolean get() = this == TAR || this == TAR_GZ || this == TAR_BZ2 || this == TAR_XZ
    /** gz/bz2/xz tanpa tar: satu berkas terkompresi. */
    val isSingle: Boolean get() = this == GZ || this == BZ2 || this == XZ
    /** Format yang bisa berkata sandi. */
    val hasPassword: Boolean get() = this == ZIP || this == SEVEN_Z
}

/** [ARCHIVE] Tujuan ekstrak: langsung ke folder tujuan, atau ke subfolder bernama sama dengan arsip. */
internal enum class ExtractTarget { HERE, NAMED_FOLDER }

/**
 * [ARCHIVE] Logika nama/jalur murni (tanpa Android, tanpa pustaka arsip) supaya bisa diuji di JVM.
 * Semua nama entri dari arsip dianggap tidak tepercaya: [cleanEntry] menolak ".." dan [safeTarget] memeriksa jalur kanonik.
 */
internal object ArchiveNames {
    /** Urutan penting: sufiks majemuk (.tar.gz) harus dicek sebelum sufiks tunggal (.gz). */
    private val SUFFIXES: List<Pair<String, ArchiveFormat>> = listOf(
        ".tar.gz" to ArchiveFormat.TAR_GZ, ".tgz" to ArchiveFormat.TAR_GZ,
        ".tar.bz2" to ArchiveFormat.TAR_BZ2, ".tbz2" to ArchiveFormat.TAR_BZ2, ".tbz" to ArchiveFormat.TAR_BZ2,
        ".tar.xz" to ArchiveFormat.TAR_XZ, ".txz" to ArchiveFormat.TAR_XZ,
        ".tar" to ArchiveFormat.TAR, ".7z" to ArchiveFormat.SEVEN_Z, ".zip" to ArchiveFormat.ZIP,
        ".gz" to ArchiveFormat.GZ, ".bz2" to ArchiveFormat.BZ2, ".xz" to ArchiveFormat.XZ
    )

    private fun match(name: String): Pair<String, ArchiveFormat>? {
        val l = name.lowercase(Locale.ROOT)
        return SUFFIXES.firstOrNull { l.endsWith(it.first) && l.length > it.first.length }
    }

    fun detect(name: String): ArchiveFormat? = match(name)?.second

    /** Nama tanpa sufiks arsip ("a.tar.gz" -> "a", "notes.txt.gz" -> "notes.txt"); "Ekstrak" bila kosong. */
    fun stem(name: String): String {
        val m = match(name) ?: return name.ifEmpty { "Ekstrak" }
        return name.substring(0, name.length - m.first.length).ifEmpty { "Ekstrak" }
    }

    /** Nama subfolder tujuan. Untuk gz/bz2/xz tunggal ekstensi isi dibuang ("notes.txt.gz" -> "notes"). */
    fun folderName(name: String): String {
        val s = stem(name)
        val fmt = detect(name)
        if (fmt != null && fmt.isSingle) {
            val cut = s.substringBeforeLast('.', s)
            if (cut.isNotEmpty()) return cut
        }
        return s
    }

    /**
     * Bersihkan nama entri: pemisah Windows jadi "/", awalan "/" dan "./" dibuang, segmen kosong dan "." dilewati.
     * Mengembalikan null bila ada segmen ".." atau karakter NUL (tidak aman), "" bila entri hanya akar (abaikan saja).
     */
    fun cleanEntry(raw: String): String? {
        if (raw.indexOf('\u0000') >= 0) return null
        val parts = ArrayList<String>()
        for (seg in raw.replace('\\', '/').split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            if (seg == "..") return null
            parts.add(seg)
        }
        return parts.joinToString("/")
    }

    /** Apakah entri [clean] termasuk pilihan [only]? null = semua. Kunci folder berakhiran "/" dan mencakup isinya. */
    fun selected(clean: String, isDir: Boolean, only: Set<String>?): Boolean {
        if (only == null) return true
        val key = if (isDir) clean.trimEnd('/') + "/" else clean
        if (key in only) return true
        for (o in only) if (o.endsWith("/") && key.startsWith(o)) return true
        return false
    }

    /** Jalur tujuan entri di bawah [base]; null bila jalur kanonik keluar dari [base] (zip-slip lewat symlink yang sudah ada). */
    fun safeTarget(base: File, clean: String): File? {
        val root = base.canonicalFile
        val out = File(root, clean).canonicalFile
        return if (out.path == root.path || out.path.startsWith(root.path + File.separator)) out else null
    }

    /** "nama (1).ext", "nama (2).ext", ... di [dir] sampai belum dipakai. */
    fun uniqueIn(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            f = File(dir, "$base ($i)$ext")
            if (!f.exists()) return f
            i++
        }
    }
}

/**
 * [ARCHIVE2] Format keluaran dialog Kompres. Level dibatasi demi RAM Android: xz/7z (LZMA2) maksimal 6 (kamus 8 MB,
 * sekitar 100 MB memori enkoder); preset 7-9 butuh 190-670 MB sehingga tidak ditawarkan.
 */
internal enum class CompressFormat(
    val label: String, val ext: String, val minLevel: Int, val maxLevel: Int, val defaultLevel: Int,
    val hasPassword: Boolean, val note: String
) {
    ZIP("ZIP", ".zip", 0, 9, 6, true, "Paling kompatibel. Bisa dilindungi kata sandi (AES-256)."),
    SEVEN_Z("7Z", ".7z", 0, 6, 6, true, "Rasio kecil (LZMA2). Bisa dilindungi kata sandi (AES-256); sandi dikerjakan 7z di Debian."),
    TAR_GZ("TAR.GZ", ".tar.gz", 1, 9, 6, false, "Umum di Linux/Debian."),
    TAR_BZ2("TAR.BZ2", ".tar.bz2", 1, 9, 9, false, "Lebih kecil dari gz, lebih lambat."),
    TAR_XZ("TAR.XZ", ".tar.xz", 0, 6, 6, false, "Sangat kecil, paling lambat."),
    TAR("TAR", ".tar", 0, 0, 0, false, "Hanya menggabungkan berkas, tanpa kompresi.");

    val hasLevel: Boolean get() = maxLevel > minLevel
    /** [ARCHIVE6] Hanya 7z yang bisa mengenkripsi nama berkas (`-mhe=on`); ZIP AES tidak. */
    val hasNameEncryption: Boolean get() = this == SEVEN_Z
    /** True bila hanya ZIP/7z yang punya level 0 = simpan saja (tanpa kompresi). */
    val zeroIsStore: Boolean get() = this == ZIP || this == SEVEN_Z
    fun clampLevel(level: Int): Int = level.coerceIn(minLevel, maxLevel)
}

/**
 * [ARCHIVE2] Pilihan Kompres: [baseName] tanpa ekstensi (mentah dari pengguna; divalidasi [CompressNames]).
 * [ARCHIVE6] [encryptNames] (khusus 7z bersandi): true = isi DAN nama berkas dienkripsi, false = isi saja.
 */
internal class CompressOptions(
    val format: CompressFormat, val level: Int, val baseName: String, val password: String?,
    val encryptNames: Boolean = false,
)

/** [ARCHIVE2] Logika nama/level murni untuk Kompres (diuji JVM tanpa pustaka arsip). */
internal object CompressNames {
    const val MAX_NAME_BYTES = 255

    /** Nama dasar: dipangkas, dan ekstensi format yang sama di belakangnya dibuang ("a.zip" + ZIP -> "a"). */
    fun cleanBaseName(raw: String, fmt: CompressFormat): String {
        val t = raw.trim()
        return if (t.length > fmt.ext.length && t.lowercase(Locale.ROOT).endsWith(fmt.ext)) t.substring(0, t.length - fmt.ext.length).trim() else t
    }

    /** Pesan galat untuk nama dasar [raw], atau null bila valid. */
    fun baseNameError(raw: String, fmt: CompressFormat): String? {
        val n = cleanBaseName(raw, fmt)
        if (n.isEmpty()) return "Nama tidak boleh kosong"
        if (n == "." || n == "..") return "Nama tidak valid"
        if (n.indexOf('/') >= 0 || n.indexOf('\\') >= 0 || n.indexOf('\u0000') >= 0) return "Nama tidak boleh memuat / atau \\"
        if ((n + fmt.ext).toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) return "Nama terlalu panjang"
        return null
    }

    fun outputName(raw: String, fmt: CompressFormat): String = cleanBaseName(raw, fmt) + fmt.ext

    /** Nama dasar bawaan: satu item = nama folder / nama berkas tanpa ekstensi; banyak item = nama folder induk atau "Arsip". */
    fun defaultBaseName(singleName: String?, singleIsDir: Boolean, parentName: String): String {
        if (singleName != null) {
            if (singleIsDir) return singleName
            val dot = singleName.lastIndexOf('.')
            return if (dot > 0) singleName.substring(0, dot) else singleName
        }
        return parentName.ifEmpty { "Arsip" }
    }

    /** "nama.ext", "nama (1).ext", ... sampai belum dipakai (tautan rusak juga dianggap memakai nama). */
    fun uniqueOutput(dir: File, base: String, ext: String): File {
        fun taken(f: File) = Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)
        var f = File(dir, base + ext)
        var i = 1
        while (taken(f)) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }

    /** Label level untuk dialog. */
    fun levelLabel(fmt: CompressFormat, level: Int): String = when {
        !fmt.hasLevel -> ""
        level == 0 && fmt.zeroIsStore -> "0 \u2022 Simpan saja (tanpa kompresi)"
        level == fmt.defaultLevel -> "$level \u2022 Seimbang"
        level == fmt.minLevel -> "$level \u2022 Tercepat"
        level == fmt.maxLevel -> "$level \u2022 Terkecil"
        else -> level.toString()
    }

    /** Ukuran ramah-baca (B/KB/MB/GB) untuk pesan hasil. */
    fun sizeText(b: Long): String = when {
        b < 1024L -> "$b B"
        b < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", b / 1024.0)
        b < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        else -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
    }
}
