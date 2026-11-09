package com.molina.studio.code

import com.molina.core.common.YtdlpCommand

/** Argumen proot untuk satu skrip. Murni (tanpa Android) supaya mudah diuji (scripts/code-tests). */
internal object ScriptCommand {
    const val GUEST_TMP_DIR = "/tmp/molina-run"

    private val ENV_NAME = Regex("[A-Z_][A-Z0-9_]*")
    private val RESERVED_ENV = setOf("PATH", "HOME", "LANG", "TERM", "IFS", "SHELL")
    private const val MAX_ARGS = 256
    private const val MAX_ARG_CHARS = 4096

    /** Pesan galat siap tampil bila [args] atau [env] tidak boleh dipakai; null bila aman. */
    fun validate(args: List<String>, env: Map<String, String>): String? {
        if (args.size > MAX_ARGS) return "Terlalu banyak argumen skrip."
        if (args.any { it.length > MAX_ARG_CHARS || '\u0000' in it }) return "Argumen skrip tidak valid."
        for ((k, v) in env) {
            if (!ENV_NAME.matches(k)) return "Nama variabel lingkungan tidak valid."
            if (k in RESERVED_ENV || k.startsWith("LD_") || k.startsWith("PROOT")) return "Variabel lingkungan dilarang: $k"
            if (v.length > MAX_ARG_CHARS || '\u0000' in v) return "Nilai variabel lingkungan tidak valid: $k"
        }
        return null
    }

    /**
     * Argv libproot.so (tanpa argv[0]). [storageBinds] sudah berbentuk `--bind=host:guest`.
     * `env -i` menjaga lingkungan guest bersih; PYTHONUNBUFFERED supaya keluaran Python mengalir langsung.
     * [env] masuk sebagai NAMA=nilai sebelum interpreter, [args] menyusul setelah berkas skrip.
     * Tanpa shell di jalur ini, jadi spasi, kutip, `;` dan `$()` pada argumen tetap harfiah.
     */
    fun prootArgs(
        rootfs: String,
        cwd: String,
        storageBinds: List<String>,
        interpreter: String,
        guestScript: String,
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
    ): List<String> = prootArgv(rootfs, cwd, storageBinds, listOf(interpreter, guestScript) + args, env)

    /**
     * [CODE-FORMAT] Seperti [prootArgs] tetapi perintah guest diberikan utuh sebagai [argv] (mis. formatter
     * yang membaca stdin). Awalan dan [env] identik dengan [prootArgs]; tanpa shell.
     */
    fun prootArgv(
        rootfs: String,
        cwd: String,
        storageBinds: List<String>,
        argv: List<String>,
        env: Map<String, String> = emptyMap(),
    ): List<String> = buildList {
        add("--link2symlink"); add("--kill-on-exit"); add("-0")
        add("--rootfs=$rootfs"); add("--cwd=$cwd")
        add("--bind=/dev"); add("--bind=/proc"); add("--bind=/sys")
        addAll(storageBinds)
        add("/usr/bin/env"); add("-i")
        add("HOME=/root"); add("LANG=C.UTF-8"); add("TERM=dumb")
        add("MALLOC_ARENA_MAX=2"); add("DEBIAN_FRONTEND=noninteractive")
        add("PYTHONDONTWRITEBYTECODE=1"); add("PYTHONIOENCODING=utf-8"); add("PYTHONUNBUFFERED=1")
        add("PATH=${YtdlpCommand.GUEST_PATH}")
        for ((k, v) in env) add("$k=$v")
        addAll(argv)
    }
}
