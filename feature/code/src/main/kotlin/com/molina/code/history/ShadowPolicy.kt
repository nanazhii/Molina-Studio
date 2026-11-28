package com.molina.code.history

// [EXT-DIFF2] Kebijakan titik pantau (shadow history): berkas mana yang boleh direkam isinya untuk diff/pulihkan perubahan dari luar.
// Kotlin murni agar bisa diuji di JVM: scripts/code-tests/run_shadowpolicy.sh. Berkas rahasia dan biner tidak pernah direkam.
object ShadowPolicy {
    /** Sama dengan RunReport.MAX_BACKUP_FILE. */
    const val MAX_FILE_BYTES = 512L * 1024
    /** Plafon total snapshot berjenis titik pantau (di luar plafon riwayat 100 MB). */
    const val MAX_BYTES = 30L * 1024 * 1024
    /** Berkas baru yang direkam per putaran (bertahap), jumlah berkas yang diperiksa per putaran, dan total teks per putaran. */
    const val SEED_MAX_FILES = 300
    const val EXAMINE_MAX = 1500
    const val SEED_MAX_RAW = 16L * 1024 * 1024
    /** Berkas pada satu butir feed yang dilengkapi hitungan +/-. */
    const val ENRICH_MAX = 200

    class Entry(val path: String, val size: Long, val mtime: Long)

    private val BINARY_EXT = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic", "mp3", "mp4", "mkv", "avi", "mov", "wav", "flac", "ogg", "m4a",
        "zip", "gz", "tgz", "xz", "bz2", "7z", "rar", "jar", "apk", "aab", "dex", "so", "o", "a", "class", "pdf", "docx", "xlsx", "pptx",
        "ttf", "otf", "woff", "woff2", "db", "sqlite", "sqlite3", "bin", "iso", "img"
    )
    private val SECRET_EXT = setOf("pem", "key", "p12", "pfx", "keystore", "jks", "kdbx", "gpg", "p8", "ppk")
    private val SECRET_DIRS = setOf(".ssh", ".gnupg", ".aws")
    private val SECRET_NAMES = setOf(".netrc", ".npmrc", ".pgpass", ".git-credentials", ".htpasswd")
    private val SECRET_PREFIX = listOf("id_rsa", "id_ed25519", "id_ecdsa", "id_dsa", "credentials", "secret")

    /** Berkas yang kemungkinan berisi rahasia (kunci, token, kredensial). Konservatif: lebih baik tidak direkam. */
    fun isSecret(rel: String): Boolean {
        val parts = rel.replace('\\', '/').split('/')
        val name = parts.last().lowercase()
        if (parts.dropLast(1).any { it.lowercase() in SECRET_DIRS }) return true
        if (name == ".env" || name.startsWith(".env.")) return true
        if (name in SECRET_NAMES) return true
        if (SECRET_PREFIX.any { name.startsWith(it) }) return true
        return name.substringAfterLast('.', "") in SECRET_EXT
    }

    /** Layak direkam: kecil, bukan rahasia, bukan ekstensi biner. Pembacaan teks UTF-8 sebenarnya diperiksa pemanggil. */
    fun eligible(rel: String, size: Long): Boolean {
        if (size < 0 || size > MAX_FILE_BYTES || isSecret(rel)) return false
        val name = rel.substringAfterLast('/').lowercase()
        return name.substringAfterLast('.', "") !in BINARY_EXT
    }

    /** [EXT-DIFF4] Ukuran ringkas untuk tampilan: "512 KB", "12 MB", "1,5 MB". */
    fun sizeLabel(bytes: Long): String {
        val b = if (bytes < 0) 0L else bytes
        if (b < 1024L * 1024) return "${(b + 1023) / 1024} KB"
        val tenths = b * 10 / (1024L * 1024)
        return if (tenths % 10 == 0L) "${tenths / 10} MB" else "${tenths / 10},${tenths % 10} MB"
    }

    /** [EXT-DIFF4] Baris pemakaian ruang titik pantau di Pengaturan/feed. */
    fun usageLine(bytes: Long): String = "Titik pantau: ${sizeLabel(bytes)} dari ${sizeLabel(MAX_BYTES)}"

    /** [EXT-DIFF4] Daftar kecualian yang tampil ke pengguna (transparansi: apa yang TIDAK pernah direkam). */
    val EXCLUSION_NOTE: String =
        "Tidak pernah direkam: kunci dan sertifikat (.pem, .key, .p12, id_rsa), .env, folder .ssh/.gnupg/.aws, " +
            "berkas credentials/secret, berkas biner, dan berkas di atas ${sizeLabel(MAX_FILE_BYTES)}."

    /** Jalur layak, yang paling baru diubah dulu. */
    fun candidates(entries: List<Entry>): List<String> =
        entries.filter { eligible(it.path, it.size) }.sortedByDescending { it.mtime }.map { it.path }
}
