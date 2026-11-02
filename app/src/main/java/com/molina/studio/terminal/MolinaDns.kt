package com.molina.studio.terminal

import android.content.Context
import android.net.ConnectivityManager
import com.molina.core.common.LogLevel
import com.molina.studio.log.LogBus
import java.io.File
import java.nio.file.Files

/**
 * DNS guest Debian mengikuti DNS jaringan aktif perangkat. Resolver publik hanya dipakai
 * sebagai cadangan bila Android tidak melaporkan DNS sama sekali.
 * Membutuhkan izin ACCESS_NETWORK_STATE (izin normal, tanpa dialog).
 */
object MolinaDns {
    private val FALLBACK = listOf("1.1.1.1", "8.8.8.8")

    fun systemServers(ctx: Context): List<String> = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return@runCatching emptyList<String>()
        cm.getLinkProperties(net)?.dnsServers.orEmpty()
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .filter { !it.startsWith("127.") && it != "::1" }
            .distinct()
    }.getOrDefault(emptyList())

    /** Tulis ulang etc/resolv.conf guest (murah; dipanggil tiap sesi baru). */
    fun writeResolvConf(ctx: Context, rootfsDir: File) {
        val sys = systemServers(ctx)
        val body = sys.ifEmpty { FALLBACK }.joinToString("") { "nameserver $it\n" }
        val f = File(rootfsDir, "etc/resolv.conf")
        runCatching {
            if (Files.isSymbolicLink(f.toPath())) f.delete()
            if (!f.exists() || f.readText() != body) f.writeText(body)
        }.onFailure {
            LogBus.log("sistem", LogLevel.Warn, "Dns", "Gagal menulis resolv.conf: ${it.message}")
        }
        if (sys.isEmpty()) {
            LogBus.log("sistem", LogLevel.Info, "Dns", "DNS sistem tidak tersedia, memakai cadangan")
        }
    }
}
