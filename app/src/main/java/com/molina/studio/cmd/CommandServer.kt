package com.molina.studio.cmd

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.molina.cinema.MpvFolder
import com.molina.core.common.LogLevel
import com.molina.studio.cinema.CinemaActions
import com.molina.studio.code.CodeNav
import com.molina.studio.log.LogBus
import com.molina.studio.system.MpvControl
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Server perintah CLI `molina` (Debian -> aplikasi): abstract Unix socket "@molina.cmd".
 * Hanya UID yang sama dengan aplikasi yang dilayani (peer credentials), seperti LogServer.
 * Perintah (lihat [CommandProtocol]): ping, version, status, play, stop. Tanpa izin atau host baru.
 */
object CommandServer {
    private const val MAX_CLIENTS = 8
    private const val READ_TIMEOUT_MS = 5000
    private const val TAG = "CommandServer"

    @Volatile private var server: LocalServerSocket? = null
    private val clients = AtomicInteger(0)

    @Synchronized
    fun start(ctx: Context) {
        if (server != null) return
        val app = ctx.applicationContext
        val s = try {
            LocalServerSocket(CommandProtocol.SOCKET_NAME)
        } catch (e: IOException) {
            LogBus.log("sistem", LogLevel.Error, TAG, "Gagal bind @${CommandProtocol.SOCKET_NAME}: ${e.message}")
            return
        }
        server = s
        LogBus.log("sistem", LogLevel.Info, TAG, "Mendengarkan @${CommandProtocol.SOCKET_NAME}")
        val t = Thread({ acceptLoop(s, app) }, "molina-cmd-accept")
        t.isDaemon = true
        t.start()
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (e: IOException) { }
        server = null
    }

    private fun acceptLoop(s: LocalServerSocket, app: Context) {
        while (true) {
            val c = try { s.accept() } catch (e: IOException) { break }
            if (clients.incrementAndGet() > MAX_CLIENTS) {
                clients.decrementAndGet()
                closeQuietly(c)
                continue
            }
            val t = Thread({
                try {
                    handle(c, app)
                } catch (e: IOException) {
                    // peer menutup koneksi atau timeout baca
                } finally {
                    clients.decrementAndGet()
                    closeQuietly(c)
                }
            }, "molina-cmd-client")
            t.isDaemon = true
            t.start()
        }
        synchronized(this) { if (server === s) server = null }
    }

    private fun handle(c: LocalSocket, app: Context) {
        val uid = try { c.peerCredentials.uid } catch (e: IOException) { -1 }
        if (uid != Process.myUid()) {
            LogBus.log("sistem", LogLevel.Warn, TAG, "Koneksi ditolak (uid $uid)")
            return
        }
        c.soTimeout = READ_TIMEOUT_MS
        val line = readLine(c.inputStream)
        val reply = when {
            line == null -> return
            line.tooLong -> CommandProtocol.err("toolong", "perintah terlalu panjang")
            else -> when (val p = CommandProtocol.parse(line.text)) {
                is CommandProtocol.Parsed.Bad -> CommandProtocol.err(p.code, p.message)
                is CommandProtocol.Parsed.Ok -> execute(app, p.req)
            }
        }
        c.outputStream.write(reply.toByteArray(Charsets.UTF_8))
        c.outputStream.flush()
    }

    private class Line(val text: String, val tooLong: Boolean)

    /** Baca satu baris berbatas [CommandProtocol.MAX_LINE] byte; null bila koneksi tutup tanpa data. */
    private fun readLine(input: InputStream): Line? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else Line(buf.toString("UTF-8"), false)
            if (b == '\n'.code) return Line(buf.toString("UTF-8"), false)
            if (buf.size() >= CommandProtocol.MAX_LINE) return Line("", true)
            buf.write(b)
        }
    }

    private fun execute(app: Context, r: CommandProtocol.Request): String = when (r.verb) {
        CommandProtocol.Verb.PING -> CommandProtocol.ok("pong")
        CommandProtocol.Verb.VERSION -> CommandProtocol.ok("molina-cmd ${CommandProtocol.VERSION}")
        CommandProtocol.Verb.STATUS ->
            CommandProtocol.ok(if (MpvControl.isActive()) "player=aktif" else "player=diam")
        CommandProtocol.Verb.STOP ->
            CommandProtocol.ok(if (MpvControl.shutdown(app)) "pemutar dihentikan" else "tidak ada pemutar aktif")
        CommandProtocol.Verb.PLAY -> gated { play(NewTaskContext(app), r.arg) }
        CommandProtocol.Verb.OPEN -> gated { open(app, r.arg) }
        CommandProtocol.Verb.CLIP -> BridgeCommands.clip(app, CommandProtocol.parseClip(r.arg))
        CommandProtocol.Verb.NOTIFY -> BridgeCommands.notify(app, r.arg)
        CommandProtocol.Verb.SHARE -> gated { BridgeCommands.share(app, r.arg) }
    }

    /** [CLI-SCOPE] Aksi yang membuka UI dibatasi lajunya. */
    private inline fun gated(block: () -> String): String =
        if (CliScope.rateLimited()) CommandProtocol.err("rate", "terlalu cepat, tunggu sebentar") else block()

    private fun play(ctx: Context, arg: String): String {
        if (arg.contains("://")) {
            return when (val i = CinemaActions.classify(arg)) {
                is CinemaActions.Input.Direct ->
                    if (CinemaActions.play(ctx, i.uri)) CommandProtocol.ok("memutar URL")
                    else CommandProtocol.err("launch", "gagal membuka pemutar")
                else -> CommandProtocol.err("url", "URL tidak didukung (http, https, rtsp, rtmp, rtmps)")
            }
        }
        val root = Environment.getExternalStorageDirectory().absolutePath
        val host = CommandProtocol.guestToHost(arg, root)
            ?: return CommandProtocol.err("path", "path harus di bawah /sdcard atau /storage/emulated/0")
        CliScope.deny(ctx, host, root, arg)?.let { return it } // [CLI-SCOPE]
        val f = File(host)
        return when {
            f.isFile ->
                if (CinemaActions.playPath(ctx, host)) CommandProtocol.ok("memutar berkas")
                else CommandProtocol.err("launch", "gagal membuka pemutar")
            f.isDirectory -> {
                val first = MpvFolder.firstMediaIn(f)
                when {
                    first == null -> CommandProtocol.err("empty", "tidak ada media di folder")
                    CinemaActions.playFolder(ctx, first) -> CommandProtocol.ok("memutar folder")
                    else -> CommandProtocol.err("launch", "gagal membuka pemutar")
                }
            }
            else -> CommandProtocol.err("missing", "berkas tidak ditemukan")
        }
    }

    /** Buka berkas di editor Code (tab Code); MainActivity yang mengonsumsi CodeNav.pendingFiles. */
    private fun open(app: Context, arg: String): String {
        val paths = CommandProtocol.splitPaths(arg)
        if (paths.isEmpty()) return CommandProtocol.err("usage", "open butuh minimal satu berkas")
        if (paths.size > CommandProtocol.MAX_OPEN) {
            return CommandProtocol.err("many", "maksimal ${CommandProtocol.MAX_OPEN} berkas")
        }
        val root = Environment.getExternalStorageDirectory().absolutePath
        val files = ArrayList<File>(paths.size)
        for (p in paths) {
            val host = CommandProtocol.guestToHost(p, root)
                ?: return CommandProtocol.err("path", "path harus di bawah /sdcard atau /storage/emulated/0: $p")
            CliScope.deny(app, host, root, p)?.let { return it } // [CLI-SCOPE]
            val f = File(host)
            if (!f.isFile) return CommandProtocol.err("missing", "bukan berkas: $p")
            files.add(f)
        }
        Handler(Looper.getMainLooper()).post { CodeNav.pendingFiles = files }
        return CommandProtocol.ok("membuka ${files.size} berkas di Code")
    }

    /** startActivity dari konteks Application wajib FLAG_ACTIVITY_NEW_TASK; CinemaActions tidak menambahkannya. */
    private class NewTaskContext(base: Context) : ContextWrapper(base) {
        override fun startActivity(intent: Intent) {
            super.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun closeQuietly(c: LocalSocket) {
        try { c.close() } catch (e: IOException) { }
    }
}
