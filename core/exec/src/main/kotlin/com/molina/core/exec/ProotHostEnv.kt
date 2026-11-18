package com.molina.core.exec

import java.io.File

/**
 * [SSOT-PROOT-ENV] Satu-satunya pembangun lingkungan proses HOST untuk menjalankan libproot.so
 * (bukan env tamu Debian). Dipakai Terminal, Code, Stream, Penjelajah, dan HealthCheck; perbaikan
 * env PRoot cukup dilakukan di sini. JVM murni, tanpa Android.
 */
object ProotHostEnv {
    const val PROOT_LIB = "libproot.so"
    const val LOADER_LIB = "libproot_loader.so"
    const val TMP_DIR_NAME = "proot-tmp"

    /** Folder sementara PRoot di cache aplikasi; dibuat bila belum ada. */
    fun tmpDir(cacheDir: File): File = File(cacheDir, TMP_DIR_NAME).apply { mkdirs() }

    /** Lingkungan host (urutan tetap). [getenv] dapat diganti untuk pengujian. */
    fun map(
        nativeDir: String,
        tmpDir: File,
        homeDir: File,
        getenv: (String) -> String? = { System.getenv(it) },
    ): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m["PROOT_LOADER"] = "$nativeDir/$LOADER_LIB"
        m["PROOT_TMP_DIR"] = tmpDir.absolutePath
        m["TMPDIR"] = tmpDir.absolutePath
        m["PATH"] = "/system/bin"
        m["HOME"] = homeDir.absolutePath
        getenv("ANDROID_ROOT")?.let { m["ANDROID_ROOT"] = it }
        getenv("ANDROID_DATA")?.let { m["ANDROID_DATA"] = it }
        return m
    }

    /** Bentuk `KEY=VALUE` untuk API yang meminta Array<String> (mis. TerminalSession). */
    fun asArray(
        nativeDir: String,
        tmpDir: File,
        homeDir: File,
        getenv: (String) -> String? = { System.getenv(it) },
    ): Array<String> = map(nativeDir, tmpDir, homeDir, getenv).map { "${it.key}=${it.value}" }.toTypedArray()
}
