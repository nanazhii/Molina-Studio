package com.molina.core.common

/**
 * [SHARE-GUARD] Penentu berkas kunci/kredensial yang tidak boleh keluar dari Molina lewat FileProvider
 * (Bagikan, Buka dengan). Berdasarkan nama dan lokasi, tanpa membaca isi. Murni, tanpa Android.
 * Batas: berkas rahasia yang diberi nama lain di luar folder dikenal tidak terdeteksi.
 */
object SensitivePaths {
    private val SENSITIVE_DIRS = setOf(".ssh", ".gnupg", "molina-keys", ".aws", ".kube", ".docker", ".password-store")
    private val SENSITIVE_NAMES = setOf(".git-credentials", ".netrc", ".npmrc", ".pypirc", ".env", ".bash_history", ".zsh_history", ".python_history",
        "id_rsa", "id_ed25519", "id_ecdsa", "id_dsa") // [SHARE-GUARD+]
    private val KEY_SUFFIXES = listOf(".p12", ".pfx", ".jks", ".keystore", ".p12.b64", ".ppk", ".kdbx", ".kdb") // [SHARE-GUARD++]
    /** [SHARE-GUARD++] Pasangan folder/anak yang berisi token atau kredensial (cocok persis, bukan awalan). */
    private val SENSITIVE_PAIRS = setOf(".config/gh", ".config/gcloud", ".config/rclone", ".config/git",
        ".git/config", ".cargo/credentials", ".cargo/credentials.toml", ".gem/credentials")
    private val EXTRA_DIRS = setOf(".azure", ".terraform.d")
    private val EXTRA_NAMES = setOf(".pgpass", ".my.cnf", ".s3cfg", ".boto", ".vault-token", ".htpasswd")

    fun isSensitive(path: String): Boolean {
        val parts = path.replace('\\', '/').lowercase().split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        if (parts.any { it in SENSITIVE_DIRS }) return true
        if (parts.any { it in EXTRA_DIRS }) return true
        for (i in 0 until parts.size - 1) if ((parts[i] + "/" + parts[i + 1]) in SENSITIVE_PAIRS) return true
        val name = parts.last()
        // Salinan base64/teks kunci tanda tangan (mis. molina-b64.txt) dikenali dari namanya.
        if (name.startsWith("molina-b64") || name.contains("keystore")) return true
        return name in SENSITIVE_NAMES || name in EXTRA_NAMES || KEY_SUFFIXES.any { name.endsWith(it) }
    }
}
