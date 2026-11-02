package com.molina.studio.cmd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.molina.core.common.LogLevel
import com.molina.studio.MainActivity
import com.molina.studio.R
import com.molina.studio.log.LogBus
import com.molina.studio.settings.MolinaExplorer
import com.molina.studio.terminal.TerminalPrefs
import com.molina.studio.ui.MolinaTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Bridge API Android untuk CLI `molina`: clip (baca/tulis clipboard), notify, share.
 * Dipanggil dari thread klien [CommandServer] (UID sama sudah diperiksa); yang menyentuh UI dipindah ke main thread.
 * Isi clipboard, teks notifikasi, dan teks share TIDAK dicatat ke log (hanya jenis aksi dan ukuran).
 */
internal object BridgeCommands {
    private const val TAG = "CommandBridge"
    private const val CHANNEL = "molina_cli"
    private const val NOTIF_BASE = 7100
    private const val NOTIF_SLOTS = 20
    private const val NOTIFY_MIN_GAP_MS = 300L

    private val slot = AtomicInteger(0)
    private val lastNotify = AtomicLong(0)
    private val main = Handler(Looper.getMainLooper())

    private fun log(level: LogLevel, msg: String) = LogBus.log("sistem", level, TAG, msg)

    /** Clipboard dikunci sakelar Setelan (default mati): semua program di Debian berbagi UID aplikasi. */
    fun clip(app: Context, c: CommandProtocol.ClipParse): String {
        TerminalPrefs.init(app)
        if (!TerminalPrefs.settings.value.cliClipboard) {
            return CommandProtocol.err("denied", "akses clipboard dari CLI mati: aktifkan di Setelan > Terminal > Molina CLI")
        }
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return when (c) {
            is CommandProtocol.ClipParse.Bad -> CommandProtocol.err(c.code, c.message)
            is CommandProtocol.ClipParse.Put -> runCatching {
                cm.setPrimaryClip(ClipData.newPlainText("Molina CLI", c.text))
                log(LogLevel.Info, "clip set (${c.text.length} karakter)")
                CommandProtocol.ok("clipboard diisi (${c.text.length} karakter)")
            }.getOrElse { CommandProtocol.err("clip", "gagal menulis clipboard: ${it.message}") }
            CommandProtocol.ClipParse.Get -> runCatching {
                val clip = cm.primaryClip
                val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(app)?.toString().orEmpty() else ""
                if (text.toByteArray(Charsets.UTF_8).size > CommandProtocol.MAX_CLIP) {
                    CommandProtocol.err("toolong", "isi clipboard lebih dari ${CommandProtocol.MAX_CLIP} byte")
                } else {
                    log(LogLevel.Info, "clip get (${text.length} karakter)")
                    CommandProtocol.okPayload(CommandProtocol.encodeB64(text))
                }
            }.getOrElse { CommandProtocol.err("clip", "gagal membaca clipboard: ${it.message}") }
        }
    }

    fun notify(app: Context, arg: String): String {
        val msg = CommandProtocol.parseNotify(arg) ?: return CommandProtocol.err("usage", "notify butuh teks")
        val now = SystemClock.elapsedRealtime()
        val prev = lastNotify.get()
        if (now - prev < NOTIFY_MIN_GAP_MS || !lastNotify.compareAndSet(prev, now)) {
            return CommandProtocol.err("rate", "terlalu cepat, tunggu sebentar")
        }
        return runCatching {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.areNotificationsEnabled()) {
                return@runCatching CommandProtocol.err("disabled", "notifikasi aplikasi dimatikan di pengaturan Android")
            }
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pesan dari CLI", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                app, 0,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_molina)
                .setColor(MolinaTheme.GOLD_ARGB)
                .setContentTitle(CommandProtocol.notifyHeadline(msg.title)) // [NOTIFY-SPOOF]
                .setSubText("Terminal Debian")
                .setContentText(msg.text)
                .setStyle(Notification.BigTextStyle().bigText(msg.text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_BASE + slot.getAndIncrement().mod(NOTIF_SLOTS), n)
            log(LogLevel.Info, "notify (${msg.text.length} karakter)")
            CommandProtocol.ok("notifikasi dikirim")
        }.getOrElse { CommandProtocol.err("notify", "gagal membuat notifikasi: ${it.message}") }
    }

    /** Membuka share sheet sistem; hasil akhirnya (dipilih atau dibatalkan) tidak diketahui CLI. */
    fun share(app: Context, arg: String): String = when (val s = CommandProtocol.parseShare(arg)) {
        is CommandProtocol.ShareParse.Bad -> CommandProtocol.err(s.code, s.message)
        is CommandProtocol.ShareParse.Text -> {
            main.post {
                runCatching {
                    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, s.text) }
                    app.startActivity(Intent.createChooser(send, "Bagikan").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { log(LogLevel.Warn, "share teks gagal: ${it.message}") }
            }
            log(LogLevel.Info, "share text (${s.text.length} karakter)")
            CommandProtocol.ok("membuka share sheet")
        }
        is CommandProtocol.ShareParse.Files -> shareFiles(app, s.paths)
    }

    private fun shareFiles(app: Context, paths: List<String>): String {
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
        main.post { MolinaExplorer.share(app, files) }
        log(LogLevel.Info, "share file (${files.size} berkas)")
        return CommandProtocol.ok("membuka share sheet untuk ${files.size} berkas")
    }
}
