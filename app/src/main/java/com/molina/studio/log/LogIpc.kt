package com.molina.studio.log

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import com.molina.core.common.LogLevel
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicInteger

/**
 * Server IPC log: abstract-namespace Unix socket "@molina.log" (Android tidak punya /tmp).
 * Protokol: satu baris per event, UTF-8, kolom dipisah TAB:
 *   source \t level(D/I/W/E) \t tag \t message \n
 * Baris tanpa 4 kolom dicatat apa adanya sebagai source "luar". Hanya UID yang sama
 * dengan aplikasi yang diterima (dicek lewat peer credentials).
 */
object LogServer {
    const val SOCKET_NAME = "molina.log"
    private const val MAX_CLIENTS = 16
    private const val MAX_LINE = 4096

    @Volatile private var server: LocalServerSocket? = null
    private val clients = AtomicInteger(0)

    @Synchronized
    fun start() {
        if (server != null) return
        val s = try {
            LocalServerSocket(SOCKET_NAME)
        } catch (e: IOException) {
            LogBus.log("sistem", LogLevel.Error, "LogServer", "Gagal bind @$SOCKET_NAME: ${e.message}")
            return
        }
        server = s
        LogBus.log("sistem", LogLevel.Info, "LogServer", "Mendengarkan @$SOCKET_NAME")
        val t = Thread({ acceptLoop(s) }, "molina-log-accept")
        t.isDaemon = true
        t.start()
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (e: IOException) {}
        server = null
    }

    private fun acceptLoop(s: LocalServerSocket) {
        while (true) {
            val c = try { s.accept() } catch (e: IOException) { break }
            if (clients.incrementAndGet() > MAX_CLIENTS) {
                clients.decrementAndGet()
                closeQuietly(c)
                continue
            }
            val t = Thread({
                try {
                    handle(c)
                } catch (e: IOException) {
                    // peer menutup koneksi
                } finally {
                    clients.decrementAndGet()
                    closeQuietly(c)
                }
            }, "molina-log-client")
            t.isDaemon = true
            t.start()
        }
        synchronized(this) { if (server === s) server = null }
    }

    private fun handle(c: LocalSocket) {
        val uid = try { c.peerCredentials.uid } catch (e: IOException) { -1 }
        if (uid != Process.myUid()) {
            LogBus.log("sistem", LogLevel.Warn, "LogServer", "Koneksi ditolak (uid $uid)")
            return
        }
        val reader = BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8))
        while (true) {
            val line = readBoundedLine(reader) ?: break
            if (line.isNotBlank()) dispatch(line)
        }
    }

    private fun readBoundedLine(r: BufferedReader): String? {
        val sb = StringBuilder()
        while (true) {
            val ch = r.read()
            if (ch < 0) return if (sb.isEmpty()) null else sb.toString()
            if (ch == '\n'.code) return sb.toString()
            if (ch != '\r'.code && sb.length < MAX_LINE) sb.append(ch.toChar())
        }
    }

    private fun dispatch(line: String) {
        val p = line.split('\t', limit = 4)
        if (p.size == 4) {
            LogBus.log(p[0], LogLevel.fromCode(p[1].firstOrNull() ?: 'I'), p[2], p[3])
        } else {
            LogBus.log("luar", LogLevel.Info, "ipc", line)
        }
    }

    private fun closeQuietly(c: LocalSocket) {
        try { c.close() } catch (e: IOException) {}
    }
}

/** Pengirim log lewat socket (untuk uji jalur IPC penuh; producer dalam-proses cukup pakai LogBus.log). */
object LogClient {
    fun send(source: String, level: LogLevel, tag: String, message: String): Boolean {
        val payload = listOf(source, level.code.toString(), tag, message)
            .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ') } + "\n"
        return try {
            LocalSocket().use { s ->
                s.connect(
                    LocalSocketAddress(LogServer.SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT)
                )
                // [IPC-PEER] Socket abstrak tidak punya izin berkas: aplikasi lain bisa mendahului bind nama ini.
                // Jangan kirim log ke server yang UID-nya bukan UID Molina.
                if (s.peerCredentials.uid != Process.myUid()) return false
                s.outputStream.write(payload.toByteArray(Charsets.UTF_8))
                s.outputStream.flush()
            }
            true
        } catch (e: IOException) {
            false
        }
    }
}
