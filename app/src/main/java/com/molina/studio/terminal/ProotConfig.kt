package com.molina.studio.terminal

import java.io.File

/**
 * Pembangun argumen PRoot per-sesi. Murni Kotlin (tanpa Android) agar bisa diuji di JVM.
 *
 * Urutan argumen identik dengan yang dulu ditulis langsung di TerminalSessions.debianShell,
 * ditambah `--sysvipc` hanya bila [sysvipc] true.
 */
class ProotConfig(
    val rootfs: String,
    val cwd: String,
    /** Argumen bind penuh ("--bind=host:guest"), dipasang setelah /dev, /proc, /sys. */
    val binds: List<String> = emptyList(),
    /** Variabel "K=V" untuk `env -i` di dalam guest. */
    val guestEnv: List<String> = emptyList(),
    val sysvipc: Boolean = false,
    val shell: List<String> = DEFAULT_SHELL,
) {
    fun args(): Array<String> = buildList {
        add("proot"); add("--link2symlink"); add("--kill-on-exit")
        if (sysvipc) add("--sysvipc")
        add("-0")
        add("--rootfs=$rootfs"); add("--cwd=$cwd")
        add("--bind=/dev"); add("--bind=/proc"); add("--bind=/sys")
        addAll(binds)
        add("/usr/bin/env"); add("-i")
        addAll(guestEnv)
        addAll(shell)
    }.toTypedArray()

    companion object {
        val DEFAULT_SHELL = listOf("/bin/bash", "--rcfile", "/etc/molina/bashrc", "-i")
    }
}

/** Pembuat argumen bind yang tervalidasi. */
object ProotBinds {
    /** null bila tidak valid: ':' memecah sintaks host:guest, guest wajib absolut, NUL/newline ditolak. */
    fun arg(host: String, guest: String): String? {
        if (host.isEmpty() || !guest.startsWith("/")) return null
        val bad = { s: String -> s.any { it == ':' || it == '\u0000' || it == '\n' } }
        if (bad(host) || bad(guest)) return null
        return "--bind=$host:$guest"
    }
}

/** Deteksi kemampuan binary PRoot bawaan: flag yang tak dikenal membuat proot langsung keluar. */
object ProotCaps {
    /** [name] tanpa tanda minus, mis. "sysvipc". true bila teksnya ada di tabel opsi binary. */
    fun supports(binary: ByteArray, name: String): Boolean {
        val n = name.toByteArray(Charsets.US_ASCII)
        if (n.isEmpty() || binary.size < n.size) return false
        outer@ for (i in 0..binary.size - n.size) {
            if (binary[i] != n[0]) continue
            for (j in 1 until n.size) if (binary[i + j] != n[j]) continue@outer
            return true
        }
        return false
    }

    fun supports(binary: File, name: String): Boolean =
        try { binary.isFile && supports(binary.readBytes(), name) } catch (e: Exception) { false }
}

/**
 * Berkas /proc palsu untuk Android yang memblokir pembacaan /proc/stat dan kawan-kawannya.
 * Hanya berkas yang TIDAK terbaca di host yang diganti; bila semuanya terbaca, tidak ada bind tambahan.
 */
object FakeProc {
    val NAMES = listOf("loadavg", "uptime", "stat", "version", "vmstat")

    fun content(name: String, cpus: Int): String? {
        val n = cpus.coerceIn(1, 64)
        return when (name) {
            "loadavg" -> "0.00 0.00 0.00 1/100 1000\n"
            "uptime" -> "1000.00 4000.00\n"
            "version" -> "Linux version 5.10.0-molina (molina@proot) (gcc 12.2.0) #1 SMP PREEMPT Thu Jan 1 00:00:00 UTC 2026\n"
            "stat" -> buildString {
                append("cpu  1000 0 500 20000 0 0 0 0 0 0\n")
                for (i in 0 until n) append("cpu$i 100 0 50 2000 0 0 0 0 0 0\n")
                append("intr 0\nctxt 0\nbtime 1767225600\nprocesses 1000\nprocs_running 1\nprocs_blocked 0\n")
                append("softirq 0 0 0 0 0 0 0 0 0 0 0\n")
            }
            "vmstat" -> "nr_free_pages 100000\nnr_active_anon 100000\nnr_inactive_anon 10000\n" +
                "pgpgin 0\npgpgout 0\npswpin 0\npswpout 0\npgfault 0\npgmajfault 0\n"
            else -> null
        }
    }

    /**
     * Tulis berkas palsu ke [dir] untuk /proc/<nama> yang tak bisa dibuka ([canOpen] false)
     * dan kembalikan argumen bind-nya. Aman dipanggil berulang (berkas ditulis ulang hanya bila beda).
     */
    fun prepare(dir: File, cpus: Int, canOpen: (String) -> Boolean): List<String> {
        val out = ArrayList<String>()
        for (name in NAMES) {
            if (canOpen("/proc/$name")) continue
            val text = content(name, cpus) ?: continue
            dir.mkdirs()
            val f = File(dir, name)
            if (!f.isFile || f.readText() != text) f.writeText(text)
            ProotBinds.arg(f.absolutePath, "/proc/$name")?.let(out::add)
        }
        return out
    }
}
